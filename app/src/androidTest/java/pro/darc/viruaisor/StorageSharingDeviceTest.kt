package pro.darc.viruaisor

import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import pro.darc.viruaisor.vm.VmService
import java.io.File
import java.security.MessageDigest

/** Opt-in: requires the temporary, fixed-path guest fixture documented in the device report. */
class StorageSharingDeviceTest {
    @Test fun bidirectionalFilesThroughRealGuest() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("storageValidation") == "20261019")
        val expectedBytes = requireNotNull(arguments.getString("rootBytes")).toLong()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "storage/0/.viruaisor-validation-20261019")
        check(directory.canonicalFile == directory.absoluteFile.normalize())
        if (directory.exists()) check(directory.deleteRecursively())
        check(directory.mkdirs())
        val payload = ByteArray(256 * 1024) { ((it * 31 + 17) % 251).toByte() }
        File(directory, "host-input").writeBytes(payload)
        File(directory, "host.sha256").writeText(sha(payload) + "\n")
        File(directory, "host-delete").writeText("guest must remove this\n")
        File(directory, "host-ready").writeText("20261019\n")
        var started = false
        try {
            context.startForegroundService(VmService.startIntent(context))
            started = true
            await { File(directory, "guest-ready").isFile || File(directory, "guest-error").isFile }
            assertFalse(File(directory, "guest-error").readIfPresent(), File(directory, "guest-error").exists())
            assertFalse(File(directory, "host-input").exists())
            assertFalse(File(directory, "host-delete").exists())
            assertArrayEquals(payload, File(directory, "host-renamed").readBytes())
            assertArrayEquals(payload, File(directory, "guest-payload").readBytes())
            assertEquals("guest-created\n", File(directory, "guest-created").readText())
            File(directory, "guest-created").writeText("host-edited\n")
            check(File(directory, "guest-created").renameTo(File(directory, "host-renamed-guest")))
            check(File(directory, "guest-delete").delete())
            File(directory, "host-ack").writeText("host-edited\n")
            await { File(directory, "guest-pass").isFile || File(directory, "guest-error").isFile }
            assertFalse(File(directory, "guest-error").readIfPresent(), File(directory, "guest-error").exists())
            val report = File(directory, "guest-pass").readText()
            assertTrue(report, report.contains("uid=1000 gid=984"))
            assertTrue(report, report.contains("rootBytes=$expectedBytes\n"))
            assertTrue(report, report.contains("marker=viruaisor-storage-preserve-20261019"))
            assertFalse(File(directory, "host-renamed-guest").exists())
            // The file probe can finish before VmService's readiness probe. Keep the runner
            // alive until its durable boot verification has also cleaned the transaction.
            await { !File(context.filesDir, "vms/default/.rootfs-resize").exists() }
            val evidence = File(context.filesDir, "storage-validation-20261019")
            check(evidence.isDirectory)
            File(evidence, "sharing-$expectedBytes.txt").writeText("hostUid=${android.os.Process.myUid()}\nsha256=${sha(payload)}\n$report")
            Log.i("ViruaisorStorageTest", "BIDIRECTIONAL_PASS hostUid=${android.os.Process.myUid()} $report")
        } finally {
            if (started) {
                context.startForegroundService(VmService.stopIntent(context))
                // Instrumentation completion kills the package. Wait for the service's full
                // AVF/FS/root-bridge teardown first; guest poweroff alone is insufficient.
                await(40_000) { !hasVmResources(context.applicationInfo.uid) }
            }
        }
    }

    private fun hasVmResources(uid: Int): Boolean {
        // This read-only observer is shell; every shared file operation above is the app UID.
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("ps -A -o UID,ARGS")
        val rows = android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readLines() }
        return rows.any { row ->
            val columns = row.trim().split(Regex("\\s+"), limit = 2)
            columns.size == 2 && (columns[0] == uid.toString() &&
                (columns[1].startsWith("crosvm_default ") || columns[1].startsWith("virtmgr_") ||
                    columns[1].contains("libvenus_desktop_crosvm.so device fs ")) ||
                columns[0] == "0" && columns[1] == AppIdentity.rootProcess(BuildConfig.APPLICATION_ID))
        }
    }
    private fun await(timeout: Long = 60_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!condition()) {
            check(SystemClock.elapsedRealtime() < deadline) { "Timed out waiting for guest storage validation" }
            Thread.sleep(100)
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    private fun File.readIfPresent() = if (isFile) readText() else "No guest failure report"
}
