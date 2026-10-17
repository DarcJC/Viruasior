package pro.darc.viruaisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import pro.darc.viruaisor.vm.VmGpuConfiguration
import pro.darc.viruaisor.vm.VmGpuProfile

class VmGpuConfigurationTest {
    @Test fun venusRequiresItsVulkanCapsetAndNeverEnablesSoftwareFallback() {
        val calls = mutableMapOf<String, Any>()
        VmGpuConfiguration.apply(VmGpuProfile.VENUS) { method, value -> calls[method] = value }
        assertEquals("virglrenderer", calls["setBackend"])
        assertEquals(true, calls["setRendererUseVulkan"])
        assertEquals(false, calls["setRendererUseEgl"])
        assertEquals(false, calls["setRendererUseGles"])
        assertEquals(listOf("venus"), (calls["setContextTypes"] as? Array<*>)?.toList())
    }
    @Test fun gfxstreamKeepsTheCompatibleGlCompositorAndRequestsVulkanCapsets() {
        val calls = mutableListOf<Pair<String, Any>>()
        VmGpuConfiguration.apply(VmGpuProfile.GFXSTREAM) { method, value -> calls += method to value }

        assertEquals(
            listOf(
                "setBackend" to "gfxstream",
                "setRendererUseEgl" to true,
                "setRendererUseGles" to true,
                "setRendererUseGlx" to false,
                "setRendererUseSurfaceless" to true,
                "setRendererUseVulkan" to true,
            ),
            calls.filterNot { it.first == "setContextTypes" },
        )
        assertEquals(
            listOf("gfxstream-gles", "gfxstream-vulkan", "gfxstream-composer"),
            (calls.single { it.first == "setContextTypes" }.second as Array<*>).toList(),
        )
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
