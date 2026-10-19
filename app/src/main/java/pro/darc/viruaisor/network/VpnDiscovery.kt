package pro.darc.viruaisor.network

/** Public NetworkCapabilities redacts another VPN's owner even for a root client.
 * Read only that identity from the current ConnectivityService dump; all link
 * addresses, DNS, MTU and transport still come from ConnectivityManager. */
internal object VpnDiscovery {
    fun table(aliases: String, name: String): Int? {
        require(name.matches(Regex("(?:tun|ipsec)[0-9]{1,5}")))
        val entries = aliases.lineSequence().map { it.substringBefore('#').trim().split(Regex("\\s+")) }
            .filter { it.size == 2 && it[1] == name }.toList()
        return entries.singleOrNull()?.get(0)?.toIntOrNull()?.takeIf { it in 1001..1_000_000 }
    }
    fun owner(dump: String, netId: Int, appUid: Int): Int? {
        val lines = dump.lineSequence().filter { it.trimStart().startsWith("NetworkAgentInfo{network{$netId}") }.toList()
        val line = lines.singleOrNull() ?: return null
        val transports = Regex("nc\\{\\[ Transports: ([A-Z_|]+) Capabilities:").find(line)?.groupValues?.get(1)?.split('|') ?: return null
        if ("VPN" !in transports) return null
        val owner = Regex(" OwnerUid: ([0-9]+) AdminUids:").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return owner.takeIf { it % 100000 in 10000..19999 && it / 100000 == appUid / 100000 }
    }
}
