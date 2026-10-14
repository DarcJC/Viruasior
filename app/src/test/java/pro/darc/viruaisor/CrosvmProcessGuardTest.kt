package pro.darc.viruaisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.darc.viruaisor.display.CrosvmProcessGuard
import java.io.File
import java.nio.file.AccessDeniedException

class CrosvmProcessGuardTest {
    @get:Rule val temporary = TemporaryFolder()
    private val executables = mutableMapOf<String, String?>()
    private val proc: File by lazy { temporary.newFolder("proc") }

    @Test
    fun deviceNamedCrosvmIsNotMistakenForZeroVMs() {
        // Replay the real device's /proc/22884/comm and /proc/22884/exe, including VM naming.
        process("22884", "crosvm_default", "/apex/com.android.virt/bin/crosvm")
        assertEquals("22884", guard().requireSingle().name)
    }

    @Test
    fun legacyUnnamedCrosvmStillWorks() {
        process("123", "crosvm", "/apex/com.android.virt/bin/crosvm")
        assertEquals("123", guard().requireSingle().name)
    }

    @Test
    fun executableIdentityDoesNotDependOnTruncatedOrRenamedComm() {
        process("123", "custom_vm", "/apex/com.android.virt@370000000/bin/crosvm")
        assertEquals("123", guard().requireSingle().name)
    }

    @Test
    fun matchingNameWithWrongExecutableDoesNotCountAsVM() {
        process("123", "crosvm", "/system/bin/sleep")
        assertCountRejected(0)
    }

    @Test
    fun multipleNamedVMsAreStillRejected() {
        process("123", "crosvm_default", "/apex/com.android.virt/bin/crosvm")
        process("456", "crosvm_terminal", "/apex/com.android.virt/bin/crosvm")
        assertCountRejected(2)
    }

    @Test
    fun zeroVMsAndKernelThreadsAreRejected() {
        process("123", "kworker", null)
        assertCountRejected(0)
    }

    @Test
    fun nonPidEntriesAreNotScanned() {
        process("self", "crosvm", "/apex/com.android.virt/bin/crosvm")
        process("0", "crosvm", "/apex/com.android.virt/bin/crosvm")
        assertCountRejected(0)
    }

    @Test
    fun unrelatedAndDeletedVmBinariesCannotHideCompetingVMs() {
        process("123", "crosvm_default", "/apex/com.android.virt/bin/crosvm")
        process("456", "renamed", "/apex/com.android.virt@360000000/bin/crosvm (deleted)")
        process("789", "other", "/data/local/tmp/crosvm")
        assertCountRejected(2)
    }

    @Test
    fun unreadableExecutableFailsClosedInsteadOfBeingCountedAsZero() {
        process("123", "crosvm_default", "/apex/com.android.virt/bin/crosvm")
        val guard = CrosvmProcessGuard(proc) { throw AccessDeniedException(it.path) }
        val error = assertThrows(IllegalStateException::class.java) { guard.requireSingle() }
        assertTrue(error.message.orEmpty().contains("Cannot inspect executable for pid=123"))
    }

    @Test
    fun failedProcEnumerationIsNotReportedAsZeroVMs() {
        val file = temporary.newFile("not-proc")
        val error = assertThrows(IllegalStateException::class.java) {
            CrosvmProcessGuard(file).requireSingle()
        }
        assertTrue(error.message.orEmpty().contains("Cannot inspect active VM processes"))
    }

    private fun guard() = CrosvmProcessGuard(proc) { executables.getValue(it.name) }

    private fun process(pid: String, comm: String, executable: String?) {
        val directory = File(proc, pid).apply { mkdirs() }
        File(directory, "comm").writeText("$comm\n")
        executables[pid] = executable
    }

    private fun assertCountRejected(expected: Int) {
        val error = assertThrows(IllegalStateException::class.java) { guard().requireSingle() }
        assertEquals("Root display experiment requires exactly one crosvm; found $expected", error.message)
    }
}
