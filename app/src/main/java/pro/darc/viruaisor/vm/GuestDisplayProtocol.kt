package pro.darc.viruaisor.vm

data class DisplaySize(val width: Int, val height: Int) {
    init {
        require(width in 1..4096 && height in 1..4096 && width.toLong()*height <= 8_388_608L) {
            "Display size exceeds the pixel budget"
        }
    }
    override fun toString() = "${width}×${height}"
}

data class DisplayModes(val current: DisplaySize, val available: List<DisplaySize>)
data class DisplayMode(val size: DisplaySize, val refreshMilliHz: Int) {
    init {
        require(refreshMilliHz in 10_000..120_250 &&
            (size.width.toLong()*size.height <= 4_194_304L || refreshMilliHz <= 60_250)) {
            "Display refresh rate exceeds the budget"
        }
    }
    val refreshHz get() = refreshMilliHz/1000f
    override fun toString() = "$size @ ${if (refreshMilliHz % 1000 == 0) (refreshMilliHz/1000).toString() else java.lang.String.format(java.util.Locale.ROOT,"%.2f",refreshHz)} Hz"
}
data class DisplayConfigs(val current: DisplayMode, val available: List<DisplayMode>)
data class VmDisplayState(
    val modes: List<DisplayMode> = emptyList(),
    val actual: DisplayMode? = null,
    val presented: DisplaySize? = null,
    val requested: DisplayMode? = null,
    val error: String? = null,
) {
    val switching get() = requested != null && requested.size != presented
    fun withPresented(size: DisplaySize) = copy(presented=size,
        requested=requested.takeUnless { it != null && it.size == size && actual == it })
    fun withConfirmed(mode: DisplayMode) = copy(actual=mode,
        requested=requested.takeUnless { it == mode && presented == mode.size })
}

internal object GuestDisplayProtocol {
    fun resize(size: DisplaySize) = "RESIZE ${size.width} ${size.height}"
    fun resize(mode: DisplayMode) = "RESIZE_RATE ${mode.size.width} ${mode.size.height} ${mode.refreshMilliHz}"

    fun parseConfigs(reply: String?): DisplayConfigs {
        check(reply != null && reply.length <= 2048) { "Guest display refresh control unavailable; update the paired guest agent" }
        val fields = reply.split(' ')
        check(fields.size in 8..197 && (fields.size-2)%3 == 0 && fields.take(2) == listOf("OK","CONFIGS")) {
            "Invalid display configuration reply"
        }
        val modes = fields.drop(2).chunked(3).map { part ->
            check(part.take(2).all { it.matches(Regex("[0-9]{1,4}")) } && part[2].matches(Regex("[0-9]{1,6}"))) {
                "Invalid display mode fields"
            }
            try { DisplayMode(DisplaySize(part[0].toInt(),part[1].toInt()),part[2].toInt()) }
            catch (_: IllegalArgumentException) { error("Invalid display mode budget") }
        }
        val current=modes.first();val available=modes.drop(1)
        check(available.distinct().size == available.size && current in available) { "Inconsistent display configurations" }
        return DisplayConfigs(current,available)
    }

    fun validateResize(reply: String?, mode: DisplayMode) {
        check(reply == "OK CONFIG ${mode.size.width} ${mode.size.height} ${mode.refreshMilliHz}") {
            "Guest did not confirm requested mode: $reply"
        }
    }

    fun parseModes(reply: String?): DisplayModes {
        check(reply != null && reply.length <= 2048) { "Display mode reply unavailable or too large" }
        val fields = reply.split(' ')
        check(fields.size in 6..132 && fields.size % 2 == 0 && fields.take(2) == listOf("OK", "MODES")) {
            "Invalid display mode reply"
        }
        val sizes = fields.drop(2).chunked(2).map { pair ->
            check(pair.all { it.matches(Regex("[0-9]{1,4}")) }) { "Invalid display dimensions" }
            try { DisplaySize(pair[0].toInt(),pair[1].toInt()) }
            catch (_: IllegalArgumentException) { error("Invalid display dimensions") }
        }
        val current = sizes.first()
        val available = sizes.drop(1)
        check(available.distinct().size == available.size && current in available) { "Inconsistent display modes" }
        return DisplayModes(current, available)
    }

    fun validateResize(reply: String?, requested: DisplaySize) {
        check(reply == "OK SIZE ${requested.width} ${requested.height}") { "Guest did not confirm requested resolution: $reply" }
    }
}
