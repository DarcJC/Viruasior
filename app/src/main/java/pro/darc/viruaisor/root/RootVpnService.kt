package pro.darc.viruaisor.root

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.system.Os
import android.util.AtomicFile
import android.util.Log
import com.topjohnwu.superuser.ipc.RootService
import org.json.JSONObject
import pro.darc.viruaisor.network.VpnLink
import pro.darc.viruaisor.network.VpnRules
import pro.darc.viruaisor.network.VpnDiscovery
import pro.darc.viruaisor.network.removeVpnOperations
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Fixed VM network lease. No caller-selected command, interface, route or path. */
class RootVpnService : RootService() {
    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "vm-vpn-root") }
    private var token: IBinder? = null
    private var callerPid = 0
    private var ownerDead = false
    private var death: IBinder.DeathRecipient? = null
    private var kernel: VpnKernel? = null
    private var status = "等待 VPN"
    private var startedAt = 0L

    override fun onCreate() {
        check(Process.myUid() == 0 && packageName == pro.darc.viruaisor.BuildConfig.APPLICATION_ID)
        worker.scheduleWithFixedDelay({
            if (kernel != null) runCatching { update() }.onFailure { failure ->
                runCatching { kernel?.disconnect() }
                val message = "ERROR: ${failure.message?.take(240)}"
                if (status != message) Log.e(TAG, "VPN lease is blocked", failure)
                status = message
            }
        }, 250, 1500, TimeUnit.MILLISECONDS)
    }

    override fun onBind(intent: Intent): IBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            val uid = getCallingUid(); val pid = getCallingPid()
            RootStorageAuthorization.enforce(applicationInfo.uid, uid, pid, code)
            require(reply != null && flags and IBinder.FLAG_ONEWAY == 0)
            data.enforceInterface(DESCRIPTOR)
            val owner = requireNotNull(data.readStrongBinder())
            data.enforceNoDataAvail()
            val identity = clearCallingIdentity()
            try {
                val result = worker.submit<String> {
                    RootStorageAuthorization.verifyProcess(File("/proc/$pid/status").readText(),
                        File("/proc/$pid/cmdline").readText(), uid, packageName)
                    if (code == START) {
                        check(token == null && owner.isBinderAlive) { "A VPN lease is already owned" }
                        val next = VpnKernel(this@RootVpnService, uid)
                        check(next.vmProcesses().isEmpty()) { "Stop existing VMs before arming VPN sharing" }
                        next.recover()
                        next.arm()
                        kernel = next; token = owner; callerPid = pid; ownerDead = false
                        startedAt = android.os.SystemClock.elapsedRealtime()
                        val recipient = IBinder.DeathRecipient { worker.execute { ownerDead = true; runCatching { kernel?.disconnect() } } }
                        death = recipient
                        try { owner.linkToDeath(recipient, 0) }
                        catch (failure: Throwable) { ownerDead = true; throw failure }
                        status = "等待虚拟机与 Android VPN；外网已暂停"
                    } else if (code == STOP && token == null && kernel == null) {
                        status = "VPN 转发未建立；无需撤销"
                    } else {
                        RootStorageAuthorization.enforceOwner(uid, callerPid, requireNotNull(token), uid, pid, owner)
                        // STATUS reads the worker's snapshot. Do not duplicate its
                        // kernel queries whenever the ordinary UI polls Binder.
                        if (code == STOP) stop()
                    }
                    status
                }.get(25, TimeUnit.SECONDS)
                reply.writeNoException(); reply.writeString(result)
            } catch (failure: Exception) {
                val cause = (failure as? java.util.concurrent.ExecutionException)?.cause ?: failure
                Log.e(TAG, "Fixed VPN operation failed code=$code", cause)
                throw IllegalStateException(cause.message ?: "VPN lease failed", cause)
            } finally { restoreCallingIdentity(identity) }
            return true
        }
    }

    private fun update() {
        val current = kernel ?: return
        if (ownerDead || token?.isBinderAlive != true) {
            current.disconnect()
            if (current.vmProcesses().isEmpty()) stop()
            return
        }
        if (status.startsWith("ERROR:")) return // Keep the guard closed until Stop/recovery.
        val processes = current.vmProcesses()
        if (processes.isEmpty()) {
            if (android.os.SystemClock.elapsedRealtime() - startedAt > 30_000) status = "ERROR: VM did not acquire the VPN lease"
            return
        }
        current.verifyVm(processes)
        val nextStatus = current.update()
        if (status != nextStatus) Log.i(TAG, nextStatus)
        status = nextStatus
    }

    private fun stop() {
        val current = kernel ?: return
        current.disconnect()
        check(current.vmProcesses().isEmpty()) { "VPN guard retained until all VMs have exited" }
        current.close()
        death?.let { recipient -> runCatching { token?.unlinkToDeath(recipient, 0) } }
        token = null; death = null; kernel = null
        status = "VPN 转发已撤销"
        Log.i(TAG, "VPN_LEASE_REMOVED uid=${applicationInfo.uid}")
    }

    override fun onUnbind(intent: Intent): Boolean {
        worker.execute { ownerDead = true; runCatching { kernel?.disconnect() }; if (kernel?.vmProcesses()?.isEmpty() == true) runCatching { stop() } }
        return false
    }
    override fun onDestroy() {
        try { worker.submit { kernel?.disconnect(); if (kernel?.vmProcesses()?.isEmpty() == true) stop() }.get(25, TimeUnit.SECONDS) }
        catch (failure: Exception) { Log.e(TAG, "VPN guard retained for recovery", failure) }
        finally { worker.shutdown() }
    }
    companion object {
        const val DESCRIPTOR = "pro.darc.viruaisor.root.IVmVpn.v1"
        const val START = 1
        const val STATUS = 2
        const val STOP = 3
        private const val TAG = "ViruaisorVpnRoot"
    }
}

