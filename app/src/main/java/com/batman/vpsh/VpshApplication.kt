package com.batman.vpsh

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.batman.vpsh.util.LocaleHelper

class VpshApplication : Application() {
    companion object {
        const val CHANNEL_ID = "vpsh_service_channel"
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val vpshChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            val batProxyChannel = NotificationChannel(
                com.batman.vpsh.service.BatVpnService.CHANNEL_ID,
                getString(R.string.batproxy_notif_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.batproxy_notif_channel_desc)
            }
            manager?.createNotificationChannel(vpshChannel)
            manager?.createNotificationChannel(batProxyChannel)
        }
    }
}
