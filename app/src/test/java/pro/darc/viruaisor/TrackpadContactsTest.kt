package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import pro.darc.viruaisor.display.TrackpadContacts

class TrackpadContactsTest {
    @Test fun arbitraryAndroidIdsUseStableBoundedVirtualSlots() {
        val state=TrackpadContacts()
        assertArrayEquals(intArrayOf(0,1),state.assign(listOf(18,3)))
        state.lift(18)
        assertArrayEquals(intArrayOf(1,0),state.assign(listOf(3,22)))
        assertEquals(setOf(3,22),state.ids)
        state.clear()
        assertTrue(state.ids.isEmpty())
        assertArrayEquals(intArrayOf(0),state.assign(listOf(31)))
    }
    @Test fun malformedFramesDoNotPartiallyAllocateContacts() {
        val state=TrackpadContacts()
        state.assign(listOf(2))
        for(ids in listOf((0..10).toList(),listOf(2,2),listOf(-1),listOf(32))) {
            assertThrows(IllegalArgumentException::class.java) { state.assign(ids) }
            assertEquals(setOf(2),state.ids)
        }
    }
}