/** All state and all command construction stay inside the authenticated root leaf. */
private class VpnKernel(private val context: RootVpnService, private val uid: Int) {
    private val rules = VpnRules(uid)
    private val directory = File(context.filesDir.canonicalFile, "vpn-lease")
    private val journal: AtomicFile
    private var link: VpnLink? = null
    private var subnet: String? = null
    private var ifindex = 0
    private var signature = ""
    private var armed = false
    private var verifiedNetwork = -1
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    init {
        check(directory.canonicalFile == directory.absoluteFile.normalize())
        check(directory.isDirectory || directory.mkdir())
        check(Os.stat(directory.path).st_uid in setOf(0, uid))
        Os.chmod(directory.path, 0x1c0); Os.chown(directory.path, uid, uid)
        journal = AtomicFile(File(directory, "journal"))
    }

    fun vmProcesses(): List<File> = File("/proc").listFiles().orEmpty().filter { process ->
        process.name.toIntOrNull() != null && runCatching {
            val name = File(process, "cmdline").readText().substringBefore('\u0000')
            name.startsWith("crosvm_") || Regex("^/apex/com\\.android\\.virt(?:@[0-9]+)?/bin/crosvm$").matches(name)
        }.getOrDefault(false)
    }

    fun verifyVm(processes: List<File>) {
        check(processes.size == 1) { "VPN sharing requires exactly one app-owned VM" }
        val process = processes.single()
        check(Os.stat(process.path).st_uid == uid && File(process, "cmdline").readText().substringBefore('\u0000') == "crosvm_default")
        val executable = Os.readlink("${process.path}/exe")
        check(Regex("^/apex/com\\.android\\.virt(?:@[0-9]+)?/bin/crosvm$").matches(executable) ||
            executable == File(context.applicationInfo.nativeLibraryDir, RootOverlayArtifactPolicy.DESKTOP_LIBRARY).path)
    }

    fun arm() {
        check(!armed)
        for (family in listOf("-4", "-6")) {
            val text = exec(listOf("ip", family, "rule", "show")).output
            check(listOf(rules.routePriority, rules.routePriority+1, rules.returnPriority).none { priority ->
                Regex("(?m)^$priority:").containsMatchIn(text)
            }) { "VPN route priority is already in use" }
        }
        for (binary in listOf("iptables", "ip6tables")) for (name in listOf(rules.forward, rules.input))
            check(exec(listOf(binary, "-w", "5", "-t", "filter", "-S", name)).code == 1) { "VPN chain already exists" }
        check(exec(listOf("iptables", "-w", "5", "-t", "nat", "-S", rules.dnsChain)).code == 1)
        armed = true; persist() // Record intent before any mutation; recovery regenerates only fixed commands.
        try { rules.guard().forEach { checked(it.add) } }
        catch (failure: Throwable) { runCatching { close() }; throw failure }
        Log.i("ViruaisorVpnRoot", "VPN_GUARD_ARMED uid=$uid")
    }

