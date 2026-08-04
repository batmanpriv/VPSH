package com.batman.vpsh.core

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HealthMonitor(
    private val scope: CoroutineScope,
    private val intervalSeconds: Int,
    private val maxAttempts: Int,
    private val autoRestart: Boolean,
    private val onLog: (String) -> Unit,
    private val checkHealthy: suspend () -> Boolean,
    private val onRestart: suspend () -> Unit,
    private val onGiveUp: suspend () -> Unit
) {
    private var job: Job? = null
    private var attempts = 0
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun start() {
        stop()
        attempts = 0
        job = scope.launch {
            while (isActive) {
                delay(intervalSeconds * 1000L)
                val healthy = try { checkHealthy() } catch (_: Exception) { false }
                if (healthy) {
                    attempts = 0
                    continue
                }
                log("health check failed")
                if (!autoRestart) continue
                if (attempts >= maxAttempts) {
                    log("max restart attempts ($maxAttempts) reached — giving up")
                    onGiveUp()
                    break
                }
                attempts++
                log("attempting restart ($attempts/$maxAttempts)")
                try { onRestart() } catch (e: Exception) { log("restart threw: ${e.message}") }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun log(msg: String) {
        onLog("[${fmt.format(Date())}] $msg")
    }
}
