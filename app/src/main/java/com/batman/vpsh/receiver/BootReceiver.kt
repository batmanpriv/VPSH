package com.batman.vpsh.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.batman.vpsh.data.VpshPreferences
import com.batman.vpsh.service.VpshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val appContext = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = VpshPreferences(appContext)
                val settings = prefs.current()
                val wasRunning = prefs.wasRunning()
                if (settings.autoStartOnBoot && wasRunning) {
                    val svcIntent = Intent(appContext, VpshService::class.java)
                        .setAction(VpshService.ACTION_AUTOSTART)
                    ContextCompat.startForegroundService(appContext, svcIntent)
                }
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
