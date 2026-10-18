package pro.darc.viruaisor.vm

internal object DisplayModePolicy {
    fun fits(native: DisplaySize, candidate: DisplaySize) =
        kotlin.math.abs(candidate.height.toDouble() - candidate.width.toDouble()*native.height/native.width) <= 2.0
    fun preferred(native: DisplaySize, modes: List<DisplayMode>): DisplayMode? =
        modes.filter { it.size.width <= 1920 && fits(native,it.size) }
            .maxWithOrNull(compareBy<DisplayMode> { it.size.width }.thenBy { it.refreshMilliHz })
            ?: modes.filter { it.size == native }.maxByOrNull { it.refreshMilliHz }
}
