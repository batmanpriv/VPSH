package com.batman.vpsh.core

class IptablesManager(private val shell: RootShell) {

    companion object {
        const val CHAIN = "VPSH_NAT"
        const val FWD_CHAIN = "VPSH_MANGLE"
        const val ACCT_CHAIN = "VPSH_ACCT"
        const val ROUTE_TABLE = 61
        const val RULE_PRIORITY = 100
        private const val HTB_ROOT_HANDLE = "1:"
        private const val HTB_DEFAULT_CLASS = "9999"
        private const val HTB_GAME_CLASS = "7777"
        private const val CLIENT_LIMIT_FILTER_PRIO = 2
        private const val GAME_FILTER_PRIO = 1

        private val IPA_SERVICE_NAMES = listOf("vendor.ipacm", "ipacm")
    }

    private var savedRpFilterAll: String? = null
    private var savedRpFilterDefault: String? = null
    private val savedRpFilterPerIface = mutableMapOf<String, String>()

    private val tcClassIds = mutableMapOf<String, Int>()
    private var nextTcClassId = 2
    private var tcRootReady = false
    private var gameModeReady = false

    private var ipaOffloadStopped = false
    private var acctChainReady = false
    private val acctedIps = mutableSetOf<String>()

    var ipaHardwarePresent: Boolean = false
        private set

    var ipaStopWasUnsafe: Boolean = false
        private set

    fun enableForwarding(): Boolean =
        shell.run("echo 1 > /proc/sys/net/ipv4/ip_forward").exitCode == 0

    fun hasIpaOffloadCapability(): Boolean {
        val res = shell.run("ls /sys/class/net 2>/dev/null")
        val present = res.output.any { it.trim().startsWith("rmnet_ipa") }
        ipaHardwarePresent = present
        return present
    }

    private fun isIpaDaemonRunning(): Boolean {
        val res = shell.run(
            "(pgrep -f ipacm || ps -A | grep ipacm || ps | grep ipacm) 2>/dev/null | grep -v grep | grep -v diag"
        )
        return res.output.any { it.trim().isNotEmpty() }
    }

    private fun stopIpaDaemon(): Boolean {
        for (name in IPA_SERVICE_NAMES) shell.run("stop $name 2>/dev/null")
        Thread.sleep(300)
        if (!isIpaDaemonRunning()) return true

        for (name in IPA_SERVICE_NAMES) shell.run("stop $name 2>/dev/null")
        Thread.sleep(700)
        return !isIpaDaemonRunning()
    }

    private fun startIpaDaemon() {
        for (name in IPA_SERVICE_NAMES) shell.run("start $name 2>/dev/null")
    }

    private fun hasInternet(): Boolean {
        val res = shell.run("ping -c 1 -W 2 1.1.1.1 2>/dev/null")
        return res.exitCode == 0
    }

    fun disableHardwareOffload(forceStopIpa: Boolean): Boolean {
        shell.run("settings put global tether_offload_disabled 1")
        val cmdRes = shell.run("cmd connectivity tethering set-tether-offload-disabled 1")

        hasIpaOffloadCapability()
        ipaStopWasUnsafe = false
        var ipaRes = true
        if (forceStopIpa && ipaHardwarePresent) {
            val hadInternetBefore = hasInternet()
            ipaOffloadStopped = stopIpaDaemon()
            ipaRes = ipaOffloadStopped
            if (ipaOffloadStopped && hadInternetBefore && !hasInternet()) {
                
                startIpaDaemon()
                ipaOffloadStopped = false
                ipaStopWasUnsafe = true
                ipaRes = false
            }
        }
        return cmdRes.exitCode == 0 || ipaRes
    }

    fun reassertOffloadDisabled() {
        if (!ipaOffloadStopped || ipaStopWasUnsafe) return
        if (isIpaDaemonRunning()) {
            val hadInternetBefore = hasInternet()
            val stopped = stopIpaDaemon()
            if (stopped && hadInternetBefore && !hasInternet()) {
                startIpaDaemon()
                ipaOffloadStopped = false
                ipaStopWasUnsafe = true
            }
        }
    }

    fun setupNat(vpnIface: String, hotspotIface: String, blockIpv6: Boolean, disableIpaOffload: Boolean = false): Boolean {
        enableForwarding()
        disableHardwareOffload(disableIpaOffload)

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
        if (ipaOffloadStopped) {
            startIpaDaemon()
            ipaOffloadStopped = false
        }
        ipaHardwarePresent = false
        ipaStopWasUnsafe = false

        shell.run("ip rule del priority $priority 2>/dev/null")
        shell.run("ip route flush table $table 2>/dev/null")
        restoreReversePathFilter()
        clearAllClientLimits(hotspotIface)
        clearAcctChain()
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

    fun isOffloadBypassing(): Boolean = ipaHardwarePresent && isIpaDaemonRunning()

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

    private fun ensureAcctChain() {
        if (acctChainReady) return
        shell.run("iptables -N $ACCT_CHAIN 2>/dev/null")
        val hasJump = shell.run("iptables -C FORWARD -j $ACCT_CHAIN 2>/dev/null").exitCode == 0
        if (!hasJump) shell.run("iptables -I FORWARD 1 -j $ACCT_CHAIN 2>/dev/null")
        acctChainReady = true
    }

    fun ensureClientAccounting(clientIp: String) {
        if (clientIp in acctedIps) return
        ensureAcctChain()
        val tag = "vpsh_${clientIp.replace('.', '_')}"
        shell.run("iptables -A $ACCT_CHAIN -s $clientIp -m comment --comment $tag -j RETURN")
        shell.run("iptables -A $ACCT_CHAIN -d $clientIp -m comment --comment $tag -j RETURN")
        acctedIps += clientIp
    }

    fun readClientBytes(): Map<String, Long> {
        if (!acctChainReady) return emptyMap()
        val res = shell.run("iptables -L $ACCT_CHAIN -n -v -x")
        val totals = mutableMapOf<String, Long>()
        val tagToIp = acctedIps.associateBy { "vpsh_${it.replace('.', '_')}" }
        for (line in res.output) {
            val cols = line.trim().split(Regex("\\s+"))
            if (cols.size < 2) continue
            val bytes = cols[1].toLongOrNull() ?: continue
            val tag = cols.firstOrNull { it.startsWith("vpsh_") } ?: continue
            val ip = tagToIp[tag] ?: continue
            totals[ip] = (totals[ip] ?: 0L) + bytes
        }
        return totals
    }

    fun clearAcctChain() {
        shell.run("iptables -D FORWARD -j $ACCT_CHAIN 2>/dev/null")
        shell.run("iptables -F $ACCT_CHAIN 2>/dev/null")
        shell.run("iptables -X $ACCT_CHAIN 2>/dev/null")
        acctChainReady = false
        acctedIps.clear()
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
