package com.batman.vpsh.core

import android.util.Log
import com.batman.vpsh.data.VpshSettings
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class ShadowsocksServer(
    private val port: Int,
    password: String,
    private val settings: VpshSettings,
    private val onClientSeen: (String) -> Unit,
    private val onBytes: (String, Long) -> Unit,
    private val isBlocked: (String) -> Boolean = { false },
    private val limitKbpsFor: (String) -> Int = { 0 }
) {
    companion object {
        const val METHOD = "aes-256-gcm"
        private const val KEY_LEN = 32
        private const val SALT_LEN = 32
        private const val TAG_LEN = 16
        private const val NONCE_LEN = 12
        private const val MAX_CHUNK = 0x3FFF 

        fun buildUri(host: String, port: Int, password: String, tag: String = "VPSH"): String {
            val userInfo = android.util.Base64.encodeToString(
                "$METHOD:$password".toByteArray(Charsets.UTF_8),
                android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
            )
            val encodedTag = java.net.URLEncoder.encode(tag, "UTF-8")
            return "ss://$userInfo@$host:$port#$encodedTag"
        }
    }

    private val TAG = "VPSH-SS"
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private val masterKey: ByteArray = deriveKey(password, KEY_LEN)

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
        var remote: Socket? = null
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val clientSalt = ByteArray(SALT_LEN)
            readFully(input, clientSalt)
            val decKey = hkdfSha1(masterKey, clientSalt, "ss-subkey", KEY_LEN)
            val decoder = AeadChunkDecoder(decKey)

            val (host, targetPort, leftover) = decoder.readAddressHeader(input)

            val dialed = try {
                UpstreamDialer.dial(host, targetPort, settings)
            } catch (_: Exception) {
                client.close(); return
            }
            remote = dialed
            if (leftover.isNotEmpty()) {
                dialed.getOutputStream().write(leftover)
                dialed.getOutputStream().flush()
                onBytes(clientIp, leftover.size.toLong())
            }

            val serverSalt = ByteArray(SALT_LEN).also { SecureRandom().nextBytes(it) }
            output.write(serverSalt); output.flush()
            val encKey = hkdfSha1(masterKey, serverSalt, "ss-subkey", KEY_LEN)
            val encoder = AeadChunkEncoder(encKey)

            val t1 = Thread { relayClientToRemote(input, dialed, decoder, limiter, clientIp) }
            val t2 = Thread { relayRemoteToClient(dialed, output, encoder, limiter, clientIp) }
            t1.start(); t2.start()
            t1.join(); t2.join()
        } catch (_: Exception) {
        } finally {
            try { client.close() } catch (_: Exception) { }
            try { remote?.close() } catch (_: Exception) { }
        }
    }

    private fun relayClientToRemote(
        input: InputStream, remote: Socket, decoder: AeadChunkDecoder, limiter: RateLimiter?, clientIp: String
    ) {
        try {
            val out = remote.getOutputStream()
            while (true) {
                val chunk = decoder.readChunk(input) ?: break
                if (chunk.isEmpty()) continue
                limiter?.acquire(chunk.size)
                out.write(chunk); out.flush()
                onBytes(clientIp, chunk.size.toLong())
            }
        } catch (_: Exception) {
        } finally {
            try { remote.shutdownOutput() } catch (_: Exception) { }
        }
    }

    private fun relayRemoteToClient(
        remote: Socket, output: java.io.OutputStream, encoder: AeadChunkEncoder, limiter: RateLimiter?, clientIp: String
    ) {
        try {
            val buf = ByteArray(MAX_CHUNK)
            val input = remote.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n == -1) break
                limiter?.acquire(n)
                val encrypted = encoder.encryptChunk(buf, n)
                output.write(encrypted); output.flush()
                onBytes(clientIp, n.toLong())
            }
        } catch (_: Exception) {
        } finally {
            try { output.close() } catch (_: Exception) { }
        }
    }

    private fun readFully(input: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n == -1) throw IOException("stream closed while reading ${buf.size} bytes")
            off += n
        }
    }

    private fun deriveKey(password: String, keyLen: Int): ByteArray {
        val pass = password.toByteArray(Charsets.UTF_8)
        val md5 = MessageDigest.getInstance("MD5")
        val out = ByteArray(keyLen)
        var generated = 0
        var prev = ByteArray(0)
        while (generated < keyLen) {
            md5.reset()
            md5.update(prev)
            md5.update(pass)
            prev = md5.digest()
            val take = minOf(prev.size, keyLen - generated)
            System.arraycopy(prev, 0, out, generated, take)
            generated += take
        }
        return out
    }

    private fun hkdfSha1(key: ByteArray, salt: ByteArray, info: String, outLen: Int): ByteArray {
        val extractMac = Mac.getInstance("HmacSHA1")
        extractMac.init(SecretKeySpec(salt, "HmacSHA1"))
        val prk = extractMac.doFinal(key)

        val expandMac = Mac.getInstance("HmacSHA1")
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        val hashLen = expandMac.macLength
        val n = (outLen + hashLen - 1) / hashLen
        var t = ByteArray(0)
        val okm = ByteArrayOutputStream()
        for (i in 1..n) {
            expandMac.init(SecretKeySpec(prk, "HmacSHA1"))
            expandMac.update(t)
            expandMac.update(infoBytes)
            expandMac.update(i.toByte())
            t = expandMac.doFinal()
            okm.write(t)
        }
        return okm.toByteArray().copyOf(outLen)
    }

    private class NonceCounter {
        private val bytes = ByteArray(NONCE_LEN)
        fun current(): ByteArray = bytes.copyOf()
        fun increment() {
            for (i in bytes.indices) {
                val v = (bytes[i].toInt() and 0xFF) + 1
                bytes[i] = v.toByte()
                if (v <= 0xFF) break
            }
        }
    }

    private class AeadChunkDecoder(key: ByteArray) {
        private val keySpec = SecretKeySpec(key, "AES")
        private val nonce = NonceCounter()

        private fun open(cipherText: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, keySpec, GCMParameterSpec(TAG_LEN * 8, nonce.current()))
            nonce.increment()
            return cipher.doFinal(cipherText)
        }

        fun readChunk(input: InputStream): ByteArray? {
            val lenCipher = ByteArray(2 + TAG_LEN)
            if (!tryReadFully(input, lenCipher)) return null
            val lenPlain = open(lenCipher)
            val len = (((lenPlain[0].toInt() and 0xFF) shl 8) or (lenPlain[1].toInt() and 0xFF)) and MAX_CHUNK
            val payloadCipher = ByteArray(len + TAG_LEN)
            readFullyOrThrow(input, payloadCipher)
            return open(payloadCipher)
        }

        fun readAddressHeader(input: InputStream): Triple<String, Int, ByteArray> {
            var buf = readChunk(input) ?: throw IOException("connection closed before address header")
            var pos = 0
            fun ensure(n: Int) {
                while (buf.size - pos < n) {
                    val more = readChunk(input) ?: throw IOException("truncated address header")
                    buf += more
                }
            }
            ensure(1)
            val atyp = buf[pos].toInt() and 0xFF; pos += 1
            val host: String
            when (atyp) {
                1 -> {
                    ensure(4)
                    host = InetAddress.getByAddress(buf.copyOfRange(pos, pos + 4)).hostAddress
                    pos += 4
                }
                3 -> {
                    ensure(1)
                    val len = buf[pos].toInt() and 0xFF; pos += 1
                    ensure(len)
                    host = String(buf, pos, len, Charsets.UTF_8); pos += len
                }
                4 -> {
                    ensure(16)
                    host = InetAddress.getByAddress(buf.copyOfRange(pos, pos + 16)).hostAddress
                    pos += 16
                }
                else -> throw IOException("unsupported ATYP $atyp")
            }
            ensure(2)
            val port = ((buf[pos].toInt() and 0xFF) shl 8) or (buf[pos + 1].toInt() and 0xFF)
            pos += 2
            return Triple(host, port, buf.copyOfRange(pos, buf.size))
        }

        private fun tryReadFully(input: InputStream, buf: ByteArray): Boolean {
            var off = 0
            while (off < buf.size) {
                val n = input.read(buf, off, buf.size - off)
                if (n == -1) return if (off == 0) false else throw IOException("truncated AEAD chunk")
                off += n
            }
            return true
        }

        private fun readFullyOrThrow(input: InputStream, buf: ByteArray) {
            if (!tryReadFully(input, buf)) throw IOException("connection closed mid-chunk")
        }
    }

    private class AeadChunkEncoder(key: ByteArray) {
        private val keySpec = SecretKeySpec(key, "AES")
        private val nonce = NonceCounter()

        private fun seal(plain: ByteArray, offset: Int, len: Int): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, GCMParameterSpec(TAG_LEN * 8, nonce.current()))
            nonce.increment()
            return cipher.doFinal(plain, offset, len)
        }

        fun encryptChunk(plain: ByteArray, len: Int): ByteArray {
            var offset = 0
            val out = ByteArrayOutputStream()
            while (offset < len) {
                val take = minOf(MAX_CHUNK, len - offset)
                val lenBytes = byteArrayOf((take shr 8).toByte(), take.toByte())
                out.write(seal(lenBytes, 0, 2))
                out.write(seal(plain, offset, take))
                offset += take
            }
            return out.toByteArray()
        }
    }
}
