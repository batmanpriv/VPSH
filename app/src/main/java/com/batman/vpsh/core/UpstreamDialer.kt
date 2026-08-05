package com.batman.vpsh.core

import android.util.Base64
import com.batman.vpsh.data.UpstreamType
import com.batman.vpsh.data.VpshSettings
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket

object UpstreamDialer {

    private const val CONNECT_TIMEOUT_MS = 10_000

    fun dial(host: String, port: Int, settings: VpshSettings): Socket {
        return when (settings.upstreamType) {
            UpstreamType.NONE -> Socket().apply { connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
            UpstreamType.SOCKS5 -> dialViaSocks5(host, port, settings)
            UpstreamType.HTTP -> dialViaHttpConnect(host, port, settings)
        }
    }

    private fun dialViaSocks5(host: String, port: Int, settings: VpshSettings): Socket {
        val sock = Socket().apply { connect(InetSocketAddress(settings.upstreamHost, settings.upstreamPort), CONNECT_TIMEOUT_MS) }
        val out = DataOutputStream(sock.getOutputStream())
        val din = DataInputStream(sock.getInputStream())
        val useAuth = settings.upstreamUser.isNotBlank()

        out.write(if (useAuth) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0))
        out.flush()
        val chosen = ByteArray(2).also { din.readFully(it) }
        if (chosen[0].toInt() != 5) { sock.close(); throw java.io.IOException("upstream not SOCKS5") }

        if (chosen[1].toInt() == 2) {
            val user = settings.upstreamUser.toByteArray()
            val pass = settings.upstreamPass.toByteArray()
            out.write(byteArrayOf(1, user.size.toByte())); out.write(user)
            out.write(byteArrayOf(pass.size.toByte())); out.write(pass)
            out.flush()
            val authResp = ByteArray(2).also { din.readFully(it) }
            if (authResp[1].toInt() != 0) { sock.close(); throw java.io.IOException("upstream auth rejected") }
        } else if (chosen[1].toInt() != 0) {
            sock.close(); throw java.io.IOException("upstream rejected auth methods")
        }

        val hostBytes = host.toByteArray()
        out.write(byteArrayOf(5, 1, 0, 3, hostBytes.size.toByte()))
        out.write(hostBytes)
        out.write(byteArrayOf((port shr 8).toByte(), port.toByte()))
        out.flush()

        val replyHeader = ByteArray(4).also { din.readFully(it) }
        if (replyHeader[1].toInt() != 0) { sock.close(); throw java.io.IOException("upstream CONNECT failed: code ${replyHeader[1]}") }
        val addrLen = when (replyHeader[3].toInt()) {
            1 -> 4
            4 -> 16
            3 -> din.readUnsignedByte()
            else -> 0
        }
        if (replyHeader[3].toInt() == 3) {
            ByteArray(addrLen).also { din.readFully(it) }
        } else {
            ByteArray(addrLen).also { din.readFully(it) }
        }
        ByteArray(2).also { din.readFully(it) } 
        return sock
    }

    private fun dialViaHttpConnect(host: String, port: Int, settings: VpshSettings): Socket {
        val sock = Socket().apply { connect(InetSocketAddress(settings.upstreamHost, settings.upstreamPort), CONNECT_TIMEOUT_MS) }
        val out = sock.getOutputStream()
        val sb = StringBuilder()
        sb.append("CONNECT $host:$port HTTP/1.1\r\n")
        sb.append("Host: $host:$port\r\n")
        if (settings.upstreamUser.isNotBlank()) {
            val token = Base64.encodeToString("${settings.upstreamUser}:${settings.upstreamPass}".toByteArray(), Base64.NO_WRAP)
            sb.append("Proxy-Authorization: Basic $token\r\n")
        }
        sb.append("Connection: keep-alive\r\n\r\n")
        out.write(sb.toString().toByteArray())
        out.flush()

        val input = sock.getInputStream()
        val statusLine = readLine(input)
        if (!statusLine.contains(" 200 ")) { sock.close(); throw java.io.IOException("upstream CONNECT failed: $statusLine") }
        
        while (true) {
            val line = readLine(input)
            if (line.isEmpty()) break
        }
        return sock
    }

    private fun readLine(input: java.io.InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c == -1 || c == '\n'.code) break
            if (c != '\r'.code) sb.append(c.toChar())
        }
        return sb.toString()
    }
}
