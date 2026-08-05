package com.batman.vpsh.core.batproxy

import org.json.JSONObject
import java.nio.charset.StandardCharsets

data class VpnRegionInfo(
    val countryCode: String,
    val countryName: String,
    val flagEmoji: String
)

object VpnRegionDetector {

    private const val HOST = "ip-api.com"
    private const val PORT = 80
    private const val PATH = "/json/?fields=status,country,countryCode"

    suspend fun detect(manager: BatTunnelManager): VpnRegionInfo? {
        var tunnel: WorkerTunnel? = null
        return try {
            val (t, worker) = manager.openTunnel(HOST, PORT)
            tunnel = t
            val request = "GET $PATH HTTP/1.1\r\nHost: $HOST\r\nConnection: close\r\nUser-Agent: vpsh-android\r\n\r\n"
            t.sendBytes(request.toByteArray(StandardCharsets.US_ASCII))

            val raw = StringBuilder()
            while (raw.length < 8192) {
                val frame = try { t.receive(8000) } catch (_: Exception) { break }
                when (frame) {
                    is WorkerTunnel.Frame.Binary -> raw.append(String(frame.bytes, StandardCharsets.UTF_8))
                    is WorkerTunnel.Frame.Text -> raw.append(frame.text)
                    WorkerTunnel.Frame.Closed -> break
                }
            }
            manager.releaseWorker(worker)

            val bodyStart = raw.indexOf("\r\n\r\n")
            val body = if (bodyStart >= 0) raw.substring(bodyStart + 4) else raw.toString()
            val json = JSONObject(body.trim())
            if (json.optString("status") != "success") return null
            val code = json.optString("countryCode")
            if (code.isBlank()) return null
            VpnRegionInfo(
                countryCode = code,
                countryName = json.optString("country", code),
                flagEmoji = flagFor(code)
            )
        } catch (_: Exception) {
            null
        } finally {
            tunnel?.close()
        }
    }

    fun flagFor(countryCode: String): String {
        if (countryCode.length != 2) return "\uD83C\uDFF3"
        val base = 0x1F1E6
        return countryCode.uppercase().map { base + (it - 'A') }
            .joinToString("") { String(Character.toChars(it)) }
    }
}
