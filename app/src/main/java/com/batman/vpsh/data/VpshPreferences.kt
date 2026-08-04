package com.batman.vpsh.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

val Context.dataStore by preferencesDataStore(name = "vpsh_settings")

enum class VpshMode { PROXY, FULL }
enum class UpstreamType { NONE, SOCKS5, HTTP }

data class VpshSettings(
    val mode: VpshMode = VpshMode.PROXY,
    val proxyPort: Int = 8888,
    val socks5Port: Int = 1080,
    val enableSocks5: Boolean = false,
    val enableAuth: Boolean = false,
    val authUser: String = "",
    val authPass: String = "",
    val forceVpnOnly: Boolean = false,
    val autoRestart: Boolean = true,
    val healthIntervalSec: Int = 25,
    val hotspotIfaceOverride: String = "",
    val vpnIfaceOverride: String = "",
    val blockIpv6Leak: Boolean = true,
    val killSwitchProxyMode: Boolean = false,
    
    val upstreamType: UpstreamType = UpstreamType.NONE,
    val upstreamHost: String = "127.0.0.1",
    val upstreamPort: Int = 1080,
    val upstreamUser: String = "",
    val upstreamPass: String = ""
)

class VpshPreferences(private val context: Context) {

    private object Keys {
        val MODE = stringPreferencesKey("mode")
        val PROXY_PORT = intPreferencesKey("proxy_port")
        val SOCKS5_PORT = intPreferencesKey("socks5_port")
        val ENABLE_SOCKS5 = booleanPreferencesKey("enable_socks5")
        val ENABLE_AUTH = booleanPreferencesKey("enable_auth")
        val AUTH_USER = stringPreferencesKey("auth_user")
        val AUTH_PASS = stringPreferencesKey("auth_pass")
        val FORCE_VPN_ONLY = booleanPreferencesKey("force_vpn_only")
        val AUTO_RESTART = booleanPreferencesKey("auto_restart")
        val HEALTH_INTERVAL = intPreferencesKey("health_interval")
        val HOTSPOT_OVERRIDE = stringPreferencesKey("hotspot_override")
        val VPN_OVERRIDE = stringPreferencesKey("vpn_override")
        val BLOCK_IPV6 = booleanPreferencesKey("block_ipv6")
        val KILL_SWITCH_PROXY = booleanPreferencesKey("kill_switch_proxy")
        val UPSTREAM_TYPE = stringPreferencesKey("upstream_type")
        val UPSTREAM_HOST = stringPreferencesKey("upstream_host")
        val UPSTREAM_PORT = intPreferencesKey("upstream_port")
        val UPSTREAM_USER = stringPreferencesKey("upstream_user")
        val UPSTREAM_PASS = stringPreferencesKey("upstream_pass")
    }

    val settingsFlow: Flow<VpshSettings> = context.dataStore.data.map { p ->
        VpshSettings(
            mode = if (p[Keys.MODE] == "FULL") VpshMode.FULL else VpshMode.PROXY,
            proxyPort = p[Keys.PROXY_PORT] ?: 8888,
            socks5Port = p[Keys.SOCKS5_PORT] ?: 1080,
            enableSocks5 = p[Keys.ENABLE_SOCKS5] ?: false,
            enableAuth = p[Keys.ENABLE_AUTH] ?: false,
            authUser = p[Keys.AUTH_USER] ?: "",
            authPass = p[Keys.AUTH_PASS] ?: "",
            forceVpnOnly = p[Keys.FORCE_VPN_ONLY] ?: false,
            autoRestart = p[Keys.AUTO_RESTART] ?: true,
            healthIntervalSec = p[Keys.HEALTH_INTERVAL] ?: 25,
            hotspotIfaceOverride = p[Keys.HOTSPOT_OVERRIDE] ?: "",
            vpnIfaceOverride = p[Keys.VPN_OVERRIDE] ?: "",
            blockIpv6Leak = p[Keys.BLOCK_IPV6] ?: true,
            killSwitchProxyMode = p[Keys.KILL_SWITCH_PROXY] ?: false,
            upstreamType = when (p[Keys.UPSTREAM_TYPE]) {
                "SOCKS5" -> UpstreamType.SOCKS5
                "HTTP" -> UpstreamType.HTTP
                else -> UpstreamType.NONE
            },
            upstreamHost = p[Keys.UPSTREAM_HOST] ?: "127.0.0.1",
            upstreamPort = p[Keys.UPSTREAM_PORT] ?: 1080,
            upstreamUser = p[Keys.UPSTREAM_USER] ?: "",
            upstreamPass = p[Keys.UPSTREAM_PASS] ?: ""
        )
    }

    suspend fun current(): VpshSettings = settingsFlow.first()

    suspend fun update(transform: (VpshSettings) -> VpshSettings) {
        val next = transform(current())
        context.dataStore.edit { p ->
            p[Keys.MODE] = next.mode.name
            p[Keys.PROXY_PORT] = next.proxyPort
            p[Keys.SOCKS5_PORT] = next.socks5Port
            p[Keys.ENABLE_SOCKS5] = next.enableSocks5
            p[Keys.ENABLE_AUTH] = next.enableAuth
            p[Keys.AUTH_USER] = next.authUser
            p[Keys.AUTH_PASS] = next.authPass
            p[Keys.FORCE_VPN_ONLY] = next.forceVpnOnly
            p[Keys.AUTO_RESTART] = next.autoRestart
            p[Keys.HEALTH_INTERVAL] = next.healthIntervalSec
            p[Keys.HOTSPOT_OVERRIDE] = next.hotspotIfaceOverride
            p[Keys.VPN_OVERRIDE] = next.vpnIfaceOverride
            p[Keys.BLOCK_IPV6] = next.blockIpv6Leak
            p[Keys.KILL_SWITCH_PROXY] = next.killSwitchProxyMode
            p[Keys.UPSTREAM_TYPE] = next.upstreamType.name
            p[Keys.UPSTREAM_HOST] = next.upstreamHost
            p[Keys.UPSTREAM_PORT] = next.upstreamPort
            p[Keys.UPSTREAM_USER] = next.upstreamUser
            p[Keys.UPSTREAM_PASS] = next.upstreamPass
        }
    }
}
