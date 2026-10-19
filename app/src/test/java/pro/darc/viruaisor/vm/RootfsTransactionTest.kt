package pro.darc.viruaisor.vm

import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsTransactionTest {
    @get:Rule val temporary = TemporaryFolder()
    private val image by lazy { temporary.newFile("root_part").apply { writeBytes(ByteArray(8192) { 42 }) } }
    private var failCheck = false
    private var failResize = false
    private val tools = object : RootfsTools {
        override fun check(image: File) { check(!failCheck) { "bad filesystem" } }
        override fun minimumBytes(image: File) = 4096L
        override fun resize(image: File, targetBytes: Long) {
            check(!failResize) { "resize failed" }
            RandomAccessFile(image, "rw").use { it.setLength(targetBytes) }
        }
        override fun filesystemBytes(image: File) = image.length()
    }
    private fun transaction(fault: (String) -> Unit = {}) = RootfsTransaction(image, tools, {}, ::exchangeFixture, fault)

    @Test fun failedResizeNeverChangesOriginal() {
        val before = image.readBytes()
        failResize = true
        assertThrows(IllegalStateException::class.java) { transaction().resize(16384) }
        assertArrayEquals(before, image.readBytes())
        failResize = false
        transaction().recover()
        assertFalse(transaction().pending)
    }

    @Test fun growAndShrinkKeepOriginalUntilBootVerification() {
        val original = image.readBytes()
        transaction().resize(16384)
        assertEquals(16384, image.length())
        assertTrue(transaction().pending)
        transaction().recover()
        assertArrayEquals(original, image.readBytes())
        transaction().resize(4096)
        assertEquals(4096, image.length())
        transaction().confirmBoot()
        assertFalse(transaction().pending)
        assertFalse(transaction().recover())
        assertEquals(4096, image.length())
    }

    @Test fun everyInterruptedPhaseRecoversOriginalBytes() {
        for (phase in listOf("preparing", "copied", "checked", "ready", "exchanged", "backed-up", "activated", "switched")) {
            val original = image.readBytes()
            assertThrows(IllegalStateException::class.java) {
                transaction { if (it == phase) error("power loss at $phase") }.resize(16384)
            }
            transaction().recover()
            assertArrayEquals("phase=$phase", original, image.readBytes())
            assertFalse(transaction().pending)
        }
    }

    @Test fun insufficientMinimumAndUnresolvedBackupAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { transaction().resize(2048) }
        transaction().recover()
        transaction().resize(16384)
        assertThrows(IllegalStateException::class.java) { transaction().resize(32768) }
        assertEquals(16384, image.length())
    }

    @Test fun rollbackAfterExchangeCanBeInterruptedAndRetried() {
        val original=image.readBytes()
        assertThrows(IllegalStateException::class.java) {
            transaction { if (it == "exchanged") error("power loss") }.resize(16384)
        }
        assertThrows(IllegalStateException::class.java) {
            transaction { if (it == "restored") error("power loss during exchange recovery") }.recover()
        }
        transaction().recover()
        assertArrayEquals(original,image.readBytes())
        assertFalse(transaction().pending)
    }

    @Test fun failedFinalCheckCannotActivateCandidate() {
        val original = image.readBytes()
        assertThrows(IllegalStateException::class.java) {
            transaction { if (it == "checked") failCheck = true }.resize(16384)
        }
        transaction().recover()
        assertArrayEquals(original, image.readBytes())
    }

    @Test fun missingOriginalDuringActivationFailsClosed() {
        transaction().resize(16384)
        File(image.parentFile, ".rootfs-resize/original").delete()
        assertThrows(IllegalStateException::class.java) { transaction().recover() }
        assertTrue(transaction().pending)
    }

    @Test fun malformedJournalCannotDeleteRecoveryFiles() {
        transaction().resize(16384)
        File(image.parentFile, ".rootfs-resize/journal").writeText("broken")
        assertThrows(IllegalStateException::class.java) { transaction().recover() }
        assertTrue(File(image.parentFile, ".rootfs-resize/original").exists())
    }

    @Test fun durableVerificationCanFinishCleanupAfterInterruption() {
        transaction().resize(16384)
        assertThrows(IllegalStateException::class.java) {
            transaction { if (it == "verified") error("power loss") }.confirmBoot()
        }
        transaction().recover()
        assertEquals(16384, image.length())
        assertFalse(transaction().pending)
    }

    @Test fun rollbackItselfCanBeInterruptedAndRetried() {
        val original = image.readBytes()
        transaction().resize(16384)
        assertThrows(IllegalStateException::class.java) {
            transaction { if (it == "restored") error("power loss") }.recover()
        }
        transaction().recover()
        assertArrayEquals(original, image.readBytes())
    }

    @Test fun completedActivationCanBeRecognizedAfterReopening() {
        transaction().resize(16384)
        assertTrue(transaction().awaitingBoot)
        transaction().recover()
        assertFalse(transaction().awaitingBoot)
    }

    @Test fun refusedShrinkNeverTruncatesTheFilesystem() {
        val original = image.readBytes()
        val refusing = object : RootfsTools by tools {
            override fun resize(image: File, targetBytes: Long) = Unit
        }
        assertThrows(IllegalStateException::class.java) { RootfsTransaction(image,refusing,{},::exchangeFixture).resize(4096) }
        assertArrayEquals(original,image.readBytes())
        transaction().recover()
        assertArrayEquals(original,image.readBytes())
    }

    @Test fun unchangedFilesystemCannotBeAcceptedAsSuccessfulGrowth() {
        val unchanged = object : RootfsTools by tools {
            override fun filesystemBytes(image: File) = 8192L
        }
        assertThrows(IllegalStateException::class.java) { RootfsTransaction(image,unchanged,{},::exchangeFixture).resize(16384) }
        assertEquals(8192,image.length())
        transaction().recover()
    }

    @Test fun sparseImportPreservesZeroGapsAndTrailingZeroBytes() {
        val bytes = ByteArray(2*1024*1024+4096)
        bytes[1024*1024+42] = 17
        val output = temporary.newFile("sparse")
        RootfsTransaction.sparseCopy(bytes.inputStream(),output)
        assertArrayEquals(bytes,output.readBytes())
    }
}
