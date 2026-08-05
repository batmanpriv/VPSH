package com.batman.vpsh.core.batproxy

import com.batman.vpsh.data.BatWorker

class WorkerHealth(val worker: BatWorker) {
    @Volatile var ok: Long = 0
    @Volatile var fail: Long = 0
    @Volatile var consecFail: Int = 0
    @Volatile var ewmaRtt: Double? = null
    @Volatile var ewmaSuccess: Double = 80.0
    @Volatile var slowStreak: Int = 0
    @Volatile var active: Int = 0
    @Volatile var state: String = "closed" 
    @Volatile var cooldownUntil: Double = 0.0

    val score: Double
        get() {
            if (state == "open" && nowSeconds() < cooldownUntil) return -1.0
            val rttPenalty = (ewmaRtt ?: 200.0) / 10.0
            val slowPenalty = if (slowStreak >= 3) SLOW_PENALTY else 0.0
            return ewmaSuccess - rttPenalty - slowPenalty
        }

    companion object {
        const val ALPHA_SUCCESS = 0.35
        const val ALPHA_RTT = 0.35
        const val SLOW_RTT_MS = 600.0
        const val SLOW_PENALTY = 20.0
        const val HALF_OPEN_AFTER_FAILS = 3
        const val COOLDOWN_BASE = 5.0
        const val COOLDOWN_MAX = 120.0
    }
}

fun nowSeconds(): Double = System.currentTimeMillis() / 1000.0

data class BatWorkerSnapshot(
    val url: String,
    val shortUrl: String,
    val status: String, 
    val cooldownSeconds: Double,
    val active: Int,
    val rttMs: Double?,
    val score: Double,
    val ok: Long,
    val fail: Long
)

data class BatStatsSnapshot(
    val active: Int = 0,
    val total: Long = 0,
    val ok: Long = 0,
    val fail: Long = 0,
    val workers: List<BatWorkerSnapshot> = emptyList()
)

fun shortenUrl(url: String): String {
    val afterScheme = url.substringAfter("//", url)
    return afterScheme.take(32)
}
