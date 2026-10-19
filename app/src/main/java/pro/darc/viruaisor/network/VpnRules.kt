package pro.darc.viruaisor.network

internal data class VpnLink(val name: String, val address: String, val dns: String, val mtu: Int, val netId: Int, val table: Int) {
    init { require(name.matches(Regex("(?:tun|ipsec)[0-9]{1,5}"))); require(VpnRules.ipv4(address) != null && VpnRules.ipv4(dns) != null); require(mtu in 576..65535 && netId in 100..65535 && table in 1001..1_000_000) }
}
internal data class RuleOperation(val add: List<String>, val remove: List<String>)
internal class VpnRules(val uid: Int) {
    val routePriority = 18000 + uid % 800 * 2
    val returnPriority = 11500 + uid % 400
    init { require(uid % 100000 in 10000..19999) }
    val forward = "vvm_${uid}_f"
    val input = "vvm_${uid}_i"
    val dnsChain = "vvm_${uid}_d"
    fun guard(): List<RuleOperation> = buildList {
        // These rules can be armed before AVF creates its fixed TAP. No packet may
        // fall through to Android's Wi-Fi tethering rule when the VPN disappears.
        for (family in listOf("-4", "-6")) add(ip(family, "priority", "${routePriority+1}", "iif", TAP, "unreachable"))
        for (binary in listOf("iptables", "ip6tables")) {
            add(chain(binary, "filter", forward))
            add(rule(binary, "filter", "-A", forward, "-j", "DROP"))
            add(chain(binary, "filter", input))
            for (protocol in listOf("udp", "tcp"))
                add(rule(binary, "filter", "-A", input, "-p", protocol, "--dport", "53", "-j", "DROP"))
            add(rule(binary, "filter", "-I", "FORWARD", "-i", TAP, "-j", forward))
            add(rule(binary, "filter", "-I", "FORWARD", "-o", TAP, "-j", forward))
            add(rule(binary, "filter", "-I", "INPUT", "-i", TAP, "-j", input))
        }
        add(chain("iptables", "nat", dnsChain))
        add(rule("iptables", "nat", "-I", "PREROUTING", "-i", TAP, "-j", dnsChain))
    }

    fun tunnel(vpn: VpnLink, subnet: String): List<RuleOperation> = buildList {
        val parts = subnet.split('/')
        require(parts.size == 2 && parts[1].toIntOrNull() in 8..30)
        require(subnet(parts[0], parts[1].toInt()) == subnet && privateIpv4(parts[0]))
        add(ip("-4", "priority", "$returnPriority", "iif", vpn.name, "to", subnet, "lookup", "main"))
        add(ip("-4", "priority", "$routePriority", "iif", TAP, "lookup", "${vpn.table}"))
        add(rule("iptables", "nat", "-A", "POSTROUTING", "-s", subnet, "-o", vpn.name,
            "-m", "comment", "--comment", forward, "-j", "MASQUERADE"))
        for (protocol in listOf("udp", "tcp")) add(rule("iptables", "nat", "-A", dnsChain,
            "-s", subnet, "-p", protocol, "--dport", "53", "-j", "DNAT", "--to-destination", vpn.dns))
        // Existing tethering mangle rules clamp MSS too; this scoped rule remains
        // effective if the system rebuilds its own tethering chains.
        add(rule("iptables", "mangle", "-I", "FORWARD", "-i", TAP, "-s", subnet, "-o", vpn.name,
            "-p", "tcp", "--tcp-flags", "SYN,RST", "SYN", "-m", "comment", "--comment", forward,
            "-j", "TCPMSS", "--clamp-mss-to-pmtu"))
        // Install ACCEPT last; reverse-order removal closes the guard first.
        add(rule("iptables", "filter", "-I", forward, "-i", vpn.name, "-o", TAP, "-d", subnet,
            "-m", "conntrack", "--ctstate", "ESTABLISHED,RELATED", "-j", "ACCEPT"))
        add(rule("iptables", "filter", "-I", forward, "-i", TAP, "-s", subnet, "-o", vpn.name,
            "-m", "conntrack", "--ctstate", "NEW,ESTABLISHED,RELATED", "-j", "ACCEPT"))
    }

    fun ingress(): RuleOperation {
        val base = listOf("tc", "filter")
        val selector = listOf("dev", TAP, "ingress", "pref", "1", "protocol", "all", "handle", "30285", "bpf")
        // Classic BPF: RET TC_ACT_OK. Android ships cls_bpf but omits the
        // generic gact action parser. This ends classification before tether BPF.
        return RuleOperation(base + "add" + selector + listOf("bytecode", "1,6 0 0 0", "direct-action"), base + "del" + selector)
    }

    private fun chain(binary: String, table: String, name: String) = RuleOperation(
        listOf(binary, "-w", "5", "-t", table, "-N", name), listOf(binary, "-w", "5", "-t", table, "-X", name))
    private fun rule(binary: String, table: String, verb: String, vararg args: String) = RuleOperation(
        listOf(binary, "-w", "5", "-t", table, verb) + args,
        listOf(binary, "-w", "5", "-t", table, "-D") + args)
    private fun ip(family: String, vararg args: String) = RuleOperation(
        listOf("ip", family, "rule", "add") + args, listOf("ip", family, "rule", "del") + args)
    companion object {
        const val TAP = "avf_tap_fixed"
        fun ownsIngress(filters: String): Boolean {
            val programs = filters.lineSequence().map { it.trim() }
                .filter { Regex("\\bpref 1\\b").containsMatchIn(it) && it.contains(" handle ") }.toList()
            val line = programs.singleOrNull() ?: return false
            return line.startsWith("filter protocol all pref 1 bpf ") &&
                Regex("\\bhandle (?:0x764d|30285)\\b").containsMatchIn(line) &&
                line.contains(" direct-action ") && line.endsWith("bytecode '1,6 0 0 0'")
        }
        fun ipv4(value: String): Long? {
            val parts=value.split('.')
            if (parts.size != 4 || parts.any { !it.matches(Regex("0|[1-9][0-9]{0,2}")) || it.toInt() !in 0..255 }) return null
            return parts.fold(0L) { result, part -> result shl 8 or part.toLong() }
        }
        fun subnet(address: String, prefix: Int): String {
            require(prefix in 8..30)
            val value = requireNotNull(ipv4(address))
            val masked = value and (0xffffffffL shl (32-prefix))
            return (3 downTo 0).joinToString(".") { "${masked shr (8*it) and 255}" } + "/$prefix"
        }
        fun probeSource(subnet: String, gateway: String): String {
            val parts = subnet.split('/')
            require(parts.size == 2 && subnet(parts[0], parts[1].toInt()) == subnet)
            val first = requireNotNull(ipv4(parts[0])) + 1
            val value = if (first == ipv4(gateway)) first+1 else first
            return (3 downTo 0).joinToString(".") { "${value shr (8*it) and 255}" }
        }
        private fun privateIpv4(address: String): Boolean {
            val value = requireNotNull(ipv4(address))
            return value shr 24 == 10L || value shr 20 == 0xac1L || value shr 16 == 0xc0a8L
        }
    }
}
/** Retry the complete journaled plan idempotently; never stop cleanup at its first failure. */
internal fun removeVpnOperations(operations: List<RuleOperation>, remove: (List<String>) -> Unit) {
    var failure: Throwable? = null
    for (operation in operations.asReversed()) {
        try { remove(operation.remove) }
        catch (error: Throwable) { if (failure == null) failure = error else failure.addSuppressed(error) }
    }
    failure?.let { throw IllegalStateException("Owned VPN cleanup is incomplete; journal retained", it) }
}
