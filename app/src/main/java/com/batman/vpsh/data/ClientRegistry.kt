package com.batman.vpsh.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class ClientEntry(
    val key: String,
    val nickname: String = "",
    val blocked: Boolean = false,
    
    val limitKbps: Int = 0
)

class ClientRegistry(private val context: Context) {

    private val KEY = stringPreferencesKey("client_registry_json")

    val entriesFlow: Flow<Map<String, ClientEntry>> = context.dataStore.data.map { p ->
        parse(p[KEY] ?: "[]")
    }

    suspend fun current(): Map<String, ClientEntry> = entriesFlow.first()

    suspend fun isBlocked(key: String): Boolean = current()[key]?.blocked ?: false

    suspend fun setNickname(key: String, nickname: String) = mutate(key) { it.copy(nickname = nickname) }

    suspend fun setBlocked(key: String, blocked: Boolean) = mutate(key) { it.copy(blocked = blocked) }

    suspend fun setLimit(key: String, kbps: Int) = mutate(key) { it.copy(limitKbps = kbps.coerceAtLeast(0)) }

    private suspend fun mutate(key: String, transform: (ClientEntry) -> ClientEntry) {
        val entries = current().toMutableMap()
        val existing = entries[key] ?: ClientEntry(key)
        entries[key] = transform(existing)
        context.dataStore.edit { p -> p[KEY] = serialize(entries) }
    }

    private fun parse(json: String): Map<String, ClientEntry> {
        return try {
            val arr = JSONArray(json)
            val map = LinkedHashMap<String, ClientEntry>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val key = o.getString("key")
                map[key] = ClientEntry(key, o.optString("nickname", ""), o.optBoolean("blocked", false), o.optInt("limitKbps", 0))
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun serialize(entries: Map<String, ClientEntry>): String {
        val arr = JSONArray()
        entries.values.forEach { e ->
            arr.put(JSONObject().apply {
                put("key", e.key)
                put("nickname", e.nickname)
                put("blocked", e.blocked)
                put("limitKbps", e.limitKbps)
            })
        }
        return arr.toString()
    }
}
