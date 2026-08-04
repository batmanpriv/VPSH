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

    fun snapshot(): List<ClientInfo> = clients.values.sortedByDescending { it.lastSeenMs }

    fun totalBytes(): Long = bytesPerClient.values.sum()

    fun refreshFromNeighTable(hotspotIface: String) {
        val sh = shellProvider() ?: return
        val res = sh.run("ip neigh show dev $hotspotIface")
        for (line in res.output) {
            
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.isEmpty()) continue
            val ip = parts[0]
            val macIdx = parts.indexOf("lladdr")
            val mac = if (macIdx in parts.indices.minus(parts.size - 1)) parts.getOrNull(macIdx + 1) ?: "" else ""
            clients.compute(ip) { _, existing ->
                (existing ?: ClientInfo(ip)).apply {
                    if (mac.isNotEmpty()) this.mac = mac
                    lastSeenMs = System.currentTimeMillis()
                }
            }
        }
    }

    fun clear() {
        clients.clear()
        bytesPerClient.clear()
    }
}
