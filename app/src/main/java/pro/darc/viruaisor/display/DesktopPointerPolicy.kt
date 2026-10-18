package pro.darc.viruaisor.display

internal object DesktopPointerPolicy {
    fun isTrackpad(source: Int, captured: Boolean) = captured && source and 0xff == 0x08
    data class ScreenMode(val id: Int,val width: Int,val height: Int,val hz: Float)
    fun preferredMode(modes: List<ScreenMode>,width: Int,height: Int,targetHz: Float): ScreenMode? =
        modes.filter { it.width == width && it.height == height && it.hz.isFinite() && it.hz > 0 && it.hz <= targetHz+.25f }
            .maxByOrNull { it.hz }
}
