package pro.darc.viruaisor

import android.content.Intent
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.json.JSONObject
import pro.darc.viruaisor.vm.VmService
import java.io.File

/** App-UID headless driver; the host controller inspects real kernel/guest results. */
class VmVpnHeadlessTest {
    @Test fun driveFromHost() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("headlessController") == "20261020")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.filesDir, "headless-vpn-control").apply { mkdirs() }
        val request = File(directory, "request.json")
        val ack = File(directory, "ack.json")
        File(directory, "ready").writeText("20261020\n")
        var sequence = 0
        val deadline = SystemClock.elapsedRealtime()+600_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val record = runCatching { JSONObject(request.readText()) }.getOrNull()
            if (record == null || record.getInt("sequence") <= sequence) { Thread.sleep(200); continue }
            sequence = record.getInt("sequence")
            val operation = record.getString("operation")
            if (operation == "finish") { ack.writeText(record.toString()); return }
            val intent = when (operation) {
                "start" -> VmService.startIntent(context)
                "stop" -> VmService.stopIntent(context)
                "vpn", "ordinary" -> Intent(context, VmService::class.java)
                    .setAction("${context.packageName}.action.DEBUG_VPN_MODE").putExtra("enabled", operation == "vpn")
                "FONT_STATUS", "NETWORK_STATUS", "NETWORK_BENCH", "CHROMIUM_RENDER" -> Intent(context, VmService::class.java)
                    .setAction("${context.packageName}.action.DEBUG_GUEST_CHECK").putExtra("command", operation)
                else -> error("Unsupported fixed headless operation")
            }
            context.startForegroundService(intent)
            ack.writeText(record.toString())
        }
        context.startForegroundService(VmService.stopIntent(context))
        error("Host controller did not complete within its bounded lease")
    }
}
