package com.batman.vpsh.core

import com.batman.vpsh.core.batproxy.VpnRegionDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class PublicIpInfo(
    val ip: String,
    val countryCode: String,
    val countryName: String,
    val flagEmoji: String
)


object PublicIpDetector {

    private const val URL_STR = "https://ipwho.is/"
    private const val TIMEOUT_MS = 5000

    suspend fun detect(): PublicIpInfo? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(URL_STR).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "vpsh-android")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (!json.optBoolean("success", true)) return@withContext null

            val ip = json.optString("ip")
            val code = json.optString("country_code")
            if (ip.isBlank() || code.isBlank()) return@withContext null

            PublicIpInfo(
                ip = ip,
                countryCode = code,
                countryName = json.optString("country", code),
                flagEmoji = VpnRegionDetector.flagFor(code)
            )
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
