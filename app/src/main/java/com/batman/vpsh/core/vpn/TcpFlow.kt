package com.batman.vpsh.core.vpn

import com.batman.vpsh.core.batproxy.BatTunnelManager
import com.batman.vpsh.core.batproxy.WorkerTunnel
import com.batman.vpsh.data.BatWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean

class TcpFlow(
    private val srcIp: ByteArray, private val srcPort: Int,
    private val dstIp: ByteArray, private val dstPort: Int,
    private val sink: PacketSink,
    private val tunnelManager: BatTunnelManager,
    private val scope: CoroutineScope,
    private val onClosed: () -> Unit
) {
    enum class State { SYN_RCVD, ESTABLISHED, CLOSED }

    @Volatile var state: State = State.SYN_RCVD
        private set

    private var clientNext: Long = 0L 
    private var ourSeq: Long = ThreadLocalRandom.current().nextLong(0, 0xFFFFFFFFL)
    private var tunnel: WorkerTunnel? = null
    private var worker: BatWorker? = null
    private val closed = AtomicBoolean(false)

    fun onSyn(seq: Long) {
        clientNext = seq + 1
        scope.launch {
            try {
                val (t, w) = tunnelManager.openTunnel(Ipv4Tcp.ipToString(dstIp), dstPort)
                if (closed.get()) { t.close(); tunnelManager.releaseWorker(w); return@launch }
                tunnel = t; worker = w
                sendControl(Ipv4Tcp.FLAG_SYN or Ipv4Tcp.FLAG_ACK, ourSeq)
                ourSeq += 1
                state = State.ESTABLISHED
                pumpFromTunnel(t)
            } catch (e: Exception) {
                sendControl(Ipv4Tcp.FLAG_RST or Ipv4Tcp.FLAG_ACK, ourSeq)
                close()
            }
        }
    }

    fun onSegment(seq: Long, ack: Long, flags: Int, payload: ByteArray) {
        if (state == State.CLOSED) return
        if (flags and Ipv4Tcp.FLAG_RST != 0) { close(); return }

        if (payload.isNotEmpty() && state == State.ESTABLISHED) {
            if (seq == clientNext) {
                clientNext += payload.size
                tunnel?.sendBytes(payload)
            }
            
            sendAck()
        }

        if (flags and Ipv4Tcp.FLAG_FIN != 0) {
            clientNext += 1
            sendAck()
            tunnel?.let { t -> scope.launch { t.close() } }
            close()
        }
    }

    private suspend fun pumpFromTunnel(t: WorkerTunnel) {
        try {
            while (state == State.ESTABLISHED) {
                when (val frame = t.receive(120_000)) {
                    is WorkerTunnel.Frame.Binary -> sendData(frame.bytes)
                    is WorkerTunnel.Frame.Closed -> {
                        sendControl(Ipv4Tcp.FLAG_FIN or Ipv4Tcp.FLAG_ACK, ourSeq)
                        ourSeq += 1
                        break
                    }
                    is WorkerTunnel.Frame.Text -> {} 
                }
            }
        } catch (_: Exception) {
        } finally {
            close()
        }
    }

    private fun sendData(payload: ByteArray) {
        var offset = 0
        while (offset < payload.size) {
            val end = minOf(offset + MAX_SEGMENT, payload.size)
            val chunk = payload.copyOfRange(offset, end)
            sendPacket(Ipv4Tcp.FLAG_ACK or Ipv4Tcp.FLAG_PSH, ourSeq, chunk)
            ourSeq += chunk.size
            offset = end
        }
    }

    private fun sendControl(flags: Int, seq: Long) = sendPacket(flags, seq, ByteArray(0))
    private fun sendAck() = sendPacket(Ipv4Tcp.FLAG_ACK, ourSeq, ByteArray(0))

    private fun sendPacket(flags: Int, seq: Long, payload: ByteArray) {
        
        val packet = Ipv4Tcp.buildTcpPacket(dstIp, srcIp, dstPort, srcPort, seq, clientNext, flags, 65535, payload)
        sink.write(packet)
    }

    fun close() {
        if (closed.compareAndSet(false, true)) {
            state = State.CLOSED
            tunnel?.close()
            worker?.let { tunnelManager.releaseWorker(it) }
            onClosed()
        }
    }

    companion object {
        const val MAX_SEGMENT = 1400
    }
}
