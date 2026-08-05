package com.batman.vpsh.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.batman.vpsh.core.ClientInfo
import com.batman.vpsh.core.HotspotFirewall
import com.batman.vpsh.core.HotspotType
import com.batman.vpsh.core.NetworkUtils
import com.batman.vpsh.core.RootShell
import com.batman.vpsh.core.UsbTetherUtils
import com.batman.vpsh.data.HotspotBanEntry
import com.batman.vpsh.data.HotspotBanRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HotspotViewModel(app: Application) : AndroidViewModel(app) {

    private val registry = HotspotBanRegistry(app)
    val bannedEntries: StateFlow<Map<String, HotspotBanEntry>> = registry.entriesFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap()
    )

    private val _isRooted = MutableStateFlow<Boolean?>(null)
    val isRooted: StateFlow<Boolean?> = _isRooted

    private val _hotspotIface = MutableStateFlow<String?>(null)
    val hotspotIface: StateFlow<String?> = _hotspotIface

    private val _hotspotIp = MutableStateFlow("")
    val hotspotIp: StateFlow<String> = _hotspotIp

    private val _hotspotType = MutableStateFlow(HotspotType.UNKNOWN)
    val hotspotType: StateFlow<HotspotType> = _hotspotType

    private val _devices = MutableStateFlow<List<ClientInfo>>(emptyList())
    val devices: StateFlow<List<ClientInfo>> = _devices

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private var rootShell: RootShell? = null
    private var pollJob: Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            _isRooted.value = RootShell.quickCheckRoot()
        }
    }

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = viewModelScope.launch {
            while (isActive) {
                refreshOnce()
                delay(5000)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    private suspend fun refreshOnce() = withContext(Dispatchers.IO) {
        val iface = NetworkUtils.findHotspotInterface(null)
        _hotspotIface.value = iface?.name
        _hotspotIp.value = iface?.ip ?: ""
        _hotspotType.value = if (iface != null) UsbTetherUtils.classify(iface.name) else HotspotType.UNKNOWN

        if (iface == null) {
            _devices.value = emptyList()
            return@withContext
        }

        val shell = ensureShell()
        if (shell == null) {
            _devices.value = emptyList()
            return@withContext
        }

        val res = shell.run("ip neigh show dev ${iface.name}")
        val list = mutableListOf<ClientInfo>()
        for (line in res.output) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.isEmpty() || parts[0].isBlank()) continue
            val ip = parts[0]
            val macIdx = parts.indexOf("lladdr")
            val mac = if (macIdx in 0 until parts.size - 1) parts.getOrNull(macIdx + 1) ?: "" else ""
            if (mac.isEmpty()) continue
            list.add(ClientInfo(ip = ip, mac = mac))
        }
        _devices.value = list

        val bans = bannedEntries.value.keys
        if (bans.isNotEmpty()) {
            HotspotFirewall(shell).reapplyAll(iface.name, bans)
        }
    }

    private fun ensureShell(): RootShell? {
        rootShell?.let { return it }
        val shell = RootShell()
        return if (shell.open()) {
            rootShell = shell
            shell
        } else {
            shell.close()
            null
        }
    }

    fun ban(mac: String, nickname: String = "", lastIp: String = "") {
        if (mac.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            withContext(Dispatchers.IO) {
                val shell = ensureShell()
                val iface = _hotspotIface.value
                if (shell != null && iface != null) {
                    HotspotFirewall(shell).banMac(iface, mac)
                }
            }
            registry.ban(mac, nickname, lastIp)
            refreshOnce()
            _busy.value = false
        }
    }

    fun unban(mac: String) {
        viewModelScope.launch {
            _busy.value = true
            withContext(Dispatchers.IO) {
                ensureShell()?.let { HotspotFirewall(it).unbanMac(mac) }
            }
            registry.unban(mac)
            refreshOnce()
            _busy.value = false
        }
    }

    override fun onCleared() {
        rootShell?.close()
        rootShell = null
        super.onCleared()
    }
}
