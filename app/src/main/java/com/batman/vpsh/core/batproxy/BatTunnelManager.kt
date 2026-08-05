package com.batman.vpsh.core.batproxy

import com.batman.vpsh.data.BatWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min
import kotlin.math.pow

class BatTunnelManager(workers: List<BatWorker>) {

    private data class DestCacheEntry(val workerUrl: String, val expiry: Double)

    private val healthMap: Map<String, WorkerHealth> =
        workers.associate { it.url to WorkerHealth(it) }
    private val destCache = ConcurrentHashMap<String, DestCacheEntry>()

    private val totalCount = AtomicLong(0)
    private val okCount = AtomicLong(0)
    private val failCount = AtomicLong(0)
    private val activeCount = AtomicInteger(0)

    private val _stats = MutableStateFlow(BatStatsSnapshot())
    val stats: StateFlow<BatStatsSnapshot> = _stats

    init {
        publishStats()
    }

    suspend fun openTunnel(hostname: String, port: Int): Pair<WorkerTunnel, BatWorker> {
        if (healthMap.isEmpty()) throw IOException("no BatProxy workers configured")
        val destKey = "$hostname:$port"
        cleanupDestCache()
        val preferredUrl = destCache[destKey]?.takeIf { nowSeconds() < it.expiry }?.workerUrl
        val order = pickWorkersOrder(preferredUrl).take(MAX_ATTEMPTS_PER_REQUEST)

        var lastError: Throwable? = null
        for (health in order) {
            try {
                val t0 = nowSeconds()
                val tunnel = WorkerTunnel.connect(health.worker.url, CONNECT_TIMEOUT_MS)
                val token = BatAuth.makeToken(health.worker.password, destKey)
                val handshake = JSONObject().apply {
                    put("hostname", hostname)
                    put("port", port)
                    put("auth", token)
                }
                tunnel.sendText(handshake.toString())
                val frame = tunnel.receive(HANDSHAKE_TIMEOUT_MS)
                val text = (frame as? WorkerTunnel.Frame.Text)?.text
                    ?: throw IOException("worker closed before replying")
                val resp = JSONObject(text)
                if (resp.optString("status") != "connected") {
                    tunnel.close()
                    throw IOException(resp.optString("message", "handshake rejected"))
                }
                val rttMs = (nowSeconds() - t0) * 1000.0
                markSuccess(health, rttMs)
                destCache[destKey] = DestCacheEntry(health.worker.url, nowSeconds() + DEST_CACHE_TTL_SEC)
                totalCount.incrementAndGet(); okCount.incrementAndGet()
                health.active++
                activeCount.incrementAndGet()
                publishStats()
                return tunnel to health.worker
            } catch (e: Exception) {
                lastError = e
                markFailure(health)
                failCount.incrementAndGet()
                publishStats()
            }
        }
        throw IOException("all workers failed for $destKey: ${lastError?.message}")
    }

    fun releaseWorker(worker: BatWorker) {
        healthMap[worker.url]?.let { it.active = maxOf(0, it.active - 1) }
        activeCount.updateAndGet { maxOf(0, it - 1) }
        publishStats()
    }

    private fun pickWorkersOrder(preferredUrl: String?): List<WorkerHealth> {
        val all = healthMap.values.toList()
        val healthy = all.filter { it.score >= 0 }
            .sortedWith(
                compareByDescending<WorkerHealth> { if (it.worker.url == preferredUrl) 1 else 0 }
                    .thenByDescending { it.score }
            )
        if (healthy.isNotEmpty()) return healthy
        return all.sortedBy { it.cooldownUntil }
    }

    private fun markSuccess(h: WorkerHealth, rttMs: Double) {
        h.ok++
        h.consecFail = 0
        h.ewmaRtt = if (h.ewmaRtt == null) rttMs else h.ewmaRtt!! * (1 - WorkerHealth.ALPHA_RTT) + rttMs * WorkerHealth.ALPHA_RTT
        h.ewmaSuccess = h.ewmaSuccess * (1 - WorkerHealth.ALPHA_SUCCESS) + 100.0 * WorkerHealth.ALPHA_SUCCESS
        h.slowStreak = if (rttMs > WorkerHealth.SLOW_RTT_MS) h.slowStreak + 1 else 0
        h.state = "closed"
    }

    private fun markFailure(h: WorkerHealth) {
        h.fail++
        h.consecFail++
        h.ewmaSuccess = h.ewmaSuccess * (1 - WorkerHealth.ALPHA_SUCCESS)
        if (h.consecFail >= WorkerHealth.HALF_OPEN_AFTER_FAILS) {
            h.state = "open"
            val tripped = h.consecFail - WorkerHealth.HALF_OPEN_AFTER_FAILS
            val cooldown = min(WorkerHealth.COOLDOWN_MAX, WorkerHealth.COOLDOWN_BASE * 2.0.pow(tripped))
            h.cooldownUntil = nowSeconds() + cooldown
        } else {
            h.state = "half_open"
        }
    }

    private fun cleanupDestCache() {
        val now = nowSeconds()
        destCache.entries.removeIf { it.value.expiry < now }
    }

    fun startHealthCheckLoop(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                for (health in healthMap.values) {
                    if (health.state == "open" && nowSeconds() < health.cooldownUntil) continue
                    try {
                        val t0 = nowSeconds()
                        val tunnel = WorkerTunnel.connect(health.worker.url, CONNECT_TIMEOUT_MS)
                        val token = BatAuth.makeToken(health.worker.password, "ping")
                        tunnel.sendText(JSONObject().apply { put("cmd", "ping"); put("auth", token) }.toString())
                        val frame = tunnel.receive(HANDSHAKE_TIMEOUT_MS)
                        val text = (frame as? WorkerTunnel.Frame.Text)?.text
                        val ok = text != null && JSONObject(text).optString("status") == "ok"
                        tunnel.close()
                        if (ok) markSuccess(health, (nowSeconds() - t0) * 1000.0) else markFailure(health)
                    } catch (_: Exception) {
                        markFailure(health)
                    }
                    publishStats()
                }
            }
        }
    }

    private fun publishStats() {
        _stats.value = BatStatsSnapshot(
            active = activeCount.get(),
            total = totalCount.get(),
            ok = okCount.get(),
            fail = failCount.get(),
            workers = healthMap.values.map { h ->
                BatWorkerSnapshot(
                    url = h.worker.url,
                    shortUrl = shortenUrl(h.worker.url),
                    status = h.state,
                    cooldownSeconds = (h.cooldownUntil - nowSeconds()).coerceAtLeast(0.0),
                    active = h.active,
                    rttMs = h.ewmaRtt,
                    score = h.score,
                    ok = h.ok,
                    fail = h.fail
                )
            }
        )
    }

    companion object {
        const val MAX_ATTEMPTS_PER_REQUEST = 3
        const val CONNECT_TIMEOUT_MS = 6000L
        const val HANDSHAKE_TIMEOUT_MS = 8000L
        const val DEST_CACHE_TTL_SEC = 300.0
        const val HEALTH_CHECK_INTERVAL_MS = 30_000L
    }
}
