package pro.darc.viruaisor.vm

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VmMemoryPolicyTest {
    private val eightGiB = 8192 * VmMemoryPolicy.MIB
    private val bundle = VmBundle(File("vm"), File("kernel"), null, File("rootfs"), null, null,
        emptyList(), "Linux", 4096, true, 4050, true, false, false, null, emptyList())
    private val stopped = VmUiState(support = AvfSupport.Available(3), bundle = bundle)

    @Test fun limitFloorsPhysicalMemoryAndCapsAtBundleMaximum() {
        assertEquals(8192, VmMemoryPolicy.maxMiB(eightGiB + 12345))
        assertEquals(32768, VmMemoryPolicy.maxMiB(Long.MAX_VALUE))
        assertEquals(0, VmMemoryPolicy.maxMiB(0))
        assertEquals(0, VmMemoryPolicy.maxMiB(-1))
    }

    @Test fun rejectsMalformedOutOfRangeAndUnchangedInput() {
        listOf("", "2.5", "-1", "0", "255", "32769", "999999999999999999999", "4096").forEach {
            assertNotNull("must reject $it", VmMemoryPolicy.inputError(it, eightGiB, 4096))
        }
        assertNull(VmMemoryPolicy.inputError("256", eightGiB, 4096))
        assertNull(VmMemoryPolicy.inputError("8192", eightGiB, 4096))
    }

    @Test fun serviceValidationRejectsBeyondHostAndUnknownHostMemory() {
        assertNotNull(VmMemoryPolicy.error(8193, eightGiB))
        assertNotNull(VmMemoryPolicy.error(4096, 0))
        assertNotNull(VmMemoryPolicy.error(256, 255 * VmMemoryPolicy.MIB))
        assertNull(VmMemoryPolicy.error(32768, 65536 * VmMemoryPolicy.MIB))
        assertNull(VmMemoryPolicy.error(256, eightGiB))
    }

    @Test fun onlyIdleStoppedOrCrashedImportedVmCanChangeMemory() {
        assertTrue(stopped.canConfigureMemory)
        assertTrue(stopped.copy(status = VmStatus.Crashed("previous failure")).canConfigureMemory)
        assertFalse(stopped.copy(bundle = null).canConfigureMemory)
        listOf(VmStatus.Importing, VmStatus.Starting, VmStatus.Running,
            VmStatus.GuestAgentReady, VmStatus.Stopping).forEach {
            assertFalse("status=$it", stopped.copy(status = it).canConfigureMemory)
        }
        assertFalse(stopped.copy(storage = VmStorageState(busy = true)).canConfigureMemory)
        assertFalse(stopped.copy(memory = VmMemoryState(busy = true)).canConfigureMemory)
    }

    @Test fun savingMemoryBlocksStartImportAndDiskActions() {
        val saving = stopped.copy(memory = VmMemoryState(busy = true))
        assertFalse(saving.canStart)
        assertFalse(saving.canImport)
        val policy = pro.darc.viruaisor.ui.WorkbenchPolicy(saving)
        assertFalse(policy.canConfigure)
        assertFalse(policy.canResizeDisk)
    }
}
