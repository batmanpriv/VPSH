package com.batman.vpsh.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address
import java.net.NetworkInterface

data class IfaceInfo(val name: String, val ip: String, val prefix: Int)

object NetworkUtils {

    private val HOTSPOT_NAME_PRIORITY = listOf(
        "swlan0", "softap0", "ap0", "wlan1", "usb0", "rndis0", "ncm0"
    )
    
    private val VPN_NAME_PRIORITY = listOf("tun0", "tun1", "ppp0")

    fun listInterfaces(): List<IfaceInfo> {
        val result = mutableListOf<IfaceInfo>()
        try {
            val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            for (iface in ifaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.interfaceAddresses) {
                    val ip = addr.address
                    if (ip is Inet4Address && !ip.isLoopbackAddress) {
                        result.add(IfaceInfo(iface.name, ip.hostAddress ?: continue, addr.networkPrefixLength.toInt()))
                    }
                }
            }
        } catch (_: Exception) { }
        return result
    }

    fun findHotspotInterface(override: String?): IfaceInfo? {
        val ifaces = listInterfaces()
        if (!override.isNullOrBlank()) {
            return ifaces.firstOrNull { it.name == override }
        }
        for (name in HOTSPOT_NAME_PRIORITY) {
            ifaces.firstOrNull { it.name == name }?.let { return it }
        }
        
        return ifaces.firstOrNull {
            it.name.startsWith("wlan") || it.name.startsWith("ap") || it.name.startsWith("usb") || it.name.startsWith("rndis")
        }
    }

    fun findVpnInterface(context: Context, override: String?): IfaceInfo? {
        val ifaces = listInterfaces()
        if (!override.isNullOrBlank()) {
            return ifaces.firstOrNull { it.name == override }
        }

        findVpnInterfaceViaConnectivityManager(context, ifaces)?.let { return it }

        for (name in VPN_NAME_PRIORITY) {
            ifaces.firstOrNull { it.name == name }?.let { return it }
        }
        return ifaces.firstOrNull {
            it.name.startsWith("tun") || it.name.startsWith("ppp") ||
                it.name.startsWith("wg") || it.name.startsWith("ipsec")
        }
    }

    private fun findVpnInterfaceViaConnectivityManager(context: Context, ifaces: List<IfaceInfo>): IfaceInfo? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return null
        
        val networks = try {
            cm.allNetworks
        } catch (_: Exception) {
            listOfNotNull(cm.activeNetwork).toTypedArray()
        }
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue
            val linkProps = cm.getLinkProperties(network) ?: continue
            val ifaceName = linkProps.interfaceName ?: continue

            ifaces.firstOrNull { it.name == ifaceName }?.let { return it }
            val addr = linkProps.linkAddresses.firstOrNull { it.address is Inet4Address } ?: continue
            val hostAddress = addr.address.hostAddress ?: continue
            return IfaceInfo(ifaceName, hostAddress, addr.prefixLength)
        }
        return null
    }

    fun isVpnActive(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
}
