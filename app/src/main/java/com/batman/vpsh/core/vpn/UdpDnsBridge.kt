package com.batman.vpsh.core.vpn

import com.batman.vpsh.core.batproxy.BatTunnelManager
import com.batman.vpsh.core.batproxy.WorkerTunnel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream

class UdpDnsBridge(
    private val tunnelManager: BatTunnelManager,
    private val dnsHost: String,
    private val dnsPort: Int,
    private val sink: PacketSink,
    private val scope: CoroutineScope
) {
    fun handle(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int, dnsQuery: ByteArray) {
        scope.launch {
            var tunnel: WorkerTunnel? = null
            try {
                val (t, worker) = tunnelManager.openTunnel(dnsHost, dnsPort)
                tunnel = t
                val framed = ByteArrayOutputStream(2 + dnsQuery.size).apply {
                    write((dnsQuery.size shr 8) and 0xFF)
                    write(dnsQuery.size and 0xFF)
                    write(dnsQuery)
                }.toByteArray()
                t.sendBytes(framed)

                val response = readFramedResponse(t)
                tunnelManager.releaseWorker(worker)
                if (response != null) {
                    val packet = Ipv4Tcp.buildUdpPacket(dstIp, srcIp, dstPort, srcPort, response)
                    sink.write(packet)
                }
            } catch (_: Exception) {
                
            } finally {
                tunnel?.close()
            }
        }
    }

    private suspend fun readFramedResponse(tunnel: WorkerTunnel): ByteArray? = withTimeoutOrNull(8000) {
        val buffer = ByteArrayOutputStream()
        var expectedLen = -1
        while (true) {
            val frame = tunnel.receive(8000)
            val bytes = when (frame) {
                is WorkerTunnel.Frame.Binary -> frame.bytes
                is WorkerTunnel.Frame.Closed -> return@withTimeoutOrNull null
                is WorkerTunnel.Frame.Text -> continue
            }
            buffer.write(bytes)
            val current = buffer.toByteArray()
            if (expectedLen < 0 && current.size >= 2) {
                expectedLen = ((current[0].toInt() and 0xFF) shl 8) or (current[1].toInt() and 0xFF)
            }
            if (expectedLen in 0..current.size - 2) {
                return@withTimeoutOrNull current.copyOfRange(2, 2 + expectedLen)
            }
        }
        @Suppress("UNREACHABLE_CODE")
        null
    }
}
