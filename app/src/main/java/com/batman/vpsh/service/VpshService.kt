package com.batman.vpsh.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.batman.vpsh.MainActivity
import com.batman.vpsh.R
import com.batman.vpsh.VpshApplication
import com.batman.vpsh.core.ClientTracker
import com.batman.vpsh.core.HealthMonitor
import com.batman.vpsh.core.HttpProxyServer
import com.batman.vpsh.core.IptablesManager
import com.batman.vpsh.core.GuestAccessManager
import com.batman.vpsh.core.NetworkUtils
import com.batman.vpsh.core.PacServer
import com.batman.vpsh.core.RootShell
import com.batman.vpsh.core.Socks5ProxyServer
import com.batman.vpsh.data.ClientRegistry
import com.batman.vpsh.data.RunState
import com.batman.vpsh.data.VpshMode
import com.batman.vpsh.data.VpshPreferences
import com.batman.vpsh.data.VpshSettings
import com.batman.vpsh.data.VpshUiState
import com.batman.vpsh.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class VpshService : Service() {

    inner class LocalBinder : Binder() {
        fun getService(): VpshService = this@VpshService
    }

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(VpshUiState())
    val state: StateFlow<VpshUiState> = _state

    private var rootShell: RootShell? = null
    private var iptables: IptablesManager? = null
    private var httpProxy: HttpProxyServer? = null
    private var socks5Proxy: Socks5ProxyServer? = null
    private var pacServer: PacServer? = null
    private var guestManager: GuestAccessManager? = null
    private var clientTracker: ClientTracker? = null
    private var healthMonitor: HealthMonitor? = null
    private var killSwitchJob: Job? = null
    private var currentSettings: VpshSettings? = null
    private val logFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var prefs: VpshPreferences
    private lateinit var registry: ClientRegistry
    @Volatile private var blockedKeys: Set<String> = emptySet()
    @Volatile private var clientLimits: Map<String, Int> = emptyMap()

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        prefs = VpshPreferences(this)
        registry = ClientRegistry(this)
        _state.value = _state.value.copy(isRooted = RootShell.quickCheckRoot())

        scope.launch { state.collect { VpshBridge.runState.value = it.runState } }
        
        scope.launch {
            registry.entriesFlow.collect { m ->
                blockedKeys = m.values.filter { it.blocked }.map { it.key }.toSet()
                clientLimits = m.values.filter { it.limitKbps > 0 }.associate { it.key to it.limitKbps }
                reconcileFullModeLimits()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DISMISS -> {
                
                if (!isActiveState(_state.value.runState)) {
                    stopForegroundCompat()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
            ACTION_STOP -> end()
            ACTION_TOGGLE -> scope.launch {
                if (isActiveState(_state.value.runState)) { prefs.setWasRunning(false); doStop() } else begin(prefs.current())
            }
            ACTION_AUTOSTART -> scope.launch {
                
                if (!isActiveState(_state.value.runState)) begin(prefs.current())
            }
        }
        startForeground(NOTIF_ID, buildNotification(getString(R.string.notif_preparing), _state.value.runState))
        return START_STICKY
    }

    fun begin(settings: VpshSettings) {
        currentSettings = settings
        _state.value = _state.value.copy(runState = RunState.STARTING, mode = settings.mode, errorMessage = null)
        val modeWord = getString(if (settings.mode == VpshMode.FULL) R.string.mode_word_full else R.string.mode_word_proxy)
        appendLog(getString(R.string.log_start_mode, modeWord))
        updateNotification(getString(R.string.notif_preparing))
        scope.launch { prefs.setWasRunning(true) }
        scope.launch { doStart(settings) }
    }

    fun end() {
        scope.launch { prefs.setWasRunning(false) }
        scope.launch { doStop() }
    }

    fun createGuestLink(ttlMinutes: Int, quotaMb: Int) {
        val gm = guestManager ?: return
        val g = gm.create(ttlMinutes, quotaMb)
        pushGuests(gm)
        appendLog(getString(R.string.log_guest_created, g.user))
    }

    fun revokeGuestLink(user: String) {
        val gm = guestManager ?: return
        gm.revoke(user)
        pushGuests(gm)
    }

    private fun pushGuests(gm: GuestAccessManager) {
        _state.value = _state.value.copy(guests = gm.activeGuests())
    }

    fun runHealthCheckNow() {
        scope.launch {
            val ok = checkHealthy()
            appendLog(getString(if (ok) R.string.log_health_ok else R.string.log_health_fail))
        }
    }

    fun requestEnableUsbTether(onResult: (Boolean) -> Unit) {
        scope.launch {
            val existing = rootShell
            val shell = existing ?: RootShell()
            val opened = existing != null || shell.open()
            val ok = opened && com.batman.vpsh.core.UsbTetherUtils.tryEnableUsbTetherViaRoot(shell)
            if (existing == null) shell.close()
            appendLog(getString(if (ok) R.string.log_usb_tether_enabled else R.string.log_usb_tether_failed))
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    private suspend fun doStart(settings: VpshSettings) {
        val hotspot = NetworkUtils.findHotspotInterface(settings.hotspotIfaceOverride.ifBlank { null })
        if (hotspot == null) {
            fail(getString(R.string.err_no_hotspot))
            return
        }

        _state.value = _state.value.copy(
            hotspotIface = hotspot.name,
            hotspotIp = hotspot.ip,
            hotspotType = com.batman.vpsh.core.UsbTetherUtils.classify(hotspot.name)
        )
        clientTracker = ClientTracker { rootShell }

        if (settings.mode == VpshMode.FULL) {
            startFullMode(settings, hotspot.name)
        } else {
            startProxyMode(settings)
            if (settings.killSwitchProxyMode) startKillSwitchWatcher(settings)
        }
    }

    private suspend fun startFullMode(settings: VpshSettings, hotspotIface: String) {
        val shell = RootShell()
        val opened = shell.open()
        if (!opened) {
            fail(getString(R.string.err_no_root))
            return
        }
        rootShell = shell
        val ipt = IptablesManager(shell)
        iptables = ipt

        val vpn = NetworkUtils.findVpnInterface(this@VpshService, settings.vpnIfaceOverride.ifBlank { null })
        if (vpn == null) {
            fail(getString(R.string.err_no_vpn))
            return
        }
        _state.value = _state.value.copy(vpnIface = vpn.name, vpnActive = true)

        val ok = ipt.setupNat(vpn.name, hotspotIface, settings.blockIpv6Leak)
        if (!ok) {
            fail(getString(R.string.err_nat_failed))
            return
        }
        val routed = ipt.setupRoutingRule(vpn.name, hotspotIface)
        if (!routed) {
            fail(getString(R.string.err_routing_failed))
            return
        }

        if (settings.gameModeEnabled) {
            val gm = ipt.enableGameMode(hotspotIface)
            appendLog(getString(if (gm) R.string.log_game_mode_on else R.string.log_game_mode_failed))
        }

        _state.value = _state.value.copy(
            runState = RunState.RUNNING,
            statusMessage = getString(R.string.status_full_running)
        )
        appendLog(getString(R.string.log_nat_established, vpn.name, hotspotIface))
        updateNotification(getString(R.string.notif_full_running, vpn.name, hotspotIface))
        reconcileFullModeLimits()
        startHealthMonitor(settings)
    }

    private fun reconcileFullModeLimits() {
        val ipt = iptables ?: return
        if (_state.value.mode != VpshMode.FULL || _state.value.runState != RunState.RUNNING) return
        val hotspotIface = _state.value.hotspotIface
        if (hotspotIface.isEmpty()) return
        val tracker = clientTracker ?: return
        for (client in tracker.snapshot()) {
            val kbps = clientLimits[client.ip] ?: 0
            if (kbps > 0) ipt.applyClientLimit(hotspotIface, client.ip, kbps)
            else ipt.clearClientLimit(hotspotIface, client.ip)
        }
    }

    private suspend fun startProxyMode(settings: VpshSettings) {
        val tracker = clientTracker ?: ClientTracker { null }.also { clientTracker = it }
        val authUser = settings.authUser.ifBlank { null }.takeIf { settings.enableAuth }
        val authPass = settings.authPass.ifBlank { null }.takeIf { settings.enableAuth }
        val guests = guestManager ?: GuestAccessManager { ip -> tracker.bytesFor(ip) }.also { guestManager = it }
        val authChecker: (String, String, String) -> Boolean = { user, pass, ip ->
            (authUser != null && user == authUser && pass == authPass) || guests.checkAuth(user, pass, ip)
        }
        val isBlocked: (String) -> Boolean = { ip -> blockedKeys.contains(ip) || guests.isIpRevoked(ip) }

        val http = HttpProxyServer(
            port = settings.proxyPort,
            enableAuth = settings.enableAuth,
            authChecker = authChecker,
            settings = settings,
            onClientSeen = { ip -> tracker.markSeen(ip); pushClients() },
            onBytes = { ip, n -> tracker.addBytes(ip, n); pushBytes() },
            isBlocked = isBlocked,
            limitKbpsFor = { ip -> clientLimits[ip] ?: 0 }
        )
        val started = withContext(Dispatchers.IO) { http.start() }
        if (!started) {
            fail(getString(R.string.err_http_port_busy, settings.proxyPort))
            return
        }
        httpProxy = http
        appendLog(getString(R.string.log_http_started, settings.proxyPort))

        if (settings.enableSocks5) {
            val socks = Socks5ProxyServer(
                port = settings.socks5Port,
                enableAuth = settings.enableAuth,
                authChecker = authChecker,
                settings = settings,
                onClientSeen = { ip -> tracker.markSeen(ip); pushClients() },
                onBytes = { ip, n -> tracker.addBytes(ip, n); pushBytes() },
                isBlocked = isBlocked,
                limitKbpsFor = { ip -> clientLimits[ip] ?: 0 }
            )
            val socksStarted = withContext(Dispatchers.IO) { socks.start() }
            if (socksStarted) {
                socks5Proxy = socks
                appendLog(getString(R.string.log_socks5_started, settings.socks5Port))
            } else {
                appendLog(getString(R.string.log_socks5_failed))
            }
        }

        val chainNote = if (settings.upstreamType != com.batman.vpsh.data.UpstreamType.NONE) {
            getString(R.string.status_proxy_chain_note, settings.upstreamType.toString(), settings.upstreamHost, settings.upstreamPort)
        } else ""

        
        
        if (settings.enablePac) {
            val pac = PacServer(
                port = settings.pacPort,
                proxyHost = { _state.value.hotspotIp },
                socks5Port = settings.socks5Port.takeIf { socks5Proxy != null },
                httpPort = settings.proxyPort
            )
            val pacStarted = withContext(Dispatchers.IO) { pac.start() }
            if (pacStarted) {
                pacServer = pac
                _state.value = _state.value.copy(pacUrl = pac.pacUrl())
                appendLog(getString(R.string.log_pac_started, pac.pacUrl()))
            } else {
                appendLog(getString(R.string.log_pac_failed))
            }
        }

        _state.value = _state.value.copy(
            runState = RunState.RUNNING,
            proxyPort = settings.proxyPort,
            socks5Port = settings.socks5Port,
            socks5Enabled = socks5Proxy != null,
            statusMessage = getString(R.string.status_proxy_running, _state.value.hotspotIp, settings.proxyPort, chainNote)
        )
        updateNotification(getString(R.string.notif_proxy_running, settings.proxyPort))
        startHealthMonitor(settings)
    }

    private fun startKillSwitchWatcher(settings: VpshSettings) {
        killSwitchJob?.cancel()
        killSwitchJob = scope.launch {
            var wasActive = NetworkUtils.isVpnActive(this@VpshService)
            while (isActive) {
                delay(4000)
                val active = NetworkUtils.isVpnActive(this@VpshService)
                if (!active && wasActive && _state.value.runState == RunState.RUNNING) {
                    appendLog(getString(R.string.log_killswitch_paused))
                    httpProxy?.stop(); httpProxy = null
                    socks5Proxy?.stop(); socks5Proxy = null
                    pacServer?.stop(); pacServer = null
                    _state.value = _state.value.copy(runState = RunState.PAUSED, statusMessage = getString(R.string.status_paused_vpn_down), pacUrl = "")
                    updateNotification(getString(R.string.notif_paused_vpn_down))
                } else if (active && !wasActive && _state.value.runState == RunState.PAUSED) {
                    appendLog(getString(R.string.log_killswitch_resumed))
                    currentSettings?.let { startProxyMode(it) }
                }
                wasActive = active
            }
        }
    }

    private fun startHealthMonitor(settings: VpshSettings) {
        healthMonitor = HealthMonitor(
            scope = scope,
            intervalSeconds = settings.healthIntervalSec,
            maxAttempts = 5,
            autoRestart = settings.autoRestart,
            onLog = { appendLog(it) },
            checkHealthy = { checkHealthy() },
            onRestart = {
                doStop(keepSettings = true)
                currentSettings?.let { doStart(it) }
            },
            onGiveUp = {
                fail(getString(R.string.err_gave_up))
            }
        ).also { it.start() }
    }

    private suspend fun checkHealthy(): Boolean = withContext(Dispatchers.IO) {
        when (_state.value.runState) {
            RunState.PAUSED -> true 
            else -> when (_state.value.mode) {
                VpshMode.FULL -> {
                    val ipt = iptables
                    val vpnIface = _state.value.vpnIface
                    ipt != null && vpnIface.isNotEmpty() && ipt.isNatIntact() && ipt.isRoutingIntact(vpnIface)
                }
                VpshMode.PROXY -> httpProxy != null
            }
        }
    }

    private suspend fun doStop(keepSettings: Boolean = false) {
        killSwitchJob?.cancel(); killSwitchJob = null
        healthMonitor?.stop(); healthMonitor = null
        httpProxy?.stop(); httpProxy = null
        socks5Proxy?.stop(); socks5Proxy = null
        pacServer?.stop(); pacServer = null
        guestManager?.clearAll(); guestManager = null
        iptables?.teardown(_state.value.hotspotIface, _state.value.vpnIface)
        iptables = null
        rootShell?.close(); rootShell = null
        clientTracker?.clear()
        if (!keepSettings) currentSettings = null
        _state.value = _state.value.copy(
            runState = RunState.STOPPED,
            clients = emptyList(),
            totalBytes = 0,
            pacUrl = "",
            guests = emptyList(),
            statusMessage = getString(R.string.notif_stopped)
        )
        appendLog(getString(R.string.log_service_stopped))
        updateNotification(getString(R.string.notif_stopped))
    }

    private fun fail(message: String) {
        _state.value = _state.value.copy(runState = RunState.ERROR, errorMessage = message)
        appendLog(getString(R.string.log_error_prefix, message))
        updateNotification(getString(R.string.notif_error, message))
    }

    private fun pushClients() {
        val tracker = clientTracker ?: return
        if (_state.value.mode == VpshMode.FULL && _state.value.hotspotIface.isNotEmpty()) {
            tracker.refreshFromNeighTable(_state.value.hotspotIface)
            reconcileFullModeLimits()
        }
        _state.value = _state.value.copy(clients = tracker.snapshot())
    }

    private fun pushBytes() {
        val tracker = clientTracker ?: return
        _state.value = _state.value.copy(totalBytes = tracker.totalBytes())
    }

    private fun appendLog(line: String) {
        val stamped = "[${logFmt.format(Date())}] $line"
        val logs = (_state.value.logs + stamped).takeLast(200)
        _state.value = _state.value.copy(logs = logs)
    }

    private fun isActiveState(runState: RunState) =
        runState == RunState.RUNNING || runState == RunState.STARTING || runState == RunState.PAUSED

    private fun buildNotification(text: String, runState: RunState): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val nothingActive = !isActiveState(runState)

        val builder = NotificationCompat.Builder(this, VpshApplication.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openIntent)
            .setOngoing(!nothingActive)

        if (nothingActive) {
            val dismissIntent = PendingIntent.getService(
                this, 2,
                Intent(this, VpshService::class.java).setAction(ACTION_DISMISS),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_close), dismissIntent)
        } else {
            val stopIntent = PendingIntent.getService(
                this, 1,
                Intent(this, VpshService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_action_stop), stopIntent)
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, _state.value.runState))
    }

    private fun stopForegroundCompat() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        scope.launch { doStop() }
        super.onDestroy()
    }

    companion object {
        const val NOTIF_ID = 4271
        const val ACTION_STOP = "com.batman.vpsh.action.STOP"
        const val ACTION_TOGGLE = "com.batman.vpsh.action.TOGGLE"
        const val ACTION_DISMISS = "com.batman.vpsh.action.DISMISS"
        const val ACTION_AUTOSTART = "com.batman.vpsh.action.AUTOSTART"
    }
}

object VpshBridge {
    val runState = MutableStateFlow(RunState.STOPPED)
}
