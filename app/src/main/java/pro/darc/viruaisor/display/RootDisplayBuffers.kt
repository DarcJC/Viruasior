package pro.darc.viruaisor.display

/** Owns a bounded set of display references. The caller serializes access. */
internal class RootDisplayBuffers<T : AutoCloseable>(private val capacity: Int) : AutoCloseable {
    private val entries = java.util.LinkedHashMap<Long, T>(capacity, 0.75f, true)
    private var width = 0
    private var height = 0
    private var closed = false

    init { require(capacity > 0) }

    fun get(id: Long, width: Int, height: Int, acquire: () -> T): T {
        check(!closed) { "Display references are closed" }
        if (this.width != width || this.height != height) {
            clear()
            this.width = width
            this.height = height
        }
        entries[id]?.let { return it }
        val value = acquire()
        entries[id] = value
        if (entries.size > capacity) {
            val iterator = entries.entries.iterator()
            val oldest = iterator.next().value
            iterator.remove()
            oldest.close()
        }
        return value
    }

    /** Release retained references when switching to a mode that does not use this cache. */
    fun clear() {
        if (entries.isEmpty()) return
        val values = entries.values.toList()
        entries.clear()
        var failure: Throwable? = null
        for (value in values) {
            try { value.close() } catch (error: Throwable) {
                val first = failure
                if (first == null) failure = error else if (first !== error) first.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    override fun close() {
        if (closed) return
        closed = true
        clear()
    }
}
