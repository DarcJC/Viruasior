package pro.darc.viruaisor.display

/** Android IDs may be 0..31; the AVF virtio trackpad has ten slots. */
internal class TrackpadContacts {
    private val slots=linkedMapOf<Int,Int>()
    val ids get() = slots.keys.toSet()
    fun assign(ids: List<Int>): IntArray {
        require(ids.size <= 10 && ids.distinct().size == ids.size && ids.all { it in 0..31 })
        require(slots.size+ids.count { it !in slots } <= 10)
        return ids.map { id -> slots.getOrPut(id) { (0..9).first { it !in slots.values } } }.toIntArray()
    }
    fun lift(id: Int) { slots.remove(id) }
    fun clear() { slots.clear() }
}
