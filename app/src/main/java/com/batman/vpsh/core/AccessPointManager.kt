package com.batman.vpsh.core

import kotlinx.coroutines.delay

object AccessPointManager {

    private const val MIN_SDK = android.os.Build.VERSION_CODES.R 

    data class ApResult(val success: Boolean, val message: String)

    fun isSupported(): Boolean = android.os.Build.VERSION.SDK_INT >= MIN_SDK

    fun start(shell: RootShell, ssid: String, password: String?): ApResult {
        if (!isSupported()) {
            return ApResult(false, "Android 11+ required")
        }
        val cleanSsid = ssid.trim().ifBlank { "VPSH" }.replace("\"", "")
        val pass = password?.trim().orEmpty()
        if (pass.isNotEmpty() && pass.length !in 8..63) {
            return ApResult(false, "password must be 8-63 characters")
        }

        shell.run("cmd wifi stop-softap 2>/dev/null")

        val cmd = if (pass.isEmpty()) {
            "cmd wifi start-softap \"$cleanSsid\" open"
        } else {
            "cmd wifi start-softap \"$cleanSsid\" wpa2 \"$pass\""
        }
        val res = shell.run(cmd)
        val failed = res.exitCode != 0 || res.output.any { it.contains("error", ignoreCase = true) }
        return if (!failed) ApResult(true, "started") else ApResult(false, res.output.joinToString(" ").ifBlank { "unknown error" })
    }

    fun stop(shell: RootShell) {
        shell.run("cmd wifi stop-softap 2>/dev/null")
    }

    suspend fun waitForInterface(timeoutMs: Long = 8000, pollMs: Long = 500): IfaceInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            NetworkUtils.findHotspotInterface(null)?.let { return it }
            delay(pollMs)
        }
        return null
    }
}
