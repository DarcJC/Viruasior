package pro.darc.viruaisor.gpu

import org.junit.Assert.*
import org.junit.Test

class VenusHostReportTest {
    private fun complete(): String = buildString {
        appendLine("VENUS_HOST_BEGIN uid=10278 abi=arm64 protocol=1.1.3")
        appendLine("VENUS_DEVICE=Mali-G2-Ultra-NX MC16 type=1 vendor=5045 api=4211033")
        appendLine("VENUS_MEMORY_EXPORT=PASS fdType=1 size=4096")
        appendLine("VENUS_MEMORY_MAP=PASS size=4096")
        appendLine("VENUS_VK_RESULT=vkWaitForFences:0")
        repeat(16) { i ->
            val seed = 0xa5a50000u + i.toUInt()
            val expected = ((seed xor (0x9e3779b9u + i.toUInt() * 0x045d9f3bu)) * 1664525u) + 1013904223u
            appendLine("VENUS_WORD=$i actual=$expected expected=$expected")
        }
        appendLine("VENUS_COMPUTE_READBACK=PASS words=16")
        appendLine("VENUS_HOST_END exit=0")
    }

    @Test fun acceptsCompleteOrdinaryAppReadback() {
        assertTrue(VenusHostReport.isVerified(complete(), 0, 10278))
    }
    @Test fun rejectsRootOrAnotherUid() {
        assertFalse(VenusHostReport.isVerified(complete().replace("uid=10278", "uid=0"), 0, 10278))
    }
    @Test fun rejectsCollectorSuccessWithoutExecution() {
        assertFalse(VenusHostReport.isVerified("VENUS_HOST_END exit=0", 0, 10278))
    }
    @Test fun rejectsUnchangedOrInventedExpectedWords() {
        assertFalse(VenusHostReport.isVerified(complete().replace("actual=2188367812", "actual=2779054080"), 0, 10278))
        assertFalse(VenusHostReport.isVerified(complete().replace("2188367812", "1"), 0, 10278))
    }
    @Test fun rejectsCrashAfterSuccessfulReadbackOrMissingWord() {
        assertFalse(VenusHostReport.isVerified(complete(), 134, 10278))
        assertFalse(VenusHostReport.isVerified(complete().replace(Regex("VENUS_WORD=15[^\n]*\n"), ""), 0, 10278))
        assertFalse(VenusHostReport.isVerified(complete() + "VENUS_FAILURE cleanup", 0, 10278))
    }
}
