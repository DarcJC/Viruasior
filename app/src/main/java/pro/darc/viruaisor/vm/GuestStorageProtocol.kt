package pro.darc.viruaisor.vm

internal data class GuestStorageStatus(val rootBytes: Long, val mounted: Boolean)
internal object GuestStorageProtocol {
    fun parse(response: String?): GuestStorageStatus {
        val match = Regex("OK STORAGE ([0-9]+) ([01])").matchEntire(response.orEmpty())
            ?: error("Guest storage status unavailable; update the paired avf-agent.")
        val size = match.groupValues[1].toLongOrNull()
        check(size != null && size > 0 && size % 512 == 0L) { "Invalid guest root device size." }
        return GuestStorageStatus(size, match.groupValues[2] == "1")
    }
}
