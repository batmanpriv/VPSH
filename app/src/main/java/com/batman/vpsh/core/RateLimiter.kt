package com.batman.vpsh.core

class RateLimiter(kbps: Int) {
    private val bytesPerSecond: Long = (kbps.toLong() * 1000L) / 8L
    private var tokens: Long = bytesPerSecond
    private var lastRefillNs: Long = System.nanoTime()
    private val lock = Object()

    fun acquire(n: Int) {
        if (bytesPerSecond <= 0 || n <= 0) return
        var remaining = n.toLong()
        while (remaining > 0) {
            val take = synchronized(lock) {
                refill()
                val grant = minOf(tokens, remaining)
                tokens -= grant
                grant
            }
            remaining -= take
            if (remaining > 0) {
                
                try { Thread.sleep(20) } catch (_: InterruptedException) { return }
            }
        }
    }

    private fun refill() {
        val now = System.nanoTime()
        val elapsedNs = now - lastRefillNs
        if (elapsedNs <= 0) return
        val added = (elapsedNs * bytesPerSecond) / 1_000_000_000L
        if (added > 0) {
            tokens = minOf(bytesPerSecond, tokens + added)
            lastRefillNs = now
        }
    }
}
