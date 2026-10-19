package pro.darc.viruaisor.network

import org.junit.Assert.*
import org.junit.Test

class VpnRulesTest {
    private val vpn = VpnLink("tun0", "10.10.14.1", "10.10.14.2", 1500, 159, 1083)
    private val rules = VpnRules(10278)

    @Test fun guardBlocksBothFamiliesAndLocalDnsBeforeTunnelIsOpened() {
        val commands = rules.guard().map { it.add.joinToString(" ") }
        assertTrue(commands.any { it.startsWith("ip -4 rule add") && it.endsWith("iif avf_tap_fixed unreachable") })
        assertTrue(commands.any { it.startsWith("ip -6 rule add") && it.endsWith("iif avf_tap_fixed unreachable") })
        for (binary in listOf("iptables", "ip6tables")) {
            assertTrue(commands.any { it.startsWith("$binary ") && it.contains(" -i avf_tap_fixed -j ") })
            assertTrue(commands.any { it.startsWith("$binary ") && it.contains(" --dport 53 -j DROP") })
        }
        assertFalse(commands.any { it.contains(" ACCEPT") })
    }

    @Test fun tunnelTrafficUsesSpecificSubnetAndReturnsBeforeAndroidVpnIngressRule() {
        val commands = rules.tunnel(vpn, "10.178.3.0/24").map { it.add.joinToString(" ") }
        assertTrue(commands.any { it.contains("iif avf_tap_fixed lookup 1083") })
        assertTrue(rules.tunnel(vpn, "10.178.3.0/24").any { it.remove.joinToString(" ").endsWith("iif avf_tap_fixed lookup 1083") })
        assertTrue(commands.any { it.contains("iif tun0 to 10.178.3.0/24 lookup main") })
        assertTrue(commands.any { it.contains("-s 10.178.3.0/24 -o tun0") && it.contains("MASQUERADE") })
        assertTrue(commands.any { it.contains("-i avf_tap_fixed -s 10.178.3.0/24 -o tun0") && it.endsWith("-j ACCEPT") })
        assertTrue(commands.filter { it.contains("-j ACCEPT") }.all { it.contains("tun0") && it.contains("10.178.3.0/24") })
        assertTrue(commands.any { it.contains("--dport 53") && it.contains("--to-destination 10.10.14.2") })
        assertFalse(commands.any { it.contains("wlan0") || it.contains("--set-mark") || it.contains(" flush") })
        assertTrue(rules.routePriority in 12001..19998)
        assertTrue(rules.returnPriority in 11001..11999)
    }

    @Test fun fastPathOverridePreservesExistingAndroidClassifiers() {
        val operation = rules.ingress()
        assertEquals(listOf("tc", "filter", "add", "dev", "avf_tap_fixed", "ingress", "pref", "1", "protocol", "all", "handle", "30285", "bpf", "bytecode", "1,6 0 0 0", "direct-action"), operation.add)
        assertTrue(operation.remove.containsAll(listOf("pref", "1", "handle", "30285", "bpf")))
        assertFalse(operation.remove.contains("qdisc"))
        val installed = "filter protocol all pref 1 bpf chain 0 handle 0x764d direct-action not_in_hw bytecode '1,6 0 0 0'"
        assertTrue(VpnRules.ownsIngress(installed))
        assertFalse(VpnRules.ownsIngress(installed.replace("pref 1", "pref 2")))
        assertFalse(VpnRules.ownsIngress(installed.replace("6 0 0 0", "6 0 0 2")))
        assertFalse(VpnRules.ownsIngress(installed.replace("0x764d", "0x1")))
    }

