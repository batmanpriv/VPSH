package com.batman.vpsh.data

import com.batman.vpsh.core.ClientInfo
import com.batman.vpsh.core.HotspotType

enum class RunState { STOPPED, STARTING, RUNNING, PAUSED, ERROR }

data class VpshUiState(
    val runState: RunState = RunState.STOPPED,
    val mode: VpshMode = VpshMode.PROXY,
    val isRooted: Boolean = false,
    val hotspotIface: String = "",
    val hotspotIp: String = "",
    val hotspotType: HotspotType = HotspotType.UNKNOWN,
    val apSelfManaged: Boolean = false,
    val vpnIface: String = "",
    val vpnActive: Boolean = false,
    val proxyPort: Int = 0,
    val socks5Port: Int = 0,
    val socks5Enabled: Boolean = false,
    val shadowsocksEnabled: Boolean = false,
    val clients: List<ClientInfo> = emptyList(),
    val totalBytes: Long = 0L,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val logs: List<String> = emptyList(),
    val pacUrl: String = "",
    val guests: List<com.batman.vpsh.core.GuestAccess> = emptyList()
)
