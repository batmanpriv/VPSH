package com.batman.vpsh.core

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

data class GuestAccess(
    val user: String,
    val pass: String,
    val createdAtMs: Long,
    val expiresAtMs: Long,
    val quotaBytes: Long,
    @Volatile var boundIp: String? = null
)

class GuestAccessManager(private val bytesForIp: (String) -> Long) {
    private val random = SecureRandom()
    private val guests = ConcurrentHashMap<String, GuestAccess>()

    fun create(ttlMinutes: Int, quotaMb: Int): GuestAccess {
        val user = "guest" + (1000 + random.nextInt(9000))
        val pass = (1..8).map { GUEST_ALPHABET[random.nextInt(GUEST_ALPHABET.length)] }.joinToString("")
        val now = System.currentTimeMillis()
        val entry = GuestAccess(
            user = user,
            pass = pass,
            createdAtMs = now,
            expiresAtMs = now + ttlMinutes.coerceAtLeast(1) * 60_000L,
            quotaBytes = if (quotaMb > 0) quotaMb.toLong() * 1024 * 1024 else 0L
        )
        guests[user] = entry
        return entry
    }

    fun revoke(user: String) {
        guests.remove(user)
    }

    fun clearAll() {
        guests.clear()
    }

    fun activeGuests(): List<GuestAccess> {
        sweep()
        return guests.values.sortedByDescending { it.createdAtMs }
    }

    fun checkAuth(user: String, pass: String, fromIp: String): Boolean {
        val g = guests[user] ?: return false
        if (g.pass != pass) return false
        if (isSpent(g)) {
            guests.remove(user)
            return false
        }
        if (g.boundIp == null) g.boundIp = fromIp
        return true
    }

    fun isIpRevoked(ip: String): Boolean {
        val g = guests.values.firstOrNull { it.boundIp == ip } ?: return false
        return isSpent(g)
    }

    private fun isSpent(g: GuestAccess): Boolean {
        if (System.currentTimeMillis() > g.expiresAtMs) return true
        if (g.quotaBytes > 0) {
            val ip = g.boundIp ?: return false
            if (bytesForIp(ip) > g.quotaBytes) return true
        }
        return false
    }

    private fun sweep() {
        val now = System.currentTimeMillis()

        guests.entries.removeIf { (_, g) -> now > g.expiresAtMs + 24 * 3_600_000L }
    }

    companion object {
        private const val GUEST_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"
    }
}
