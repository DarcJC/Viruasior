package pro.darc.viruaisor.vm

data class DisplaySize(val width: Int, val height: Int) {
    init {
        require(width in 1..4096 && height in 1..4096 && width.toLong()*height <= 4_194_304L) {
            "Display size exceeds the pixel budget"
        }
    }
    override fun toString() = "${width}×${height}"
}

data class DisplayModes(val current: DisplaySize, val available: List<DisplaySize>)
data class VmDisplayState(
    val modes: List<DisplaySize> = emptyList(),
    val actual: DisplaySize? = null,
    val presented: DisplaySize? = null,
    val requested: DisplaySize? = null,
    val error: String? = null,
) {
    val switching get() = requested != null && requested != presented
}

internal object GuestDisplayProtocol {
    fun resize(size: DisplaySize) = "RESIZE ${size.width} ${size.height}"

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
