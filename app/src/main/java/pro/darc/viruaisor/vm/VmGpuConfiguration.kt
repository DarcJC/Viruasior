package pro.darc.viruaisor.vm

/** GPU configuration decisions at the reflective AVF builder boundary. */
internal object VmGpuConfiguration {
    fun apply(profile: VmGpuProfile, set: (String, Any) -> Unit) {
        set("setBackend", profile.avfBackend)
        // API 37 defaults this to false even for gfxstream. Do not silently fall back when
        // the runtime lacks the setter: the caller must report configuration failure.
        set("setRendererUseVulkan", profile == VmGpuProfile.GFXSTREAM)
    }
}