    fun update(): String {
        val index = File("/sys/class/net/${VpnRules.TAP}/ifindex").readText().trim().toInt()
        if (ifindex != index) {
            check(ifindex == 0) { "VM TAP was replaced; VPN guard retained" }
            val qdisc = checked(listOf("tc", "qdisc", "show", "dev", VpnRules.TAP))
            if (!qdisc.contains("clsact") && !qdisc.contains("ingress")) return "等待 AVF 网络准备；外网已暂停"
            val filters = checked(listOf("tc", "filter", "show", "dev", VpnRules.TAP, "ingress"))
            check(!Regex("\\bpref 1\\b").containsMatchIn(filters)) { "TAP classifier priority is already in use" }
            ifindex = index; persist()
            checked(rules.ingress().add)
        }
        val filters = checked(listOf("tc", "filter", "show", "dev", VpnRules.TAP, "ingress"))
        check(VpnRules.ownsIngress(filters)) { "TAP ingress guard was lost: ${filters.take(500)}" }
        val candidates = cm.allNetworks.mapNotNull { network ->
            val capabilities = cm.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return@mapNotNull null
            val properties = cm.getLinkProperties(network) ?: return@mapNotNull null
            Triple(network, capabilities, properties)
        }
        if (candidates.isEmpty()) { disconnect(); verifiedNetwork = -1; return "等待 Android VPN；虚拟机外网已暂停" }
        check(candidates.size == 1) { "Ambiguous Android VPN; traffic remains blocked" }
        val (network, _, properties) = candidates.single()
        val netId = network.toString().toInt()
        if (verifiedNetwork != netId) {
            check(VpnDiscovery.owner(checked(listOf("dumpsys", "connectivity")), netId, uid) != null) { "VPN owner could not be verified for this Android user" }
            verifiedNetwork = netId
        }
        val address = properties.linkAddresses.firstOrNull { it.address is Inet4Address }?.address?.hostAddress
            ?: run { disconnect(); return "当前 VPN 无可用 IPv4；外网已暂停" }
        val dns = properties.dnsServers.firstOrNull { it is Inet4Address }?.hostAddress
            ?: run { disconnect(); return "当前 VPN 无 IPv4 DNS；外网已暂停" }
        val name = requireNotNull(properties.interfaceName)
        // Android deletes interface-name aliases when a VPN goes away. Persist
        // its verified numeric table so teardown never needs that missing alias.
        val table = link?.takeIf { it.name == name && it.netId == netId }?.table ?: run {
            val aliases = File("/data/misc/net/rt_tables") // Read-only netd metadata.
            check(aliases.length() in 1..65536)
            VpnDiscovery.table(aliases.readText(), name)
        } ?: run { disconnect(); return "等待 VPN 路由表；外网已暂停" }
        val next = VpnLink(name, address, dns, properties.mtu.takeIf { it > 0 } ?: 1500, netId, table)
        // A tether downstream has no NetworkAgent and is absent from allNetworks.
        val tapAddress = NetworkInterface.getByName(VpnRules.TAP)?.interfaceAddresses
            ?.singleOrNull { it.address is Inet4Address }
            ?: run { disconnect(); return "等待 AVF 地址分配；外网已暂停" }
        val gateway = requireNotNull(tapAddress.address.hostAddress)
        val nextSubnet = VpnRules.subnet(gateway, tapAddress.networkPrefixLength.toInt())
        val nextSignature = properties.routes.joinToString("|")
        if (next == link && nextSubnet == subnet && nextSignature == signature) return ready(next)
        disconnect()
        // Use only Android's current VPN table; never invent a default route into
        // a split VPN or modify its routes, UID ranges, marks or upstream.
        val routes = checked(listOf("ip", "-4", "route", "show", "table", "${next.table}"))
        check(routes.isNotBlank() && routes.lineSequence().filter { it.isNotBlank() }.all { line ->
            line.contains("dev $name ") || line.endsWith("dev $name") || line.startsWith("unreachable ") || line.startsWith("throw ")
        }) { "VPN routing table does not match its interface" }
        link = next; subnet = nextSubnet; signature = nextSignature; persist()
        try {
            rules.tunnel(next, nextSubnet).forEach { checked(it.add) }
            val route = checked(listOf("ip", "-4", "route", "get", dns, "from", VpnRules.probeSource(nextSubnet, gateway), "iif", VpnRules.TAP))
            check(route.contains("dev $name ") || route.contains("dev $name\n")) { "VPN DNS route was not selected" }
        } catch (failure: Throwable) { disconnect(); throw failure }
        Log.i("ViruaisorVpnRoot", "VPN_CONFIGURED uid=$uid interface=$name netId=${next.netId} ipv6=blocked")
        return ready(next)
    }

    private fun ready(vpn: VpnLink) = "已绑定 Android VPN：${vpn.name}（IPv4；IPv6 已阻断）"

    fun disconnect() {
        val previous = link ?: return
        removeVpnOperations(rules.tunnel(previous, requireNotNull(subnet)), ::remove)
        link = null; subnet = null; signature = ""; persist()
    }

