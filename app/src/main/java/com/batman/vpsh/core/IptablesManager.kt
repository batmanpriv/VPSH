package com.batman.vpsh.core

class IptablesManager(private val shell: RootShell) {

    companion object {
        const val CHAIN = "VPSH_NAT"
        const val FWD_CHAIN = "VPSH_MANGLE"
        const val ROUTE_TABLE = 61
        const val RULE_PRIORITY = 100
        private const val HTB_ROOT_HANDLE = "1:"
        private const val HTB_DEFAULT_CLASS = "9999"
        private const val HTB_GAME_CLASS = "7777"
        private const val CLIENT_LIMIT_FILTER_PRIO = 2
        private const val GAME_FILTER_PRIO = 1
    }

    private var savedRpFilterAll: String? = null
    private var savedRpFilterDefault: String? = null
    private val savedRpFilterPerIface = mutableMapOf<String, String>()

    private val tcClassIds = mutableMapOf<String, Int>()
    private var nextTcClassId = 2
    private var tcRootReady = false
    private var gameModeReady = false

    fun enableForwarding(): Boolean =
        shell.run("echo 1 > /proc/sys/net/ipv4/ip_forward").exitCode == 0

    fun disableHardwareOffload() {
        shell.run("settings put global tether_offload_disabled 1")
        shell.run("cmd connectivity tethering set-tether-offload-disabled 1")
    }

    fun setupNat(vpnIface: String, hotspotIface: String, blockIpv6: Boolean): Boolean {
        enableForwarding()
        disableHardwareOffload()

        shell.run("iptables -t nat -N $CHAIN 2>/dev/null")
        shell.run("iptables -t nat -F $CHAIN")
        shell.run("iptables -t nat -D POSTROUTING -j $CHAIN 2>/dev/null")
        shell.run("iptables -t nat -A POSTROUTING -j $CHAIN")
        shell.run("iptables -t nat -A $CHAIN -o $vpnIface -j MASQUERADE")

        shell.run("iptables -N $FWD_CHAIN 2>/dev/null")
        shell.run("iptables -F $FWD_CHAIN")
        shell.run("iptables -D FORWARD -j $FWD_CHAIN 2>/dev/null")
        shell.run("iptables -I FORWARD -j $FWD_CHAIN")
        shell.run("iptables -A $FWD_CHAIN -i $hotspotIface -o $vpnIface -j ACCEPT")
        shell.run("iptables -A $FWD_CHAIN -i $vpnIface -o $hotspotIface -m state --state ESTABLISHED,RELATED -j ACCEPT")

        if (blockIpv6) {
            shell.run("ip6tables -I FORWARD -i $hotspotIface -j DROP 2>/dev/null")
        }
        val check = shell.run("iptables -t nat -L $CHAIN -n")
        return check.exitCode == 0
    }

    fun setupRoutingRule(
        vpnIface: String,
        hotspotIface: String,
        table: Int = ROUTE_TABLE,
        priority: Int = RULE_PRIORITY
    ): Boolean {
        loosenReversePathFilter(hotspotIface, vpnIface)

        shell.run("ip route flush table $table 2>/dev/null")
        val routeRes = shell.run("ip route add default dev $vpnIface table $table")

        shell.run("ip rule del priority $priority 2>/dev/null")
        val ruleRes = shell.run("ip rule add from all iif $hotspotIface lookup $table priority $priority")

        return routeRes.exitCode == 0 && ruleRes.exitCode == 0
    }

    private fun loosenReversePathFilter(hotspotIface: String, vpnIface: String) {
        if (savedRpFilterAll == null) {
            savedRpFilterAll = readSysctl("net.ipv4.conf.all.rp_filter")
            savedRpFilterDefault = readSysctl("net.ipv4.conf.default.rp_filter")
        }
        for (iface in listOf(hotspotIface, vpnIface)) {
            if (!savedRpFilterPerIface.containsKey(iface)) {
                savedRpFilterPerIface[iface] = readSysctl("net.ipv4.conf.$iface.rp_filter") ?: "1"
            }
        }
        shell.run("sysctl -w net.ipv4.conf.all.rp_filter=2 2>/dev/null")
        shell.run("sysctl -w net.ipv4.conf.default.rp_filter=2 2>/dev/null")
        shell.run("sysctl -w net.ipv4.conf.$hotspotIface.rp_filter=2 2>/dev/null")
        shell.run("sysctl -w net.ipv4.conf.$vpnIface.rp_filter=2 2>/dev/null")
    }

