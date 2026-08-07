package com.batman.vpsh.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.batman.vpsh.data.AppVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

sealed class UpdateCheckResult {
    data class UpToDate(val currentVersion: String) : UpdateCheckResult()
    data class Available(val remoteVersion: String, val releasePageUrl: String) : UpdateCheckResult()
    data class Failed(val reason: String) : UpdateCheckResult()
}

object UpdateManager {

    private const val TIMEOUT_MS = 10_000

    suspend fun checkForUpdate(): UpdateCheckResult = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(AppVersion.VERSION_CHECK_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", "vpsh-android")
            }
            if (conn.responseCode !in 200..299) {
                return@withContext UpdateCheckResult.Failed("HTTP ${conn.responseCode}")
            }
            val remoteVersion = conn.inputStream.bufferedReader().use { it.readText() }.trim()
            if (remoteVersion.isBlank()) {
                return@withContext UpdateCheckResult.Failed("empty version.txt")
            }
            if (AppVersion.isNewer(remoteVersion)) {
                UpdateCheckResult.Available(remoteVersion, AppVersion.releasePageUrl(remoteVersion))
            } else {
                UpdateCheckResult.UpToDate(AppVersion.CURRENT)
            }
        } catch (e: Exception) {
            UpdateCheckResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    fun openReleasePage(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
