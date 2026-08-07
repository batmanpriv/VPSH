package com.batman.vpsh.service

import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import com.batman.vpsh.MainActivity
import com.batman.vpsh.R
import com.batman.vpsh.core.batproxy.BatStatsSnapshot
import com.batman.vpsh.core.batproxy.BatTunnelManager
import com.batman.vpsh.core.vpn.Ipv4Tcp
import com.batman.vpsh.core.vpn.PacketSink
import com.batman.vpsh.core.vpn.TcpFlow
import com.batman.vpsh.core.vpn.UdpDnsBridge
import com.batman.vpsh.data.BatProxyPreferences
import com.batman.vpsh.util.LocaleHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

data class BatProxyUiState(
    val running: Boolean = false,
    val stats: BatStatsSnapshot = BatStatsSnapshot(),
    val error: String? = null,
    val region: com.batman.vpsh.core.batproxy.VpnRegionInfo? = null
)

class BatVpnService : VpnService() {

    inner class LocalBinder : Binder() {
        fun getService(): BatVpnService = this@BatVpnService
    }

    private val binder = LocalBinder()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    
    private var sessionScope: CoroutineScope? = null

    private val _state = MutableStateFlow(BatProxyUiState())
    val state: StateFlow<BatProxyUiState> = _state

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelManager: BatTunnelManager? = null
    private var udpDnsBridge: UdpDnsBridge? = null
    private val flows = ConcurrentHashMap<String, TcpFlow>()
    private val writeQueue = Channel<ByteArray>(Channel.UNLIMITED)

