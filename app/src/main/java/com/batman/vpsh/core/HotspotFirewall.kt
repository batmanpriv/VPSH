package com.batman.vpsh.core

class HotspotFirewall(private val shell: RootShell) {

    companion object {
        const val CHAIN_IN = "VPSH_MACBAN_IN"
        const val CHAIN_FWD = "VPSH_MACBAN_FWD"
    }

    private fun ensureChains(hotspotIface: String) {
        shell.run("iptables -N $CHAIN_IN 2>/dev/null")
        shell.run("iptables -D INPUT -i $hotspotIface -j $CHAIN_IN 2>/dev/null")
        shell.run("iptables -I INPUT -i $hotspotIface -j $CHAIN_IN")

        shell.run("iptables -N $CHAIN_FWD 2>/dev/null")
        shell.run("iptables -D FORWARD -i $hotspotIface -j $CHAIN_FWD 2>/dev/null")
        shell.run("iptables -I FORWARD -i $hotspotIface -j $CHAIN_FWD")
    }

    fun banMac(hotspotIface: String, mac: String): Boolean {
        ensureChains(hotspotIface)
        val m = mac.lowercase()
        shell.run("iptables -D $CHAIN_IN -m mac --mac-source $m -j DROP 2>/dev/null")
        val inRes = shell.run("iptables -A $CHAIN_IN -m mac --mac-source $m -j DROP")
        shell.run("iptables -D $CHAIN_FWD -m mac --mac-source $m -j DROP 2>/dev/null")
        val fwdRes = shell.run("iptables -A $CHAIN_FWD -m mac --mac-source $m -j DROP")
        return inRes.exitCode == 0 && fwdRes.exitCode == 0
    }

    fun unbanMac(mac: String) {
        val m = mac.lowercase()
        shell.run("iptables -D $CHAIN_IN -m mac --mac-source $m -j DROP 2>/dev/null")
        shell.run("iptables -D $CHAIN_FWD -m mac --mac-source $m -j DROP 2>/dev/null")
    }

    fun reapplyAll(hotspotIface: String, macs: Collection<String>) {
        if (macs.isEmpty()) return
        ensureChains(hotspotIface)
        macs.forEach { banMac(hotspotIface, it) }
    }
}
