package com.batman.vpsh.core

import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PacServer(
    private val port: Int,
    private val proxyHost: () -> String,
    private val socks5Port: Int?,
    private val httpPort: Int?
) {
    private val TAG = "VPSH-Pac"
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()

    private val bypassNets = listOf(
        "10.0.0.0" to "255.0.0.0",
        "172.16.0.0" to "255.240.0.0",
        "192.168.0.0" to "255.255.0.0",
        "127.0.0.0" to "255.0.0.0"
    )

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

    fun pacUrl(): String = "http://${proxyHost()}:$port/proxy.pac"

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (running.get()) {
            val client = try { ss.accept() } catch (_: Exception) { break }
            pool.execute { handleClient(client) }
        }
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8))
            val requestLine = reader.readLine() ?: return client.close()
            
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val out = client.getOutputStream()
            val path = requestLine.split(" ").getOrNull(1) ?: "/"
            if (path.startsWith("/proxy.pac") || path == "/" || path.startsWith("/wpad.dat")) {
                writePac(out)
            } else {
                write404(out)
            }
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) { }
        }
    }

    private fun buildPacBody(): String {
        val host = proxyHost()
        val bypass = bypassNets.joinToString("") { (net, mask) ->
            "  if (isInNet(host, \"$net\", \"$mask\")) return \"DIRECT\";\n"
        }
        val proxyChain = buildString {
            
            if (socks5Port != null) append("SOCKS5 $host:$socks5Port; ")
            if (httpPort != null) append("PROXY $host:$httpPort; ")
            append("DIRECT")
        }
        return "function FindProxyForURL(url, host) {\n" +
            "  if (isPlainHostName(host)) return \"DIRECT\";\n" +
            "  if (dnsDomainIs(host, \".local\") || host == \"localhost\") return \"DIRECT\";\n" +
            bypass +
            "  return \"$proxyChain\";\n" +
            "}\n"
    }

    private fun writePac(out: OutputStream) {
        val body = buildPacBody().toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/x-ns-proxy-autoconfig\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "Cache-Control: no-store\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(body)
        out.flush()
    }

    private fun write404(out: OutputStream) {
        val body = "Not found".toByteArray(StandardCharsets.UTF_8)
        val header = "HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(header.toByteArray(StandardCharsets.UTF_8))
        out.write(body)
        out.flush()
    }

    companion object {
        const val DEFAULT_PORT = 8199
    }
}
