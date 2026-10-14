package pro.darc.viruaisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import pro.darc.viruaisor.vm.VmGpuConfiguration
import pro.darc.viruaisor.vm.VmGpuProfile

class VmGpuConfigurationTest {
    @Test fun gfxstreamExplicitlyRequestsVulkanRatherThanRelyingOnTheFalseDefault() {
        val calls = mutableListOf<Pair<String, Any>>()
        VmGpuConfiguration.apply(VmGpuProfile.GFXSTREAM) { method, value -> calls += method to value }
        assertEquals(listOf("setBackend" to "gfxstream", "setRendererUseVulkan" to true), calls)
    }

    @Test fun softwareBaselineDoesNotEnableTheVulkanRenderer() {
        val calls = mutableListOf<Pair<String, Any>>()
        VmGpuConfiguration.apply(VmGpuProfile.SOFTWARE_2D) { method, value -> calls += method to value }
        assertEquals(listOf("setBackend" to "2d", "setRendererUseVulkan" to false), calls)
    }

    @Test fun unsupportedVulkanSetterIsNotSilentlyReplacedBySoftwareRendering() {
        val failure = IllegalStateException("AVF Vulkan setter unavailable")
        val actual = assertThrows(IllegalStateException::class.java) {
            VmGpuConfiguration.apply(VmGpuProfile.GFXSTREAM) { method, _ ->
                if (method == "setRendererUseVulkan") throw failure
            }
        }
        assertSame(failure, actual)
    }
}