    private fun readSysctl(key: String): String? {
        val res = shell.run("sysctl -n $key 2>/dev/null")
        return res.output.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun restoreReversePathFilter() {
        savedRpFilterAll?.let { shell.run("sysctl -w net.ipv4.conf.all.rp_filter=$it 2>/dev/null") }
        savedRpFilterDefault?.let { shell.run("sysctl -w net.ipv4.conf.default.rp_filter=$it 2>/dev/null") }
        savedRpFilterPerIface.forEach { (iface, value) ->
            shell.run("sysctl -w net.ipv4.conf.$iface.rp_filter=$value 2>/dev/null")
        }
        savedRpFilterAll = null
        savedRpFilterDefault = null
        savedRpFilterPerIface.clear()
    }

    fun teardown(hotspotIface: String, vpnIface: String = "", table: Int = ROUTE_TABLE, priority: Int = RULE_PRIORITY) {
        shell.run("iptables -t nat -D POSTROUTING -j $CHAIN 2>/dev/null")
        shell.run("iptables -t nat -F $CHAIN 2>/dev/null")
        shell.run("iptables -t nat -X $CHAIN 2>/dev/null")
        shell.run("iptables -D FORWARD -j $FWD_CHAIN 2>/dev/null")
        shell.run("iptables -F $FWD_CHAIN 2>/dev/null")
        shell.run("iptables -X $FWD_CHAIN 2>/dev/null")
        shell.run("ip6tables -D FORWARD -i $hotspotIface -j DROP 2>/dev/null")
        shell.run("settings put global tether_offload_disabled 0")

        shell.run("ip rule del priority $priority 2>/dev/null")
        shell.run("ip route flush table $table 2>/dev/null")
        restoreReversePathFilter()
        clearAllClientLimits(hotspotIface)
    }

    fun isNatIntact(): Boolean {
        val res = shell.run("iptables -t nat -L POSTROUTING -n | grep -c $CHAIN")
        val count = res.output.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        return res.exitCode == 0 && count > 0
    }

    fun isRoutingIntact(vpnIface: String, table: Int = ROUTE_TABLE, priority: Int = RULE_PRIORITY): Boolean {
        val ruleRes = shell.run("ip rule show | grep -c 'lookup $table'")
        val ruleCount = ruleRes.output.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        val routeRes = shell.run("ip route show table $table")
        val hasRoute = routeRes.output.any { it.contains("dev $vpnIface") }
        return ruleCount > 0 && hasRoute
    }

    private fun ensureTcRoot(hotspotIface: String) {
        if (tcRootReady) return
        shell.run("tc qdisc del dev $hotspotIface root 2>/dev/null")
        val add = shell.run("tc qdisc add dev $hotspotIface root handle $HTB_ROOT_HANDLE htb default $HTB_DEFAULT_CLASS")
        shell.run("tc class add dev $hotspotIface parent $HTB_ROOT_HANDLE classid $HTB_ROOT_HANDLE$HTB_DEFAULT_CLASS htb rate 1000mbit ceil 1000mbit")
        tcRootReady = add.exitCode == 0
    }

    fun applyClientLimit(hotspotIface: String, clientIp: String, kbps: Int): Boolean {
        if (kbps <= 0) {
            clearClientLimit(hotspotIface, clientIp)
            return true
        }
        ensureTcRoot(hotspotIface)
        if (!tcRootReady) return false

        val classId = tcClassIds.getOrPut(clientIp) { nextTcClassId++ }
        val classHex = classId.toString(16)
        shell.run("tc class del dev $hotspotIface classid $HTB_ROOT_HANDLE$classHex 2>/dev/null")
        val classRes = shell.run(
            "tc class add dev $hotspotIface parent $HTB_ROOT_HANDLE classid $HTB_ROOT_HANDLE$classHex " +
                "htb rate ${kbps}kbit ceil ${kbps}kbit"
        )
        shell.run("tc filter del dev $hotspotIface parent $HTB_ROOT_HANDLE protocol ip prio $CLIENT_LIMIT_FILTER_PRIO u32 match ip dst $clientIp/32 2>/dev/null")
        val filterRes = shell.run(
            "tc filter add dev $hotspotIface parent $HTB_ROOT_HANDLE protocol ip prio $CLIENT_LIMIT_FILTER_PRIO u32 " +
                "match ip dst $clientIp/32 flowid $HTB_ROOT_HANDLE$classHex"
        )
        return classRes.exitCode == 0 && filterRes.exitCode == 0
    }

    fun clearClientLimit(hotspotIface: String, clientIp: String) {
        val classId = tcClassIds.remove(clientIp) ?: return
        val classHex = classId.toString(16)
        shell.run("tc filter del dev $hotspotIface parent $HTB_ROOT_HANDLE protocol ip prio $CLIENT_LIMIT_FILTER_PRIO u32 match ip dst $clientIp/32 2>/dev/null")
        shell.run("tc class del dev $hotspotIface classid $HTB_ROOT_HANDLE$classHex 2>/dev/null")
    }

    fun clearAllClientLimits(hotspotIface: String) {
        shell.run("tc qdisc del dev $hotspotIface root 2>/dev/null")
        tcClassIds.clear()
        nextTcClassId = 2
        tcRootReady = false
        gameModeReady = false
    }

    fun enableGameMode(hotspotIface: String): Boolean {
        ensureTcRoot(hotspotIface)
        if (!tcRootReady) return false

        shell.run("tc class del dev $hotspotIface classid $HTB_ROOT_HANDLE$HTB_GAME_CLASS 2>/dev/null")
        val classRes = shell.run(
            "tc class add dev $hotspotIface parent $HTB_ROOT_HANDLE classid $HTB_ROOT_HANDLE$HTB_GAME_CLASS " +
                "htb rate 2mbit ceil 1000mbit prio 0"
        )
        shell.run("tc qdisc del dev $hotspotIface parent $HTB_ROOT_HANDLE$HTB_GAME_CLASS 2>/dev/null")
        shell.run("tc qdisc add dev $hotspotIface parent $HTB_ROOT_HANDLE$HTB_GAME_CLASS handle ${HTB_GAME_CLASS}0: sfq perturb 10")

        shell.run("tc filter del dev $hotspotIface parent $HTB_ROOT_HANDLE protocol ip prio $GAME_FILTER_PRIO u32 match ip protocol 17 0xff 2>/dev/null")
        val filterRes = shell.run(
            "tc filter add dev $hotspotIface parent $HTB_ROOT_HANDLE protocol ip prio $GAME_FILTER_PRIO u32 " +
                "match ip protocol 17 0xff flowid $HTB_ROOT_HANDLE$HTB_GAME_CLASS"
        )
        gameModeReady = classRes.exitCode == 0 && filterRes.exitCode == 0
        return gameModeReady
    }

    fun disableGameMode(hotspotIface: String) {
        shell.run("tc filter del dev $hotspotIface parent $HTB_ROOT_HANDLE protocol ip prio $GAME_FILTER_PRIO u32 match ip protocol 17 0xff 2>/dev/null")
        shell.run("tc qdisc del dev $hotspotIface parent $HTB_ROOT_HANDLE$HTB_GAME_CLASS 2>/dev/null")
        shell.run("tc class del dev $hotspotIface classid $HTB_ROOT_HANDLE$HTB_GAME_CLASS 2>/dev/null")
        gameModeReady = false
    }
}
