package pro.darc.viruaisor.vm

/** GPU configuration decisions at the reflective AVF builder boundary. */
internal object VmGpuConfiguration {
    fun apply(profile: VmGpuProfile, set: (String, Any) -> Unit) {
        set("setBackend", profile.avfBackend)
        if (profile == VmGpuProfile.GFXSTREAM) {
            // This device's host Vulkan compositor aborts on its BGRA8 display buffer. Keep the
            // working GL compositor, while making the guest Vulkan/composer capsets explicit.
            set("setRendererUseEgl", true)
            set("setRendererUseGles", true)
            set("setRendererUseGlx", false)
            set("setRendererUseSurfaceless", true)
            set("setRendererUseVulkan", true)
            set(
                "setContextTypes",
                arrayOf("gfxstream-gles", "gfxstream-vulkan", "gfxstream-composer"),
            )
        } else {
            // API 37 defaults this to false, but keep the software baseline explicit.
            set("setRendererUseVulkan", false)
        }
    }
}
