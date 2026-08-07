package com.batman.vpsh.core

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings

enum class HotspotType { WIFI, USB, BLUETOOTH, UNKNOWN }

object UsbTetherUtils {
    private val USB_IFACE_PREFIXES = listOf("rndis", "usb", "ncm")
    private val WIFI_IFACE_PREFIXES = listOf("swlan", "softap", "ap0", "wlan")
    private val BT_IFACE_PREFIXES = listOf("bt-pan", "bnep", "pan")

    fun classify(ifaceName: String): HotspotType = when {
        USB_IFACE_PREFIXES.any { ifaceName.startsWith(it) } -> HotspotType.USB
        BT_IFACE_PREFIXES.any { ifaceName.startsWith(it) } -> HotspotType.BLUETOOTH
        WIFI_IFACE_PREFIXES.any { ifaceName.startsWith(it) } -> HotspotType.WIFI
        else -> HotspotType.UNKNOWN
    }

    fun isUsbCableConnected(context: Context): Boolean = try {
        val sticky = context.registerReceiver(null, IntentFilter("android.hardware.usb.action.USB_STATE"))
        sticky?.getBooleanExtra("connected", false) ?: false
    } catch (_: Exception) {
        false
    }

    fun tryEnableUsbTetherViaRoot(shell: RootShell): Boolean {
        val res = shell.run("cmd connectivity tethering start_tethering 1")
        if (res.exitCode == 0) return true
        val legacy = shell.run("svc usb setFunctions rndis")
        return legacy.exitCode == 0
    }

    fun disableUsbTetherViaRoot(shell: RootShell) {
        shell.run("cmd connectivity tethering stop_tethering 1")
        shell.run("svc usb setFunctions none")
    }

    fun openTetherSettings(context: Context) {
        val candidates = listOf(
            Intent("android.settings.TETHER_SETTINGS"),
            Intent(Settings.ACTION_WIRELESS_SETTINGS)
        )
        for (intent in candidates) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return
            } catch (_: Exception) {
                
            }
        }
    }
}
