package com.batman.vpsh.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.batman.vpsh.core.IfaceDetail
import com.batman.vpsh.core.IptablesChain
import com.batman.vpsh.core.NetworkAdvancedManager
import com.batman.vpsh.core.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class NetworkViewModel(app: Application) : AndroidViewModel(app) {

    private val _isRooted = MutableStateFlow<Boolean?>(null)
    val isRooted: StateFlow<Boolean?> = _isRooted

    private val _interfaces = MutableStateFlow<List<IfaceDetail>>(emptyList())
    val interfaces: StateFlow<List<IfaceDetail>> = _interfaces

    private val _gateway = MutableStateFlow<String?>(null)
    val gateway: StateFlow<String?> = _gateway

    private val _dns = MutableStateFlow<List<String>>(emptyList())
    val dns: StateFlow<List<String>> = _dns

    private val _table = MutableStateFlow(NetworkAdvancedManager.IPTABLES_TABLES.first())
    val table: StateFlow<String> = _table

    private val _chains = MutableStateFlow<List<IptablesChain>>(emptyList())
    val chains: StateFlow<List<IptablesChain>> = _chains

    private val _ipRules = MutableStateFlow<List<String>>(emptyList())
    val ipRules: StateFlow<List<String>> = _ipRules

    private val _ipRoutes = MutableStateFlow<List<String>>(emptyList())
    val ipRoutes: StateFlow<List<String>> = _ipRoutes

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var rootShell: RootShell? = null
    private var manager: NetworkAdvancedManager? = null

    fun clearMessage() { _message.value = null }

    fun refreshAll() {
        viewModelScope.launch(Dispatchers.IO) {
            if (_isRooted.value == null) _isRooted.value = RootShell.quickCheckRoot()
            val ctx = getApplication<Application>()
            _gateway.value = NetworkAdvancedManager.getDefaultGateway(ctx)
            _dns.value = NetworkAdvancedManager.getDnsServers(ctx)

            val mgr = ensureManager()
            _interfaces.value = mgr?.listInterfacesRooted() ?: NetworkAdvancedManager.listInterfacesNonRoot()

            if (mgr != null) {
                _chains.value = mgr.listIptablesRules(_table.value)
                _ipRules.value = mgr.listIpRules()
                _ipRoutes.value = mgr.listIpRoutes()
            } else {
                
                _ipRules.value = NetworkAdvancedManager.tryNonRootShellCommand("ip rule show").output
                _ipRoutes.value = NetworkAdvancedManager.tryNonRootShellCommand("ip route show").output
                _chains.value = emptyList()
            }
        }
    }

    fun setTable(newTable: String) {
        _table.value = newTable
        viewModelScope.launch(Dispatchers.IO) {
            _chains.value = ensureManager()?.listIptablesRules(newTable) ?: emptyList()
        }
    }

    private fun ensureManager(): NetworkAdvancedManager? {
        if (_isRooted.value != true) return null
        manager?.let { return it }
        val shell = rootShell ?: RootShell().also { rootShell = it }
        return if (shell.open()) {
            NetworkAdvancedManager(shell).also { manager = it }
        } else {
            null
        }
    }

    fun appendRule(chain: String, ruleArgs: String) {
        if (chain.isBlank() || ruleArgs.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) {
                ensureManager()?.appendIptablesRule(_table.value, chain, ruleArgs)
            }
            reportResult(result)
            refreshIptablesOnly()
            _busy.value = false
        }
    }

    fun runRawIptables(argsAfterIptables: String) {
        if (argsAfterIptables.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) {
                ensureManager()?.runRawIptables(argsAfterIptables)
            }
            reportResult(result)
            refreshIptablesOnly()
            _busy.value = false
        }
    }

    fun deleteRule(chain: String, lineNumber: Int) {
        viewModelScope.launch {
            _busy.value = true
            withContext(Dispatchers.IO) { ensureManager()?.deleteIptablesRule(_table.value, chain, lineNumber) }
            refreshIptablesOnly()
            _busy.value = false
        }
    }

    fun runRawIp(argsAfterIp: String) {
        if (argsAfterIp.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            val result = withContext(Dispatchers.IO) { ensureManager()?.runRawIpCommand(argsAfterIp) }
            reportResult(result)
            withContext(Dispatchers.IO) {
                ensureManager()?.let {
                    _ipRules.value = it.listIpRules()
                    _ipRoutes.value = it.listIpRoutes()
                }
            }
            _busy.value = false
        }
    }

    private fun reportResult(result: com.batman.vpsh.core.ShellRunResult?) {
        _message.value = when {
            result == null -> null
            result.success -> result.output.joinToString("\n").ifBlank { "OK" }
            else -> result.output.joinToString("\n").ifBlank { "FAILED" }
        }
    }

    private suspend fun refreshIptablesOnly() = withContext(Dispatchers.IO) {
        _chains.value = ensureManager()?.listIptablesRules(_table.value) ?: emptyList()
    }

    override fun onCleared() {
        rootShell?.close()
        rootShell = null
        super.onCleared()
    }
}
