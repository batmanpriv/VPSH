package com.batman.vpsh.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class HotspotBanEntry(
    val mac: String,
    val nickname: String = "",
    val lastIp: String = "",
    val bannedAtMs: Long = System.currentTimeMillis()
)

class HotspotBanRegistry(private val context: Context) {

    private val KEY = stringPreferencesKey("hotspot_ban_registry_json")

    val entriesFlow: Flow<Map<String, HotspotBanEntry>> = context.dataStore.data.map { p ->
        parse(p[KEY] ?: "[]")
    }

    suspend fun current(): Map<String, HotspotBanEntry> = entriesFlow.first()

    suspend fun ban(mac: String, nickname: String = "", lastIp: String = "") {
        val key = mac.lowercase()
        val entries = current().toMutableMap()
        val existing = entries[key]
        entries[key] = HotspotBanEntry(
            mac = key,
            nickname = nickname.ifBlank { existing?.nickname ?: "" },
            lastIp = lastIp.ifBlank { existing?.lastIp ?: "" },
            bannedAtMs = existing?.bannedAtMs ?: System.currentTimeMillis()
        )
        context.dataStore.edit { p -> p[KEY] = serialize(entries) }
    }

    suspend fun unban(mac: String) {
        val key = mac.lowercase()
        val entries = current().toMutableMap()
        entries.remove(key)
        context.dataStore.edit { p -> p[KEY] = serialize(entries) }
    }

    private fun parse(json: String): Map<String, HotspotBanEntry> {
        return try {
            val arr = JSONArray(json)
            val map = LinkedHashMap<String, HotspotBanEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val mac = o.getString("mac")
                map[mac] = HotspotBanEntry(
                    mac = mac,
                    nickname = o.optString("nickname", ""),
                    lastIp = o.optString("lastIp", ""),
                    bannedAtMs = o.optLong("bannedAtMs", System.currentTimeMillis())
                )
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serialize(entries: Map<String, HotspotBanEntry>): String {
        val arr = JSONArray()
        entries.values.forEach { e ->
            arr.put(JSONObject().apply {
                put("mac", e.mac)
                put("nickname", e.nickname)
                put("lastIp", e.lastIp)
                put("bannedAtMs", e.bannedAtMs)
            })
        }
        return arr.toString()
    }
}
