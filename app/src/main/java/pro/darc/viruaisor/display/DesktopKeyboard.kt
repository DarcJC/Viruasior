package pro.darc.viruaisor.display

/** Android key codes enter here; only Linux evdev codes leave this boundary. */
internal class DesktopKeyboard(
    private val send: (Short, Boolean) -> Boolean,
    private val exit: () -> Unit,
) {
    private val pressed = linkedSetOf<Short>()
    private var swallowQUp = false
    fun key(keyCode: Int, scanCode: Int, down: Boolean,
            ctrl: Boolean = false, shift: Boolean = false, alt: Boolean = false): Boolean {
        if (keyCode in setOf(3,187) || keyCode == 4 && scanCode != 1) return false
        // Some keyboard layouts map physical evdev ESC (1) to Android Back. Preserve that ESC;
        // Android navigation Back (no scan code or evdev KEY_BACK) still belongs to the host.
        if (keyCode == 45 && !down && swallowQUp) { swallowQUp = false; return true }
        if (keyCode == 45 && down && !(ctrl && shift && alt)) swallowQUp = false
        if (keyCode == 45 && down && ctrl && shift && alt) {
            swallowQUp = true
            release()
            exit()
            return true
        }
        val code = scanCode.takeIf { it in 1..767 } ?: fallback[keyCode] ?: return false
        val evdev = code.toShort()
        if (down) pressed.add(evdev) else pressed.remove(evdev)
        send(evdev, down)
        // Consume a recognized key even if the transport failed; ESC must never become Back.
        return true
    }

    fun release() {
        val keys = pressed.toList()
        pressed.clear()
        keys.forEach { send(it, false) }
    }

    companion object {
        private val fallback = buildMap {
            val letters = intArrayOf(30,48,46,32,18,33,34,35,23,36,37,38,50,49,24,25,16,19,31,20,22,47,17,45,21,44)
            letters.forEachIndexed { i, code -> put(29 + i, code) }
            val digits = intArrayOf(11,2,3,4,5,6,7,8,9,10)
            digits.forEachIndexed { i, code -> put(7 + i, code) }
            putAll(mapOf(19 to 103,20 to 108,21 to 105,22 to 106,23 to 28,
                55 to 51,56 to 52,57 to 56,58 to 100,59 to 42,60 to 54,
                61 to 15,62 to 57,66 to 28,67 to 14,68 to 41,69 to 12,
                70 to 13,71 to 26,72 to 27,73 to 43,74 to 39,75 to 40,76 to 53,
                92 to 104,93 to 109,111 to 1,112 to 111,113 to 29,114 to 97,
                115 to 58,116 to 70,117 to 125,118 to 126,120 to 99,121 to 119,
                122 to 102,123 to 107,124 to 110,143 to 69,154 to 98,155 to 55,
                156 to 74,157 to 78,158 to 83,160 to 96,164 to 113))
            (0..9).forEach { put(144 + it, intArrayOf(82,79,80,81,75,76,77,71,72,73)[it]) }
            (0..9).forEach { put(131 + it, 59 + it) }
            put(141, 87); put(142, 88)
        }
    }
}
