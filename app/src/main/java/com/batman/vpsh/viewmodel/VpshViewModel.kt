package com.batman.vpsh.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.batman.vpsh.data.ClientEntry
import com.batman.vpsh.data.ClientRegistry
import com.batman.vpsh.data.VpshPreferences
import com.batman.vpsh.data.VpshSettings
import com.batman.vpsh.data.VpshUiState
import com.batman.vpsh.service.VpshService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VpshViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = VpshPreferences(app)
    private val registry = ClientRegistry(app)
    val settings: StateFlow<VpshSettings> = prefs.settingsFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), VpshSettings()
    )
    val registryEntries: StateFlow<Map<String, ClientEntry>> = registry.entriesFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap()
    )

    private var service: VpshService? = null
    private val _uiState = MutableStateFlow(VpshUiState())
    val uiState: StateFlow<VpshUiState> = _uiState

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = (binder as? VpshService.LocalBinder)?.getService() ?: return
            service = local
            viewModelScope.launch {
                local.state.collect { _uiState.value = it }
            }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun bind() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, VpshService::class.java)
        ctx.startForegroundService(intent)
        ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    fun unbind() {
        try { getApplication<Application>().unbindService(connection) } catch (_: Exception) { }
    }

    fun start() {
        viewModelScope.launch {
            service?.begin(prefs.current())
        }
    }

    fun stop() {
        service?.end()
    }

    fun runHealthCheck() {
        service?.runHealthCheckNow()
    }

    fun requestEnableUsbTether(onResult: (Boolean) -> Unit) {
        val svc = service
        if (svc == null) onResult(false) else svc.requestEnableUsbTether(onResult)
    }

    fun updateSettings(transform: (VpshSettings) -> VpshSettings) {
        viewModelScope.launch { prefs.update(transform) }
    }

    fun toggleBlock(clientKey: String) {
        viewModelScope.launch {
            val blocked = registryEntries.value[clientKey]?.blocked ?: false
            registry.setBlocked(clientKey, !blocked)
        }
    }

    fun setNickname(clientKey: String, nickname: String) {
        viewModelScope.launch { registry.setNickname(clientKey, nickname) }
    }

    fun setLimit(clientKey: String, kbps: Int) {
        viewModelScope.launch { registry.setLimit(clientKey, kbps) }
    }
}
