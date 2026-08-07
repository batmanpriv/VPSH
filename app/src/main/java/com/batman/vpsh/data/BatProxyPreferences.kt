package com.batman.vpsh.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class BatWorker(val url: String, val password: String)

enum class SplitTunnelMode { OFF, EXCLUDE, INCLUDE_ONLY }

data class BatProxyConfig(
    val workers: List<BatWorker> = emptyList(),
    
    val dnsHost: String = "1.1.1.1",
    val dnsPort: Int = 53,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.OFF,
    val splitTunnelApps: Set<String> = emptySet()
)

class BatProxyPreferences(private val context: Context) {

    private object Keys {
        val WORKERS_JSON = stringPreferencesKey("batproxy_workers_json")
        val DNS_HOST = stringPreferencesKey("batproxy_dns_host")
        val DNS_PORT = intPreferencesKey("batproxy_dns_port")
        val SPLIT_MODE = stringPreferencesKey("batproxy_split_mode")
        val SPLIT_APPS = stringPreferencesKey("batproxy_split_apps")
    }

    val configFlow: Flow<BatProxyConfig> = context.dataStore.data.map { p ->
        BatProxyConfig(
            workers = parseWorkers(p[Keys.WORKERS_JSON] ?: "[]"),
            dnsHost = p[Keys.DNS_HOST] ?: "1.1.1.1",
            dnsPort = p[Keys.DNS_PORT] ?: 53,
            splitTunnelMode = when (p[Keys.SPLIT_MODE]) {
                "EXCLUDE" -> SplitTunnelMode.EXCLUDE
                "INCLUDE_ONLY" -> SplitTunnelMode.INCLUDE_ONLY
                else -> SplitTunnelMode.OFF
            },
            splitTunnelApps = (p[Keys.SPLIT_APPS] ?: "").split(",").filter { it.isNotBlank() }.toSet()
        )
    }

    suspend fun current(): BatProxyConfig = configFlow.first()

    suspend fun setSplitTunnel(mode: SplitTunnelMode, apps: Set<String>) {
        context.dataStore.edit { p ->
            p[Keys.SPLIT_MODE] = mode.name
            p[Keys.SPLIT_APPS] = apps.joinToString(",")
        }
    }

    suspend fun addWorker(worker: BatWorker) {
        val cfg = current()
        setWorkers(cfg.workers + worker)
    }

    suspend fun removeWorker(url: String) {
        val cfg = current()
        setWorkers(cfg.workers.filterNot { it.url == url })
    }

    suspend fun setWorkers(workers: List<BatWorker>) {
        context.dataStore.edit { p -> p[Keys.WORKERS_JSON] = serializeWorkers(workers) }
    }

    suspend fun setDns(host: String, port: Int) {
        context.dataStore.edit { p ->
            p[Keys.DNS_HOST] = host
            p[Keys.DNS_PORT] = port
        }
    }

    private fun parseWorkers(json: String): List<BatWorker> = try {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            BatWorker(o.getString("url"), o.optString("password", ""))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun serializeWorkers(workers: List<BatWorker>): String {
        val arr = JSONArray()
        workers.forEach { w ->
            arr.put(JSONObject().apply {
                put("url", w.url)
                put("password", w.password)
            })
        }
        return arr.toString()
    }
}
