package com.batman.vpsh.viewmodel

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.batman.vpsh.data.BatProxyConfig
import com.batman.vpsh.data.BatProxyPreferences
import com.batman.vpsh.data.BatWorker
import com.batman.vpsh.service.BatProxyUiState
import com.batman.vpsh.service.BatVpnService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BatProxyViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = BatProxyPreferences(app)
    val config: StateFlow<BatProxyConfig> = prefs.configFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), BatProxyConfig()
    )

    private var service: BatVpnService? = null
    private var bound = false
    private val _uiState = MutableStateFlow(BatProxyUiState())
    val uiState: StateFlow<BatProxyUiState> = _uiState

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val local = (binder as? BatVpnService.LocalBinder)?.getService() ?: return
            service = local
            viewModelScope.launch { local.state.collect { _uiState.value = it } }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    fun bindIfRunning() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, BatVpnService::class.java)
        bound = try { ctx.bindService(intent, connection, 0) } catch (_: Exception) { false }
    }

    fun unbind() {
        if (bound) {
            try { getApplication<Application>().unbindService(connection) } catch (_: Exception) { }
            bound = false
        }
    }

    fun start() {
        val ctx = getApplication<Application>()
        val intent = Intent(ctx, BatVpnService::class.java)
        ctx.startForegroundService(intent)
        if (!bound) {
            bound = ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }
    }

    fun stop() {
        val ctx = getApplication<Application>()
        ctx.startService(Intent(ctx, BatVpnService::class.java).setAction(BatVpnService.ACTION_STOP))
    }

    fun addWorker(worker: BatWorker) {
        viewModelScope.launch { prefs.addWorker(worker) }
    }

    fun removeWorker(url: String) {
        viewModelScope.launch { prefs.removeWorker(url) }
    }

    fun setDns(host: String, port: Int) {
        viewModelScope.launch { prefs.setDns(host, port) }
    }

    fun setSplitTunnel(mode: com.batman.vpsh.data.SplitTunnelMode, apps: Set<String>) {
        viewModelScope.launch { prefs.setSplitTunnel(mode, apps) }
    }
}
