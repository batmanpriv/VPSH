package com.batman.vpsh.core

import android.util.Log
import com.batman.vpsh.data.VpshSettings
import java.io.DataInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class Socks5ProxyServer(
    private val port: Int,
    private val authUser: String?,
    private val authPass: String?,
    private val settings: VpshSettings,
    private val onClientSeen: (String) -> Unit,
    private val onBytes: (Long) -> Unit,
    private val isBlocked: (String) -> Boolean = { false },
    private val limitKbpsFor: (String) -> Int = { 0 }
) {
    private val TAG = "VPSH-Socks5"
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()

    private fun requiresAuth() = !authUser.isNullOrEmpty()

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
            pool.execute { handleClient(client, limiter) }
        }
    }

    private fun handleClient(client: Socket, limiter: RateLimiter?) {
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
                val ok = user == authUser && pass == authPass
                out.write(byteArrayOf(1, if (ok) 0 else 1)); out.flush()
                if (!ok) return client.close()
            }

            val rVer = din.readUnsignedByte()
            val cmd = din.readUnsignedByte()
            din.readUnsignedByte() 
            val atyp = din.readUnsignedByte()
            if (rVer != 5 || cmd != 1) { 
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

            pipe(client, remote, limiter)
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) { }
        }
    }

    private fun replyError(out: java.io.OutputStream, code: Int) {
        try {
            out.write(byteArrayOf(5, code.toByte(), 0, 1, 0, 0, 0, 0, 0, 0))
            out.flush()
        } catch (_: Exception) { }
    }

    private fun pipe(a: Socket, b: Socket, limiter: RateLimiter?) {
        val t1 = Thread { relay(a, b, limiter) }
        val t2 = Thread { relay(b, a, limiter) }
        t1.start(); t2.start()
        t1.join(); t2.join()
        try { a.close() } catch (_: Exception) { }
        try { b.close() } catch (_: Exception) { }
    }

    private fun relay(from: Socket, to: Socket, limiter: RateLimiter?) {
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
                onBytes(n.toLong())
            }
        } catch (_: Exception) {
        } finally {
            try { to.shutdownOutput() } catch (_: Exception) { }
        }
    }
}
