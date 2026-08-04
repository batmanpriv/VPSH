package com.batman.vpsh.core.batproxy

import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object BatAuth {
    private val random = SecureRandom()

    fun hmacHex(password: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(password.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val sig = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        return sig.joinToString("") { "%02x".format(it) }
    }

    fun makeToken(password: String, subject: String): JSONObject {
        val ts = (System.currentTimeMillis() / 1000).toString()
        val nonceBytes = ByteArray(8).also { random.nextBytes(it) }
        val nonce = nonceBytes.joinToString("") { "%02x".format(it) }
        val sig = hmacHex(password, "$subject:$ts:$nonce")
        return JSONObject().apply {
            put("ts", ts)
            put("nonce", nonce)
            put("sig", sig)
        }
    }
}