    @Test fun untrustedNetworkFieldsCannotBecomeCommandArguments() {
        for (name in listOf("wlan0", "tun0;id", "../tun0", "tun0 x", "")) {
            assertThrows(IllegalArgumentException::class.java) { VpnLink(name, "10.0.0.1", "1.1.1.1", 1500, 159, 1083) }
        }
        assertThrows(IllegalArgumentException::class.java) { VpnLink("tun0", "bad", "1.1.1.1", 1500, 159, 1083) }
        assertThrows(IllegalArgumentException::class.java) { VpnLink("tun0", "10.0.0.1", "::1", 1500, 159, 1083) }
        assertThrows(IllegalArgumentException::class.java) { VpnLink("tun0", "10.0.0.1", "1.1.1.1", 1500, 159, 254) }
        assertThrows(IllegalArgumentException::class.java) { rules.tunnel(vpn, "0.0.0.0/0") }
        assertThrows(IllegalArgumentException::class.java) { VpnRules(0) }
    }

    @Test fun subnetCalculationUsesTheActualDhcpPrefix() {
        assertEquals("10.178.3.0/24", VpnRules.subnet("10.178.3.61", 24))
        assertEquals("172.18.4.64/26", VpnRules.subnet("172.18.4.70", 26))
        assertThrows(IllegalArgumentException::class.java) { VpnRules.subnet("1.2.3.999", 24) }
        assertEquals("10.178.3.1", VpnRules.probeSource("10.178.3.0/24", "10.178.3.61"))
        assertEquals("172.18.4.66", VpnRules.probeSource("172.18.4.64/30", "172.18.4.65"))
    }

    @Test fun vpnOwnershipRequiresCurrentVpnAgentAndExactAndroidUser() {
        val dump = "  NetworkAgentInfo{network{159} handle{1} ni{VPN CONNECTED} lp{{InterfaceName: tun0}} nc{[ Transports: WIFI|VPN Capabilities: INTERNET OwnerUid: 10260 AdminUids: [10260]]} factorySerialNumber=5}"
        assertEquals(10260, VpnDiscovery.owner(dump, 159, 10278))
        assertNull(VpnDiscovery.owner(dump, 158, 10278))
        assertNull(VpnDiscovery.owner(dump, 159, 110278))
        assertNull(VpnDiscovery.owner(dump.replace("WIFI|VPN", "WIFI"), 159, 10278))
        assertNull(VpnDiscovery.owner(dump.replace("OwnerUid: 10260", "OwnerUid: -1"), 159, 10278))
        assertNull(VpnDiscovery.owner(dump+"\n"+dump, 159, 10278))
    }

    @Test fun numericTableIdentitySurvivesRemovalOfAndroidsInterfaceAlias() {
        assertEquals(1083, VpnDiscovery.table("255 local\n1083 tun0\n1000000083 tun0_local\n1015 wlan0\n", "tun0"))
        assertNull(VpnDiscovery.table("255 local\n1015 wlan0\n", "tun0"))
        assertNull(VpnDiscovery.table("1083 tun0\n1084 tun0\n", "tun0"))
        assertNull(VpnDiscovery.table("254 tun0\n", "tun0"))
    }

    @Test fun partialFailureIsRolledBackInReverseAndFailedCleanupIsRetained() {
        val removed = mutableListOf<List<String>>()
        var failCleanup = true
        val operations = (1..3).map { RuleOperation(listOf("add", "$it"), listOf("remove", "$it")) }
        val existing = mutableSetOf("1", "2") // Third addition failed after the journal was saved.
        val remove: (List<String>) -> Unit = { args ->
            if (args.last() == "2" && failCleanup) error("injected cleanup failure")
            if (existing.remove(args.last())) removed += args
        }
        assertThrows(IllegalStateException::class.java) { removeVpnOperations(operations, remove) }
        assertEquals(listOf(listOf("remove", "1")), removed)
        assertEquals(setOf("2"), existing)
        failCleanup = false
        removeVpnOperations(operations, remove)
        assertEquals(listOf(listOf("remove", "1"), listOf("remove", "2")), removed)
        assertTrue(existing.isEmpty())
    }
}