    fun close() {
        disconnect()
        if (ifindex != 0) {
            val current = File("/sys/class/net/${VpnRules.TAP}/ifindex").takeIf { it.isFile }?.readText()?.trim()?.toIntOrNull()
            if (current == ifindex) {
                val filters = checked(listOf("tc", "filter", "show", "dev", VpnRules.TAP, "ingress"))
                if (VpnRules.ownsIngress(filters)) remove(rules.ingress().remove)
                else check(!Regex("\\bpref 1\\b").containsMatchIn(filters)) { "TAP classifier ownership changed; journal retained" }
            }
        }
        if (armed) removeVpnOperations(rules.guard(), ::remove)
        armed = false; ifindex = 0; journal.delete()
    }

    fun recover() {
        if (!journal.baseFile.exists() && !File(directory, "journal.bak").exists()) return
        check(vmProcesses().isEmpty()) { "Stop the VM before recovering the previous VPN lease" }
        val text = journal.openRead().use { input -> check(input.available() < 4096); input.bufferedReader().readText() }
        val record = JSONObject(text)
        check(record.getInt("version") == 2 && record.getInt("uid") == uid)
        armed = record.getBoolean("armed"); ifindex = record.getInt("ifindex"); check(ifindex >= 0)
        record.optJSONObject("vpn")?.let { vpn ->
            link = VpnLink(vpn.getString("name"), vpn.getString("address"), vpn.getString("dns"), vpn.getInt("mtu"), vpn.getInt("netId"), vpn.getInt("table"))
            subnet = record.getString("subnet")
            rules.tunnel(requireNotNull(link), requireNotNull(subnet)) // Validate every persisted field before use.
        }
        close()
    }

    private fun persist() {
        val record = JSONObject().put("version", 2).put("uid", uid).put("armed", armed).put("ifindex", ifindex)
        link?.let { vpn -> record.put("subnet", subnet).put("vpn", JSONObject().put("name", vpn.name).put("address", vpn.address)
            .put("dns", vpn.dns).put("mtu", vpn.mtu).put("netId", vpn.netId).put("table", vpn.table)) }
        val output = journal.startWrite()
        try { output.write(record.toString().toByteArray()); journal.finishWrite(output); Os.chown(journal.baseFile.path, uid, uid); Os.chmod(journal.baseFile.path, 0x180) }
        catch (failure: Throwable) { journal.failWrite(output); throw failure }
    }

    private fun remove(args: List<String>) {
        if (args[0] in listOf("iptables", "ip6tables")) {
            // A jump to an already removed owned chain makes iptables -C exit
            // with a parser error rather than its usual "rule absent" code.
            // Verify the exact owned target is absent before treating it as gone.
            val target = args.indexOf("-j").takeIf { it >= 0 }?.let { args.getOrNull(it + 1) }
            if (target in setOf(rules.forward, rules.input, rules.dnsChain)) {
                val chain = exec(listOf(args[0], "-w", "5", "-t", args[4], "-S", target!!))
                if (chain.code == 1) return
                check(chain.code == 0) { "Cannot inspect an owned VPN chain" }
            }
            val check = args.toMutableList()
            check[5] = if (args[5] == "-D") "-C" else "-S"
            val exists = exec(check)
            if (exists.code == 1) return
            check(exists.code == 0) { "Cannot inspect an owned VPN rule" }
        }
        val result = exec(args)
        if (result.code != 0) check((args[0] == "ip" || args[0] == "tc") &&
            (result.output.contains("No such file") || result.output.contains("No such process") || result.output.contains("Cannot find device"))) {
            "Cannot remove owned VPN resource (${args[0]}, ${result.code})"
        }
    }

    private data class Result(val code: Int, val output: String)
    private fun checked(args: List<String>): String {
        val result = exec(args)
        check(result.code == 0) { "VPN operation failed (${args.take(6).joinToString(" ")}, ${result.code}): ${result.output.take(240)}" }
        return result.output
    }
    private fun exec(args: List<String>): Result {
        require(args.first() in listOf("ip", "iptables", "ip6tables", "tc", "dumpsys"))
        val process = ProcessBuilder(listOf("/system/bin/${args.first()}") + args.drop(1)).redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread({ process.inputStream.bufferedReader().use { input ->
            val buffer = CharArray(2048)
            while (true) { val count = input.read(buffer); if (count < 0) break; synchronized(output) {
                if (output.length < 2_097_152) output.append(buffer, 0, count)
            } }
        } }, "vpn-command-output").apply { isDaemon = true; start() }
        try {
            check(process.waitFor(8, TimeUnit.SECONDS)) { "VPN command timed out" }
            reader.join(1000); check(!reader.isAlive)
            return Result(process.exitValue(), synchronized(output) { output.toString() })
        } finally { if (process.isAlive) process.destroyForcibly(); process.inputStream.close() }
    }
}
