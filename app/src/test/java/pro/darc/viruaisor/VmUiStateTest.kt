package pro.darc.viruaisor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.darc.viruaisor.vm.AvfSupport
import pro.darc.viruaisor.vm.VmBundle
import pro.darc.viruaisor.vm.VmStatus
import pro.darc.viruaisor.vm.VmUiState
import pro.darc.viruaisor.vm.VmStorageState
import java.io.File

class VmUiStateTest {
    @Test fun storageOperationDisablesConflictingLifecycleActions() {
        val state = VmUiState(support=AvfSupport.Available(2),bundle=bundle,
            storage=VmStorageState(busy=true))
        assertFalse(state.canStart)
        assertFalse(state.canImport)
        assertFalse(state.copy(status=VmStatus.GuestAgentReady).canStop)
    }
    private val bundle = VmBundle(
        directory = File("bundle"),
        kernel = File("bundle/Image"),
        initrd = null,
        rootfs = File("bundle/rootfs.img"),
        rootfsPartitionLabel = null,
        rootfsPartitionGuid = null,
        extraDisks = emptyList(),
        osName = "debian",
        memoryMiB = 1024,
        matchHostCpuTopology = true,
        vsockPort = 4050,
        network = false,
        autoMemoryBalloon = false,
        debuggable = false,
        consoleInputDevice = null,
        kernelArgs = emptyList(),
    )

    @Test
    fun availableStoppedVmWithBundleCanStartAndImport() {
        val state = VmUiState(
            support = AvfSupport.Available(capabilities = 2),
            status = VmStatus.Stopped,
            bundle = bundle,
        )

        assertTrue(state.canStart)
        assertTrue(state.canImport)
        assertFalse(state.canStop)
    }

    @Test
    fun runningVmCanOnlyStop() {
        val state = VmUiState(
            support = AvfSupport.Available(capabilities = 2),
            status = VmStatus.GuestAgentReady,
            bundle = bundle,
        )

        assertFalse(state.canStart)
        assertFalse(state.canImport)
        assertTrue(state.canStop)
    }

    @Test
    fun unsupportedDeviceCannotImportOrStart() {
        val state = VmUiState(
            support = AvfSupport.Unavailable("unsupported"),
            status = VmStatus.Stopped,
            bundle = bundle,
        )

        assertFalse(state.canStart)
        assertFalse(state.canImport)
        assertFalse(state.canStop)
    }
}
