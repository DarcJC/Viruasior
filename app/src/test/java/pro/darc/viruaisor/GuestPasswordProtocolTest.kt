package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import pro.darc.viruaisor.vm.GuestPasswordProtocol

class GuestPasswordProtocolTest {
    @Test fun encodesUtf8WithoutShellInterpretation() {
        val password="测试 : '$`\\"
        val command=GuestPasswordProtocol.command(password)
        assertTrue(command.startsWith("SET_DROID_PASSWORD "))
        assertEquals(password,String(java.util.Base64.getDecoder().decode(command.substringAfter(' ')),Charsets.UTF_8))
        assertFalse(command.contains(password))
    }
    @Test fun rejectsEmptyOversizedAndLineInjection() {
        for (password in listOf("","x".repeat(129),"测试".repeat(22),"abc\nroot:other","x\r","x\u0000","\uD800")) {
            assertThrows(IllegalArgumentException::class.java) { GuestPasswordProtocol.command(password) }
        }
    }
    @Test fun guestErrorDoesNotEchoUntrustedOrSensitiveReply() {
        val secret="secret-marker"
        val error=assertThrows(IllegalStateException::class.java) { GuestPasswordProtocol.confirm("ERROR $secret") }
        assertFalse(error.message.orEmpty().contains(secret))
        GuestPasswordProtocol.confirm("OK PASSWORD")
    }
}
