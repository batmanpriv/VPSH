package com.batman.vpsh.core

import android.util.Log
import com.batman.vpsh.data.VpshSettings
import java.io.DataInputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Socks5ProxyServer(
    private val port: Int,
    private val enableAuth: Boolean,
    private val authChecker: (user: String, pass: String, ip: String) -> Boolean,
    private val settings: VpshSettings,
    private val onClientSeen: (String) -> Unit,
    private val onBytes: (String, Long) -> Unit,
    private val isBlocked: (String) -> Boolean = { false },
    private val limitKbpsFor: (String) -> Int = { 0 },
    private val bindHost: () -> String = { "0.0.0.0" }
) {
    private val TAG = "VPSH-Socks5"
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()

    private fun requiresAuth() = enableAuth

    fun start(): Boolean {
        return try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", port))
            }
            running.set(true)
            pool.execute { acceptLoop() }
            true
        } catch (e: IOException) {
            Log.e(TAG, "bind failed on port $port", e)
            false
        }
    }

    fun stop() {
        running.set(false)
        try { serverSocket?.close() } catch (_: Exception) { }
        pool.shutdownNow()
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running.get()) {
            val client = try { ss.accept() } catch (_: Exception) { break }
            val ip = client.inetAddress.hostAddress ?: "unknown"
            if (isBlocked(ip)) {
                try { client.close() } catch (_: Exception) { }
                continue
            }
            onClientSeen(ip)
            val limiter = limitKbpsFor(ip).takeIf { it > 0 }?.let { RateLimiter(it) }
            pool.execute { handleClient(client, limiter, ip) }
        }
    }

    private fun handleClient(client: Socket, limiter: RateLimiter?, clientIp: String) {
        try {
            client.soTimeout = 20_000
            val din = DataInputStream(client.getInputStream())
            val out = client.getOutputStream()

            val ver = din.readUnsignedByte()
            if (ver != 5) return client.close()
            val nMethods = din.readUnsignedByte()
            val methods = ByteArray(nMethods).also { din.readFully(it) }

            val wantAuth = requiresAuth()
            val chosen = if (wantAuth && methods.contains(2.toByte())) 2 else if (!wantAuth && methods.contains(0.toByte())) 0 else 0xFF
            out.write(byteArrayOf(5, chosen.toByte())); out.flush()
            if (chosen == 0xFF) return client.close()

            if (chosen == 2) {
                val authVer = din.readUnsignedByte()
                val ulen = din.readUnsignedByte()
                val user = ByteArray(ulen).also { din.readFully(it) }.toString(Charsets.UTF_8)
                val plen = din.readUnsignedByte()
                val pass = ByteArray(plen).also { din.readFully(it) }.toString(Charsets.UTF_8)
                val ok = authChecker(user, pass, clientIp)
                out.write(byteArrayOf(1, if (ok) 0 else 1)); out.flush()
                if (!ok) return client.close()
            }

            val rVer = din.readUnsignedByte()
            val cmd = din.readUnsignedByte()
            din.readUnsignedByte() 
            val atyp = din.readUnsignedByte()
            if (rVer != 5) { replyError(out, 7); return client.close() }

            if (cmd == 3) {
                handleUdpAssociate(client, din, out, atyp, limiter, clientIp)
                return
            }
            if (cmd != 1) {
                replyError(out, 7)
                return client.close()
            }

            val host: String = when (atyp) {
                1 -> { 
                    val addr = ByteArray(4).also { din.readFully(it) }
                    InetAddress.getByAddress(addr).hostAddress
                }
                3 -> { 
                    val len = din.readUnsignedByte()
                    val bytes = ByteArray(len).also { din.readFully(it) }
                    String(bytes, Charsets.UTF_8)
                }
                4 -> { 
                    val addr = ByteArray(16).also { din.readFully(it) }
                    InetAddress.getByAddress(addr).hostAddress
                }
                else -> { replyError(out, 8); return client.close() }
            }
            val portBytes = ByteArray(2).also { din.readFully(it) }
            val targetPort = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            val remote = try {
                UpstreamDialer.dial(host, targetPort, settings)
            } catch (_: Exception) {
                replyError(out, 5); client.close(); return
            }

            val boundAddr = (remote.localAddress.address)
            val boundPort = remote.localPort
            val reply = byteArrayOf(5, 0, 0, 1) + boundAddr + byteArrayOf((boundPort shr 8).toByte(), boundPort.toByte())
            out.write(reply); out.flush()

            pipe(client, remote, limiter, clientIp)
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) { }
        }
    }

    private fun handleUdpAssociate(
        client: Socket,
        din: DataInputStream,
        out: java.io.OutputStream,
        atyp: Int,
        limiter: RateLimiter?,
        clientIp: String
    ) {
        
        try { client.soTimeout = 0 } catch (_: Exception) { }

        try {
            when (atyp) {
                1 -> ByteArray(4).also { din.readFully(it) }
                3 -> { val len = din.readUnsignedByte(); ByteArray(len).also { din.readFully(it) } }
                4 -> ByteArray(16).also { din.readFully(it) }
                else -> { replyError(out, 8); client.close(); return }
            }
            ByteArray(2).also { din.readFully(it) }
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) { }
            return
        }

        val relaySocket = try {
            DatagramSocket(InetSocketAddress("0.0.0.0", 0))
        } catch (_: Exception) {
            replyError(out, 1); try { client.close() } catch (_: Exception) { }; return
        }
        val upstreamSocket = try {
            DatagramSocket()
        } catch (_: Exception) {
            relaySocket.close(); replyError(out, 1); try { client.close() } catch (_: Exception) { }; return
        }

        val relayPort = relaySocket.localPort
        val relayAddrBytes = try {
            InetAddress.getByName(bindHost()).address
        } catch (_: Exception) {
            byteArrayOf(0, 0, 0, 0)
        }
        val reply = byteArrayOf(5, 0, 0, if (relayAddrBytes.size == 16) 4 else 1) +
            relayAddrBytes + byteArrayOf((relayPort shr 8).toByte(), relayPort.toByte())
        try {
            out.write(reply); out.flush()
        } catch (_: Exception) {
            relaySocket.close(); upstreamSocket.close(); return
        }

        val alive = AtomicBoolean(true)
        val clientAddrRef = java.util.concurrent.atomic.AtomicReference<InetSocketAddress>(null)

        val watchdog = Thread {
            try {
                while (alive.get()) {
                    if (din.read() == -1) break
                }
            } catch (_: Exception) {
            } finally {
                alive.set(false)
                try { relaySocket.close() } catch (_: Exception) { }
                try { upstreamSocket.close() } catch (_: Exception) { }
            }
        }
        watchdog.isDaemon = true
        watchdog.start()

        val t1 = Thread {
            val buf = ByteArray(65535)
            try {
                while (alive.get()) {
                    val pkt = DatagramPacket(buf, buf.size)
                    relaySocket.receive(pkt)
                    clientAddrRef.set(InetSocketAddress(pkt.address, pkt.port))
                    val decoded = decodeUdpEnvelope(pkt.data, pkt.length) ?: continue
                    limiter?.acquire(decoded.data.size)
                    onBytes(clientIp, decoded.data.size.toLong())
                    val dest = InetSocketAddress(InetAddress.getByName(decoded.host), decoded.port)
                    upstreamSocket.send(DatagramPacket(decoded.data, decoded.data.size, dest))
                }
            } catch (_: Exception) {
            }
        }

        val t2 = Thread {
            val buf = ByteArray(65535)
            try {
                while (alive.get()) {
                    val pkt = DatagramPacket(buf, buf.size)
                    upstreamSocket.receive(pkt)
                    val ca = clientAddrRef.get() ?: continue
                    val envelope = encodeUdpEnvelope(pkt.address, pkt.port, pkt.data, pkt.length)
                    limiter?.acquire(pkt.length)
                    onBytes(clientIp, pkt.length.toLong())
                    relaySocket.send(DatagramPacket(envelope, envelope.size, ca))
                }
            } catch (_: Exception) {
            }
        }

        t1.start(); t2.start()
        t1.join(); t2.join()
        try { relaySocket.close() } catch (_: Exception) { }
        try { upstreamSocket.close() } catch (_: Exception) { }
        try { client.close() } catch (_: Exception) { }
    }

    private data class UdpDecoded(val host: String, val port: Int, val data: ByteArray)

    private fun decodeUdpEnvelope(buf: ByteArray, len: Int): UdpDecoded? {
        if (len < 4) return null
        var pos = 2 
        val frag = buf[pos].toInt() and 0xFF; pos += 1
        if (frag != 0) return null 
        val atyp = buf[pos].toInt() and 0xFF; pos += 1
        val host: String
        when (atyp) {
            1 -> {
                if (len - pos < 4) return null
                host = InetAddress.getByAddress(buf.copyOfRange(pos, pos + 4)).hostAddress
                pos += 4
            }
            3 -> {
                if (len - pos < 1) return null
                val l = buf[pos].toInt() and 0xFF; pos += 1
                if (len - pos < l) return null
                host = String(buf, pos, l, Charsets.UTF_8); pos += l
            }
            4 -> {
                if (len - pos < 16) return null
                host = InetAddress.getByAddress(buf.copyOfRange(pos, pos + 16)).hostAddress
                pos += 16
            }
            else -> return null
        }
        if (len - pos < 2) return null
        val port = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
        pos += 2
        return UdpDecoded(host, port, buf.copyOfRange(pos, len))
    }

    private fun encodeUdpEnvelope(addr: InetAddress, port: Int, data: ByteArray, len: Int): ByteArray {
        val addrBytes = addr.address
        val atyp = if (addrBytes.size == 16) 4 else 1
        val header = byteArrayOf(0, 0, 0, atyp.toByte()) + addrBytes +
            byteArrayOf((port shr 8).toByte(), port.toByte())
        return header + data.copyOfRange(0, len)
    }

    private fun replyError(out: java.io.OutputStream, code: Int) {
        try {
            out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
        } catch (_: Exception) { }
    }

    private fun pipe(a: Socket, b: Socket, limiter: RateLimiter?, clientIp: String) {
        val t1 = Thread { relay(a, b, limiter, clientIp) }
        val t2 = Thread { relay(b, a, limiter, clientIp) }
        t1.start(); t2.start()
        t1.join(); t2.join()
        try { a.close() } catch (_: Exception) { }
        try { b.close() } catch (_: Exception) { }
    }

    private fun relay(from: Socket, to: Socket, limiter: RateLimiter?, clientIp: String) {
        try {
            val buf = ByteArray(8192)
            val input = from.getInputStream()
            val output = to.getOutputStream()
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                limiter?.acquire(n)
                output.write(buf, 0, n)
                output.flush()
                onBytes(clientIp, n.toLong())
            }
        } catch (_: Exception) {
        } finally {
            try { to.shutdownOutput() } catch (_: Exception) { }
        }
    }
}
