package com.batman.vpsh.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class UpstreamProfile(
    val name: String,
    val type: UpstreamType,
    val host: String,
    val port: Int,
    val user: String = "",
    val pass: String = ""
)

class UpstreamProfiles(private val context: Context) {

    private val KEY = stringPreferencesKey("upstream_profiles_json")

    val profilesFlow: Flow<List<UpstreamProfile>> = context.dataStore.data.map { p -> parse(p[KEY] ?: "[]") }

    suspend fun current(): List<UpstreamProfile> = profilesFlow.first()

    suspend fun save(profile: UpstreamProfile) {
        val list = current().filterNot { it.name == profile.name } + profile
        context.dataStore.edit { p -> p[KEY] = serialize(list) }
    }

    suspend fun delete(name: String) {
        val list = current().filterNot { it.name == name }
        context.dataStore.edit { p -> p[KEY] = serialize(list) }
    }

    private fun parse(json: String): List<UpstreamProfile> {
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                UpstreamProfile(
                    name = o.getString("name"),
                    type = try { UpstreamType.valueOf(o.optString("type", "SOCKS5")) } catch (_: Exception) { UpstreamType.SOCKS5 },
                    host = o.optString("host", ""),
                    port = o.optInt("port", 1080),
                    user = o.optString("user", ""),
                    pass = o.optString("pass", "")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun serialize(list: List<UpstreamProfile>): String {
        val arr = JSONArray()
        list.forEach { pr ->
            arr.put(JSONObject().apply {
                put("name", pr.name)
                put("type", pr.type.name)
                put("host", pr.host)
                put("port", pr.port)
                put("user", pr.user)
                put("pass", pr.pass)
            })
        }
        return arr.toString()
    }
}
