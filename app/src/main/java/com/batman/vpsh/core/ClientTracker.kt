package com.batman.vpsh.core

import java.util.concurrent.ConcurrentHashMap

data class ClientInfo(val ip: String, var mac: String = "", var lastSeenMs: Long = System.currentTimeMillis())

class ClientTracker(private val shellProvider: () -> RootShell?) {
    private val clients = ConcurrentHashMap<String, ClientInfo>()
    private val bytesPerClient = ConcurrentHashMap<String, Long>()

    fun markSeen(ip: String) {
        clients.compute(ip) { _, existing ->
            existing?.apply { lastSeenMs = System.currentTimeMillis() } ?: ClientInfo(ip)
        }
    }

    fun addBytes(ip: String, n: Long) {
        bytesPerClient.merge(ip, n) { a, b -> a + b }
    }

    fun setBytes(ip: String, absolute: Long) {
        bytesPerClient[ip] = absolute
    }

    fun snapshot(): List<ClientInfo> = clients.values.sortedByDescending { it.lastSeenMs }

    fun totalBytes(): Long = bytesPerClient.values.sum()

    fun bytesFor(ip: String): Long = bytesPerClient[ip] ?: 0L

    fun refreshFromNeighTable(hotspotIface: String) {
        val sh = shellProvider() ?: return
        val res = sh.run("ip neigh show dev $hotspotIface")
        val seenIps = mutableSetOf<String>()
        for (line in res.output) {
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.isEmpty() || parts[0].isBlank()) continue
            
            if (parts.contains("FAILED") || parts.contains("INCOMPLETE")) continue
            val ip = parts[0]
            seenIps += ip
            val macIdx = parts.indexOf("lladdr")
            val mac = if (macIdx in 0 until parts.size - 1) parts[macIdx + 1] else ""
            clients.compute(ip) { _, existing ->
                (existing ?: ClientInfo(ip)).apply {
                    if (mac.isNotEmpty()) this.mac = mac
                    lastSeenMs = System.currentTimeMillis()
                }
            }
        }
        val stale = clients.keys - seenIps
        for (ip in stale) clients.remove(ip)
    }

    fun clear() {
        clients.clear()
        bytesPerClient.clear()
    }
}