    private val stopping = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocaleHelper.wrap(base))
    }

    override fun onBind(intent: Intent?): IBinder? {
        
        return if (intent?.action == VpnService.SERVICE_INTERFACE) super.onBind(intent) else binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopTunnel()
            stopForegroundCompat()
            
            (getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager)?.cancel(NOTIF_ID)
            stopSelf()
            return START_NOT_STICKY
        }
        if (vpnInterface == null) {
            stopping.set(false)
            startForeground(NOTIF_ID, buildNotification(getString(R.string.batproxy_notif_connecting), running = true))
            scope.launch { startTunnel() }
        }
        return START_STICKY
    }

    private suspend fun startTunnel() {
        try {
            val cfg = BatProxyPreferences(this).current()
            if (cfg.workers.isEmpty()) {
                fail(getString(R.string.batproxy_err_no_workers))
                return
            }

            val session = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            sessionScope = session

            val manager = BatTunnelManager(cfg.workers)
            tunnelManager = manager
            manager.startHealthCheckLoop(session)
            session.launch {
                manager.stats.collect { snap -> _state.value = _state.value.copy(running = true, stats = snap, error = null) }
            }

            val builder = Builder()
                .setSession(getString(R.string.app_name))
                .addAddress(TUN_ADDRESS, 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS_ADDRESS)
                .setMtu(MTU)
            
            try {
                if (cfg.splitTunnelMode == com.batman.vpsh.data.SplitTunnelMode.INCLUDE_ONLY && cfg.splitTunnelApps.isNotEmpty()) {
                    
                    for (pkg in cfg.splitTunnelApps) {
                        try { builder.addAllowedApplication(pkg) } catch (_: Exception) { }
                    }
                } else {
                    builder.addDisallowedApplication(packageName)
                    if (cfg.splitTunnelMode == com.batman.vpsh.data.SplitTunnelMode.EXCLUDE) {
                        for (pkg in cfg.splitTunnelApps) {
                            try { builder.addDisallowedApplication(pkg) } catch (_: Exception) { }
                        }
                    }
                }
            } catch (_: Exception) {
            }

            val pfd = builder.establish()
            if (pfd == null) {
                fail(getString(R.string.batproxy_err_vpn_setup))
                return
            }
            vpnInterface = pfd

            val sink = PacketSink { packet -> writeQueue.trySend(packet) }
            udpDnsBridge = UdpDnsBridge(manager, cfg.dnsHost, cfg.dnsPort, sink, session)

            session.launch { pumpWrites(pfd) }
            session.launch { pumpReads(pfd, sink, manager, session) }

            updateNotification(getString(R.string.batproxy_notif_connected))

            session.launch {
                val info = com.batman.vpsh.core.batproxy.VpnRegionDetector.detect(manager)
                if (info != null) _state.value = _state.value.copy(region = info)
            }
        } catch (e: Exception) {
            fail(e.message ?: getString(R.string.batproxy_err_vpn_setup))
        }
    }

    private fun pumpReads(pfd: ParcelFileDescriptor, sink: PacketSink, manager: BatTunnelManager, session: CoroutineScope) {
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(MTU + 40)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) continue
                handlePacket(buf, n, sink, manager, session)
            }
        } catch (_: IOException) {
            
        }
    }

    private suspend fun pumpWrites(pfd: ParcelFileDescriptor) {
        val output = FileOutputStream(pfd.fileDescriptor)
        try {
            for (packet in writeQueue) {
                output.write(packet)
            }
        } catch (_: IOException) {
        }
    }

    private fun handlePacket(buf: ByteArray, len: Int, sink: PacketSink, manager: BatTunnelManager, session: CoroutineScope) {
        val ip = Ipv4Tcp.parseIpv4(buf, len) ?: return
        val payloadStart = ip.headerLength
        when (ip.protocol) {
            Ipv4Tcp.PROTO_TCP -> {
                val tcp = Ipv4Tcp.parseTcp(buf, payloadStart, len) ?: return
                val dataOffset = payloadStart + tcp.headerLength
                val payload = if (dataOffset < len) buf.copyOfRange(dataOffset, len) else ByteArray(0)
                val key = flowKey(ip.srcIp, tcp.srcPort, ip.dstIp, tcp.dstPort)
                if (tcp.has(Ipv4Tcp.FLAG_SYN) && !tcp.has(Ipv4Tcp.FLAG_ACK)) {
                    val flow = TcpFlow(ip.srcIp, tcp.srcPort, ip.dstIp, tcp.dstPort, sink, manager, session) { flows.remove(key) }
                    flows[key] = flow
                    flow.onSyn(tcp.seq)
                } else {
                    flows[key]?.onSegment(tcp.seq, tcp.ack, tcp.flags, payload)
                }
            }
            Ipv4Tcp.PROTO_UDP -> {
                val udp = Ipv4Tcp.parseUdp(buf, payloadStart, len) ?: return
                if (udp.dstPort == 53) {
                    val dataOffset = payloadStart + udp.headerLength
                    val query = buf.copyOfRange(dataOffset, len)
                    udpDnsBridge?.handle(ip.srcIp, udp.srcPort, ip.dstIp, udp.dstPort, query)
                }
                
            }
            else -> {} 
        }
    }

    private fun flowKey(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int) =
        "${Ipv4Tcp.ipToString(srcIp)}:$srcPort-${Ipv4Tcp.ipToString(dstIp)}:$dstPort"

    private fun stopTunnel(finalMessage: String? = null, error: String? = null, notify: Boolean = true) {
        
        val firstStop = stopping.compareAndSet(false, true)

        try {
            vpnInterface?.close()
        } catch (_: Exception) {
        }
        vpnInterface = null

        sessionScope?.cancel()
        sessionScope = null
        flows.values.forEach { it.close() }
        flows.clear()
        tunnelManager = null
        udpDnsBridge = null
        _state.value = BatProxyUiState(running = false, error = error)

        if (notify && firstStop) {
            updateNotification(finalMessage ?: getString(R.string.batproxy_notif_stopped))
        }
    }

    private fun fail(message: String) {
        stopTunnel(finalMessage = getString(R.string.batproxy_notif_error, message), error = message)
    }

    private fun buildNotification(text: String, running: Boolean): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.batproxy_tab_title))
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(openIntent)
            .setOngoing(running)

        if (running) {
            val stopIntent = PendingIntent.getService(
                this, 3, Intent(this, BatVpnService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_media_pause, getString(R.string.notif_action_stop), stopIntent)
        } else {
            val dismissIntent = PendingIntent.getService(
                this, 4, Intent(this, BatVpnService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, getString(R.string.notif_action_close), dismissIntent)
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        val running = vpnInterface != null
        val nm = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text, running))
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
        
        stopTunnel(notify = false)
        (getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager)?.cancel(NOTIF_ID)
        super.onDestroy()
    }

    override fun onRevoke() {
        
        stopTunnel()
        stopForegroundCompat()
        (getSystemService(NOTIFICATION_SERVICE) as? android.app.NotificationManager)?.cancel(NOTIF_ID)
        stopSelf()
        super.onRevoke()
    }

    companion object {
        const val NOTIF_ID = 4272
        const val CHANNEL_ID = "batproxy_channel"
        const val ACTION_STOP = "com.batman.vpsh.batproxy.action.STOP"
        const val TUN_ADDRESS = "10.10.10.2"
        const val DNS_ADDRESS = "10.10.10.1"
        const val MTU = 1500
    }
}
