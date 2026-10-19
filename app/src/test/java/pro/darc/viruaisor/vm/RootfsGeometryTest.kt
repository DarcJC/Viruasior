package pro.darc.viruaisor.vm

import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RootfsGeometryTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun readsExt4GeometryAndRejectsTruncatedImage() {
        val image = temporary.newFile()
        val superblock = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN)
        superblock.putInt(4, 2048)
        superblock.putInt(24, 2)
        superblock.putShort(56, 0xef53.toShort())
        RandomAccessFile(image,"rw").use { it.setLength(8388608); it.seek(1024); it.write(superblock.array()) }
        assertEquals(8388608, Ext4Geometry.bytes(image))
        RandomAccessFile(image,"rw").use { it.setLength(4096) }
        assertThrows(IllegalArgumentException::class.java) { Ext4Geometry.bytes(image) }
    }
    @Test fun rejectsNonExt4AndInvalidCapacityInput() {
        assertThrows(IllegalArgumentException::class.java) { Ext4Geometry.bytes(temporary.newFile()) }
        assertEquals(16L * 1024 * 1024 * 1024, RootfsCapacity.parseGiB("16"))
        for (value in listOf("0","-1","1.5","999999999999999999999","abc")) {
            assertThrows(IllegalArgumentException::class.java) { RootfsCapacity.parseGiB(value) }
        }
    }
}
