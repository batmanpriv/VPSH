package com.batman.vpsh.core

import android.util.Base64
import android.util.Log
import com.batman.vpsh.data.VpshSettings
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class HttpProxyServer(
    private val port: Int,
    private val authUser: String?,
    private val authPass: String?,
    private val settings: VpshSettings,
    private val onClientSeen: (String) -> Unit,
    private val onBytes: (Long) -> Unit,
    private val isBlocked: (String) -> Boolean = { false },
    private val limitKbpsFor: (String) -> Int = { 0 }
) {
    private val TAG = "VPSH-HttpProxy"
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    val totalConnections = AtomicLong(0)

    fun start(): Boolean {
        return try {
            serverSocket = ServerSocket()
            serverSocket?.reuseAddress = true
            serverSocket?.bind(InetSocketAddress("0.0.0.0", port))
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
            totalConnections.incrementAndGet()
            onClientSeen(ip)
            val limiter = limitKbpsFor(ip).takeIf { it > 0 }?.let { RateLimiter(it) }
            pool.execute { handleClient(client, limiter) }
        }
    }

    private fun requiresAuth() = !authUser.isNullOrEmpty()

    private fun handleClient(client: Socket, limiter: RateLimiter?) {
        try {
            client.soTimeout = 20_000
            val input = BufferedReader(InputStreamReader(client.getInputStream()))
            val requestLine = input.readLine() ?: return client.close()
            val parts = requestLine.split(" ")
            if (parts.size < 3) return client.close()
            val method = parts[0]
            val target = parts[1]

            val headers = LinkedHashMap<String, String>()
            var line: String?
            while (true) {
                line = input.readLine()
                if (line.isNullOrEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            if (requiresAuth() && !isAuthorized(headers)) {
                sendAuthRequired(client.getOutputStream())
                client.close()
                return
            }

            if (method.equals("CONNECT", ignoreCase = true)) {
                handleConnect(client, target, limiter)
            } else {
                handlePlainHttp(client, method, target, headers, input, limiter)
            }
        } catch (_: Exception) {
            try { client.close() } catch (_: Exception) { }
        }
    }

    private fun isAuthorized(headers: Map<String, String>): Boolean {
        val header = headers["proxy-authorization"] ?: return false
        if (!header.startsWith("Basic ", ignoreCase = true)) return false
        val decoded = try {
            String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT))
        } catch (_: Exception) { return false }
        val sep = decoded.indexOf(':')
        if (sep < 0) return false
        return decoded.substring(0, sep) == authUser && decoded.substring(sep + 1) == authPass
    }

    private fun sendAuthRequired(out: OutputStream) {
        val body = "Proxy authentication required"
        out.write(
            ("HTTP/1.1 407 Proxy Authentication Required\r\n" +
                "Proxy-Authenticate: Basic realm=\"VPSH\"\r\n" +
                "Content-Length: ${body.length}\r\n" +
                "Connection: close\r\n\r\n$body").toByteArray()
        )
        out.flush()
    }

    private fun handleConnect(client: Socket, target: String, limiter: RateLimiter?) {
        val (host, port) = splitHostPort(target, 443)
        val remote = try {
            UpstreamDialer.dial(host, port, settings)
        } catch (_: Exception) {
            client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            client.close()
            return
        }
        client.getOutputStream().write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray())
        client.getOutputStream().flush()
        pipe(client, remote, limiter)
    }

    private fun handlePlainHttp(
        client: Socket,
        method: String,
        target: String,
        headers: MutableMap<String, String>,
        input: BufferedReader,
        limiter: RateLimiter?
    ) {
        val uri = try { URI(target) } catch (_: Exception) { null }
        val host = uri?.host ?: headers["host"]?.substringBefore(':') ?: return client.close()
        val port = if (uri != null && uri.port != -1) uri.port else headers["host"]?.substringAfter(':', "80")?.toIntOrNull() ?: 80
        val path = if (uri != null && uri.path.isNotEmpty()) (uri.path + (uri.query?.let { "?$it" } ?: "")) else target

        val remote = try { UpstreamDialer.dial(host, port, settings) } catch (_: Exception) {
            client.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray())
            client.close()
            return
        }

        headers.remove("proxy-authorization")
        headers.remove("proxy-connection")
        val sb = StringBuilder()
        sb.append("$method $path HTTP/1.1\r\n")
        headers.forEach { (k, v) -> sb.append("$k: $v\r\n") }
        sb.append("Connection: close\r\n\r\n")

        remote.getOutputStream().write(sb.toString().toByteArray())
        remote.getOutputStream().flush()
        pipe(client, remote, limiter)
    }

    private fun splitHostPort(target: String, defaultPort: Int): Pair<String, Int> {
        val idx = target.lastIndexOf(':')
        return if (idx > 0) {
            val h = target.substring(0, idx)
            val p = target.substring(idx + 1).toIntOrNull() ?: defaultPort
            h to p
        } else target to defaultPort
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
