package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import pro.darc.viruaisor.display.DesktopKeyboard

class DesktopKeyboardTest {
    @Test fun aPhysicalEscapeMappedToAndroidBackStillReachesTheGuest() {
        val events=mutableListOf<Short>()
        val keyboard=DesktopKeyboard({ code,_ -> events.add(code); true },{})
        assertTrue(keyboard.key(4,1,true))
        assertEquals(listOf(1.toShort()),events)
        assertFalse(keyboard.key(4,0,true))
    }
    @Test fun doubleEscapeIsForwardedWithoutLeavingCapture() {
        val events = mutableListOf<Pair<Short, Boolean>>()
        var exits = 0
        val keyboard = DesktopKeyboard({ code, down -> events.add(code to down); true }, { exits++ })
        repeat(2) {
            assertTrue(keyboard.key(111, 1, true))
            assertTrue(keyboard.key(111, 1, false))
        }
        assertEquals(listOf(1.toShort() to true, 1.toShort() to false, 1.toShort() to true, 1.toShort() to false), events)
        assertEquals(0, exits)
    }

    @Test fun exitChordReleasesModifiersAndDoesNotSendQ() {
        val events = mutableListOf<Pair<Short, Boolean>>()
        var exits = 0
        val keyboard = DesktopKeyboard({ code, down -> events.add(code to down); true }, { exits++ })
        keyboard.key(113, 29, true)
        keyboard.key(59, 42, true)
        keyboard.key(57, 56, true)
        assertTrue(keyboard.key(45, 16, true, ctrl = true, shift = true, alt = true))
        assertTrue(keyboard.key(45, 16, false))
        assertEquals(1, exits)
        assertFalse(events.any { it.first == 16.toShort() })
        assertEquals(listOf(29, 42, 56), events.filter { !it.second }.map { it.first.toInt() })
    }

    @Test fun androidBackAndHomeRemainAvailable() {
        val keyboard = DesktopKeyboard({ _, _ -> fail("System navigation must not reach guest"); false }, {})
        assertFalse(keyboard.key(4, 158, true))
        assertFalse(keyboard.key(3, 102, true))
    }

    @Test fun missingScanCodeUsesLinuxKeyCodesAndUnknownKeysAreNotInvented() {
        val events = mutableListOf<Short>()
        val keyboard = DesktopKeyboard({ code, _ -> events.add(code); true }, {})
        assertTrue(keyboard.key(29, 0, true)) // Android A=29, Linux A=30
        assertTrue(keyboard.key(111, 0, true)) // Android ESC=111, Linux ESC=1
        assertFalse(keyboard.key(0, 0, true))
        assertEquals(listOf(30.toShort(), 1.toShort()), events)
    }

    @Test fun focusLossReleasesEachPressedKeyOnce() {
        val events = mutableListOf<Pair<Short, Boolean>>()
        val keyboard = DesktopKeyboard({ code, down -> events.add(code to down); true }, {})
        keyboard.key(29, 30, true)
        keyboard.key(29, 30, true) // repeat
        keyboard.release()
        keyboard.release()
        assertEquals(1, events.count { it == 30.toShort() to false })
    }
}
