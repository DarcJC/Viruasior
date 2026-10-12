package pro.darc.viruaisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.darc.viruaisor.vm.normalizeKernelArgs

class VmKernelArgsTest {
    @Test
    fun forcesHeadlessBootAndDefersDrmProbe() {
        val normalized = normalizeKernelArgs(
            listOf(
                "console=ttyS0",
                "systemd.unit=graphical.target",
                "modprobe.blacklist=nouveau,drm",
            ),
        )

        assertTrue("console=ttyS0" in normalized)
        assertFalse("systemd.unit=graphical.target" in normalized)
        assertTrue("systemd.unit=multi-user.target" in normalized)
        assertTrue("systemd.mask=display-manager.service" in normalized)
        assertTrue("systemd.mask=graphical.target" in normalized)
        assertEquals(
            "modprobe.blacklist=nouveau,drm,virtio_gpu",
            normalized.single { it.startsWith("modprobe.blacklist=") },
        )
    }

    @Test
    fun defaultsToDrmAndVirtioGpuBlacklistWithoutDroppingOtherArguments() {
        val normalized = normalizeKernelArgs(listOf("root=/dev/vda1"))

        assertTrue("root=/dev/vda1" in normalized)
        assertTrue("modprobe.blacklist=drm,virtio_gpu" in normalized)
    }
}
