package pro.darc.viruaisor.vm

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

internal interface RootfsTools {
    fun check(image: File)
    fun minimumBytes(image: File): Long
    fun resize(image: File, targetBytes: Long)
    fun filesystemBytes(image: File): Long
}

internal class RootfsTransaction(
    private val image: File,
    private val tools: RootfsTools,
    private val syncDirectory: (File) -> Unit,
    private val exchange: (File, File) -> Unit,
    private val checkpoint: (String) -> Unit = {},
) {
    private val directory = File(image.parentFile, ".rootfs-resize")
    private val journal = File(directory, "journal")
    private val candidate = File(directory, "candidate")
    private val original = File(directory, "original")
    val pending: Boolean get() = directory.exists()
    val awaitingBoot: Boolean get() {
        validatePaths()
        if (!pending || !journal.exists()) return false
        val record = read()
        return record.phase == "switched" && original.isFile && original.length() == record.before &&
            image.isFile && image.length() == record.after && tools.filesystemBytes(image) in 1..record.after
    }

    private data class Record(val phase: String, val name: String, val before: Long, val after: Long)

    fun resize(targetBytes: Long) {
        validatePaths()
        check(!pending) { "Recover or verify the previous rootfs operation first." }
        require(targetBytes > 0 && targetBytes % 4096 == 0L) { "Capacity must be a positive multiple of 4096 bytes." }
        require(targetBytes != image.length()) { "Rootfs already has this capacity." }
        require(tools.filesystemBytes(image) <= image.length()) { "Invalid ext4 image length." }
        check(directory.mkdir()) { "Cannot create rootfs transaction directory." }
        syncDirectory(requireNotNull(directory.parentFile))
        val record = Record("preparing", image.name, image.length(), targetBytes)
        write(record)
        checkpoint("preparing")
        sparseCopy(image, candidate)
        checkpoint("copied")
        tools.check(candidate)
        checkpoint("checked")
        require(targetBytes >= tools.minimumBytes(candidate)) { "Target capacity is below the safe ext4 minimum." }
        if (targetBytes > candidate.length()) setLength(candidate, targetBytes)
        tools.resize(candidate, targetBytes)
        tools.check(candidate)
        val filesystemBytes = tools.filesystemBytes(candidate)
        check(filesystemBytes in 1..targetBytes) { "Resized ext4 exceeds the target; refusing truncation." }
        check(filesystemBytes == targetBytes) { "Ext4 did not reach the requested capacity; original retained." }
        setLength(candidate, targetBytes)
        tools.check(candidate)
        write(record.copy(phase = "ready"))
        checkpoint("ready")
        // Keep the active pathname continuously present without Android's restricted hard links.
        exchange(candidate, image)
        syncDirectory(requireNotNull(image.parentFile))
        syncDirectory(directory)
        checkpoint("exchanged")
        replace(candidate, original)
        checkpoint("backed-up")
        checkpoint("activated")
        write(record.copy(phase = "switched"))
        checkpoint("switched")
    }

    /** Cold-start recovery always rolls back an activation not yet proven to boot. */
    fun recover(): Boolean {
        validatePaths()
        if (!pending) return false
        if (!journal.exists()) {
            check(!original.exists() && image.isFile) { "Rootfs recovery journal is missing; original retained." }
            cleanup()
            return true
        }
        val record = read()
        if (record.phase == "verified") {
            check(image.isFile && image.length() == record.after) { "Verified rootfs is missing; backup retained." }
            cleanup()
            return false
        }
        if (original.exists()) {
            check(original.isFile && original.length() == record.before) { "Invalid rootfs backup; recovery stopped." }
            write(record.copy(phase = "restoring"))
            replace(original, image)
            checkpoint("restored")
        } else if (record.phase == "ready" && candidate.isFile && image.isFile) {
            // The exchange may be durable before the old inode was renamed to original.
            if (image.length() == record.after && candidate.length() == record.before) {
                exchange(candidate,image)
                syncDirectory(requireNotNull(image.parentFile))
                syncDirectory(directory)
                checkpoint("restored")
            }
            check(image.length() == record.before && candidate.length() == record.after) {
                "Ambiguous rootfs exchange; recovery files retained."
            }
        } else {
            check(record.phase == "preparing" || record.phase == "restoring" || (record.phase == "ready" && candidate.exists())) {
                "Rootfs original is missing; manual recovery is required."
            }
            check(image.isFile && image.length() == record.before) { "Original rootfs is missing or changed." }
        }
        cleanup()
        return true
    }

    fun confirmBoot() {
        validatePaths()
        if (!pending) return
        val record = read()
        check(record.phase == "switched" && original.isFile && image.length() == record.after) {
            "Rootfs activation is incomplete; backup retained."
        }
        check(tools.filesystemBytes(image) in 1..record.after) { "Booted rootfs has invalid geometry." }
        write(record.copy(phase = "verified"))
        checkpoint("verified")
        cleanup()
    }

    private fun validatePaths() {
        require(image.name.matches(Regex("[A-Za-z0-9_.-]+")) && image.name !in setOf(".", "..")) { "Unsafe rootfs filename." }
        for (file in listOf(image, directory, journal, candidate, original, File(directory, "journal.tmp"))) {
            check(file.canonicalFile == file.absoluteFile.normalize()) { "Symbolic links are not allowed in rootfs transactions." }
        }
        if (directory.exists()) check(directory.list()?.all { it in setOf("journal", "journal.tmp", "candidate", "original") } == true) {
            "Unexpected rootfs recovery files; backup retained."
        }
    }

    private fun read(): Record {
        check(journal.length() in 1..1024) { "Invalid rootfs recovery journal length; backup retained." }
        val fields = journal.readText().trimEnd('\n').split('\n')
        check(fields.size == 5 && fields[0] == "1" && fields[2] == image.name) { "Invalid rootfs recovery journal; backup retained." }
        check(fields[1] in setOf("preparing", "ready", "switched", "verified", "restoring")) { "Unknown rootfs recovery phase." }
        val before = fields[3].toLongOrNull()
        val after = fields[4].toLongOrNull()
        check(before != null && after != null && before > 0 && after > 0 && after % 4096 == 0L) { "Invalid recovery sizes." }
        return Record(fields[1], fields[2], before, after)
    }

    private fun write(record: Record) {
        val staging = File(directory, "journal.tmp")
        FileOutputStream(staging).use {
            it.write("1\n${record.phase}\n${record.name}\n${record.before}\n${record.after}\n".toByteArray())
            it.fd.sync()
        }
        replace(staging, journal)
    }

    private fun replace(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        syncDirectory(requireNotNull(target.parentFile))
        if (source.parentFile != target.parentFile) syncDirectory(requireNotNull(source.parentFile))
    }

    private fun cleanup() {
        // Only our fixed artifact names are removed; unexpected files stop cleanup.
        for (file in listOf(candidate, original, File(directory, "journal.tmp"))) Files.deleteIfExists(file.toPath())
        syncDirectory(directory)
        Files.deleteIfExists(journal.toPath())
        syncDirectory(directory)
        Files.delete(directory.toPath())
        syncDirectory(requireNotNull(directory.parentFile))
    }

    private fun setLength(file: File, length: Long) {
        RandomAccessFile(file, "rw").use { it.setLength(length); it.fd.sync() }
    }

    companion object {
        fun sparseCopy(source: File, target: File) {
            FileInputStream(source).use { input ->
                sparseCopy(input, target)
            }
        }

        fun sparseCopy(input: InputStream, target: File) {
            RandomAccessFile(target, "rw").use { output ->
                output.setLength(0)
                val buffer = ByteArray(1024 * 1024)
                var offset = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if ((0 until count).any { buffer[it] != 0.toByte() }) {
                        output.seek(offset)
                        output.write(buffer, 0, count)
                    }
                    offset += count
                }
                output.setLength(offset)
                output.fd.sync()
            }
        }
    }
}
