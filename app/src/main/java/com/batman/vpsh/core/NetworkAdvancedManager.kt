package com.batman.vpsh.core

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address
import java.net.NetworkInterface

data class IfaceDetail(
    val name: String,
    val ip: String?,
    val prefix: Int?,
    val mac: String?,
    val mtu: Int?,
    val state: String?,
    val rxBytes: Long?,
    val txBytes: Long?
)

data class IptablesRuleLine(val lineNumber: Int, val raw: String)
data class IptablesChain(val name: String, val policy: String?, val rules: List<IptablesRuleLine>)
data class ShellRunResult(val success: Boolean, val output: List<String>)

class NetworkAdvancedManager(private val shell: RootShell) {

    companion object {
        val IPTABLES_TABLES = listOf("filter", "nat", "mangle")

        fun listInterfacesNonRoot(): List<IfaceDetail> {
            val result = mutableListOf<IfaceDetail>()
            try {
                val ifaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
                for (iface in ifaces) {
                    val addr = iface.interfaceAddresses.firstOrNull { it.address is Inet4Address }
                    val mac = try {
                        iface.hardwareAddress?.joinToString(":") { b -> "%02x".format(b) }
                    } catch (_: Exception) { null }
                    result.add(
                        IfaceDetail(
                            name = iface.name,
                            ip = (addr?.address as? Inet4Address)?.hostAddress,
                            prefix = addr?.networkPrefixLength?.toInt(),
                            mac = mac,
                            mtu = try { iface.mtu } catch (_: Exception) { null },
                            state = try { if (iface.isUp) "UP" else "DOWN" } catch (_: Exception) { null },
                            rxBytes = null,
                            txBytes = null
                        )
                    )
                }
            } catch (_: Exception) { }
            return result
        }

        fun getDefaultGateway(context: Context): String? = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val lp = network?.let { cm.getLinkProperties(it) }
            lp?.routes?.firstOrNull { it.isDefaultRoute }?.gateway?.hostAddress
        } catch (_: Exception) { null }

        fun getDnsServers(context: Context): List<String> = try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val lp = network?.let { cm.getLinkProperties(it) }
            lp?.dnsServers?.mapNotNull { it.hostAddress } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        fun tryNonRootShellCommand(command: String): ShellRunResult = try {
            val p = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines()
            val code = p.waitFor()
            ShellRunResult(code == 0 && out.isNotEmpty(), out)
        } catch (_: Exception) {
            ShellRunResult(false, emptyList())
        }
    }

    fun listInterfacesRooted(): List<IfaceDetail> {
        val names = shell.run("ls /sys/class/net 2>/dev/null").output
            .flatMap { it.trim().split(Regex("\\s+")) }
            .filter { it.isNotBlank() }
            .ifEmpty { return listInterfacesNonRoot() }

        val addrOutput = shell.run("ip -o -4 addr show 2>/dev/null").output
        val ipByIface = mutableMapOf<String, Pair<String, Int>>()
        for (line in addrOutput) {
            val m = Regex("""^\d+:\s+(\S+)\s+inet\s+([\d.]+)/(\d+)""").find(line.trim()) ?: continue
            ipByIface[m.groupValues[1]] = m.groupValues[2] to (m.groupValues[3].toIntOrNull() ?: 0)
        }

        return names.map { name ->
            val mac = shell.run("cat /sys/class/net/$name/address 2>/dev/null").output.firstOrNull()?.trim()
            val mtu = shell.run("cat /sys/class/net/$name/mtu 2>/dev/null").output.firstOrNull()?.trim()?.toIntOrNull()
            val state = shell.run("cat /sys/class/net/$name/operstate 2>/dev/null").output.firstOrNull()?.trim()
            val rx = shell.run("cat /sys/class/net/$name/statistics/rx_bytes 2>/dev/null").output.firstOrNull()?.trim()?.toLongOrNull()
            val tx = shell.run("cat /sys/class/net/$name/statistics/tx_bytes 2>/dev/null").output.firstOrNull()?.trim()?.toLongOrNull()
            val ipInfo = ipByIface[name]
            IfaceDetail(name, ipInfo?.first, ipInfo?.second, mac?.takeIf { it.isNotBlank() }, mtu, state, rx, tx)
        }
    }

    fun listIptablesRules(table: String): List<IptablesChain> {
        val res = shell.run("iptables -t $table -L -n -v --line-numbers 2>&1")
        val chains = mutableListOf<IptablesChain>()
        var name: String? = null
        var policy: String? = null
        var rules = mutableListOf<IptablesRuleLine>()
        fun flush() { name?.let { chains.add(IptablesChain(it, policy, rules.toList())) } }

        for (raw in res.output) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("Chain ")) {
                flush()
                val m = Regex("""^Chain (\S+)(?:\s+\(policy (\S+))?""").find(line)
                name = m?.groupValues?.getOrNull(1) ?: line.removePrefix("Chain ").substringBefore(" ")
                policy = m?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
                rules = mutableListOf()
                continue
            }
            if (line.startsWith("num ") || line.startsWith("pkts ")) continue
            val ruleMatch = Regex("""^(\d+)\s+(.*)$""").find(line) ?: continue
            val lineNo = ruleMatch.groupValues[1].toIntOrNull() ?: continue
            rules.add(IptablesRuleLine(lineNo, ruleMatch.groupValues[2]))
        }
        flush()
        return chains
    }

    fun deleteIptablesRule(table: String, chain: String, lineNumber: Int): Boolean =
        shell.run("iptables -t $table -D $chain $lineNumber 2>&1").exitCode == 0

    fun appendIptablesRule(table: String, chain: String, ruleArgs: String): ShellRunResult {
        val res = shell.run("iptables -t $table -A $chain $ruleArgs 2>&1")
        return ShellRunResult(res.exitCode == 0, res.output)
    }

    fun runRawIptables(argsAfterIptables: String): ShellRunResult {
        val res = shell.run("iptables $argsAfterIptables 2>&1")
        return ShellRunResult(res.exitCode == 0, res.output)
    }

    fun listIpRules(): List<String> = shell.run("ip rule show 2>&1").output

    fun listIpRoutes(table: String? = null): List<String> {
        val cmd = if (table.isNullOrBlank()) "ip route show 2>&1" else "ip route show table $table 2>&1"
        return shell.run(cmd).output
    }

    fun runRawIpCommand(argsAfterIp: String): ShellRunResult {
        val res = shell.run("ip $argsAfterIp 2>&1")
        return ShellRunResult(res.exitCode == 0, res.output)
    }
}
