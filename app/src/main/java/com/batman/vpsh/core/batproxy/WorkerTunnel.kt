package com.batman.vpsh.core.batproxy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

class WorkerTunnel private constructor() {

    sealed class Frame {
        data class Text(val text: String) : Frame()
        data class Binary(val bytes: ByteArray) : Frame()
        object Closed : Frame()
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closed = false
    
    private val inbox = Channel<Frame>(Channel.UNLIMITED)

    fun sendText(text: String) {
        if (!closed) ws?.send(text)
    }

    fun sendBytes(bytes: ByteArray) {
        if (!closed) ws?.send(ByteString.of(*bytes))
    }

    suspend fun receive(timeoutMs: Long = 15000): Frame = withTimeout(timeoutMs) { inbox.receive() }

    fun close() {
        if (closed) return
        closed = true
        try {
            ws?.close(1000, "done")
        } catch (_: Exception) {
        }
        inbox.close()
    }

    private fun offer(frame: Frame) {
        inbox.trySend(frame)
    }

    companion object {
        private val client = OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build()

        suspend fun connect(url: String, timeoutMs: Long = 6000): WorkerTunnel {
            val tunnel = WorkerTunnel()
            val opened = CompletableDeferred<Unit>()
            val request = Request.Builder().url(url).build()
            val ws = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    if (!opened.isCompleted) opened.complete(Unit)
                }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    tunnel.offer(Frame.Text(text))
                }
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    tunnel.offer(Frame.Binary(bytes.toByteArray()))
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    tunnel.offer(Frame.Closed)
                }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened.isCompleted) opened.completeExceptionally(t)
                    tunnel.offer(Frame.Closed)
                }
            })
            tunnel.ws = ws
            withTimeout(timeoutMs) { opened.await() }
            return tunnel
        }
    }
}
