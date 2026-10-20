package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import pro.darc.viruaisor.ui.WorkbenchPolicy
import pro.darc.viruaisor.ui.capacityError
import pro.darc.viruaisor.vm.*
import java.io.File

class WorkbenchPolicyTest {
    private val bundle = VmBundle(File("vm"), File("kernel"), null, File("rootfs"), null, null,
        emptyList(), "Manjaro Plasma", 4096, true, 4050, true, false, false, null, emptyList())
    private val stopped = VmUiState(support = AvfSupport.Available(3), bundle = bundle)

    @Test fun probeBlocksEveryConflictingMutation() {
        val policy = WorkbenchPolicy(stopped, probesRunning = true)
        assertFalse(policy.canStart)
        assertFalse(policy.canImport)
        assertFalse(policy.canConfigure)
        assertFalse(policy.canResizeDisk)
        assertFalse(policy.canConfigureMemory)
    }

    @Test fun permissionSetupBlocksVmAndDiskOperations() {
        val policy = WorkbenchPolicy(stopped, permissionBusy = true)
        assertFalse(policy.canStart)
        assertFalse(policy.canImport)
        assertFalse(policy.canResizeDisk)
        assertFalse(policy.canConfigureMemory)
    }

    @Test fun importingBlocksEnvironmentConfigurationAndProbes() {
        assertFalse(WorkbenchPolicy(stopped.copy(status = VmStatus.Importing)).canConfigure)
    }

    @Test fun pendingRecoveryAllowsBootButNeverReplacementOrAnotherResize() {
        val policy = WorkbenchPolicy(stopped.copy(storage = VmStorageState(recoveryPending = true)))
        assertTrue(policy.canStart)
        assertTrue(policy.canRestoreDisk)
        assertFalse(policy.canImport)
        assertFalse(policy.canResizeDisk)
    }

    @Test fun activeVmCanResizeButCannotRestoreOrChangeEnvironment() {
        val policy = WorkbenchPolicy(stopped.copy(status = VmStatus.GuestAgentReady))
        assertTrue(policy.canStop)
        assertTrue(policy.canResizeDisk)
        assertFalse(policy.canRestoreDisk)
        assertFalse(policy.canConfigure)
    }

    @Test fun capacityRejectsInvalidAndUnchangedValues() {
        val current = 8 * RootfsCapacity.GIB
        listOf("", "1.5", "0", "-1", "1025", "9999999999999999999", "8").forEach {
            assertNotNull("must reject $it", capacityError(it, current))
        }
        assertNull(capacityError("1", current))
        assertNull(capacityError("1024", current))
    }
}
