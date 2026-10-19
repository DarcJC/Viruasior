package pro.darc.viruaisor.vm

import org.junit.Assert.*
import org.junit.Test

class GuestStorageProtocolTest {
    @Test fun acceptsOnlyBoundedPositiveRootSizeAndBooleanMountState() {
        assertEquals(GuestStorageStatus(8589934592L,true), GuestStorageProtocol.parse("OK STORAGE 8589934592 1"))
        for (text in listOf(null, "READY", "OK STORAGE 0 1", "OK STORAGE -1 0", "OK STORAGE 4096 2", "OK STORAGE 4096 1 extra")) {
            assertThrows(IllegalStateException::class.java) { GuestStorageProtocol.parse(text) }
        }
    }
}
