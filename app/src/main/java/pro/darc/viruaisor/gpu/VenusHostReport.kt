package pro.darc.viruaisor.gpu

object VenusHostReport {
    fun isVerified(report: String, exitCode: Int, expectedUid: Int): Boolean {
        if (exitCode != 0 || expectedUid < 10000 || "VENUS_FAILURE" in report) return false
        val lines = report.lineSequence().map(String::trim).toList()
        if (lines.count { it.startsWith("VENUS_HOST_BEGIN ") } != 1 ||
            lines.none { it.startsWith("VENUS_HOST_BEGIN uid=$expectedUid ") } ||
            lines.none { it.startsWith("VENUS_DEVICE=Mali") && "type=1 vendor=5045 " in it } ||
            lines.none { it.startsWith("VENUS_MEMORY_EXPORT=PASS ") } ||
            lines.none { it.startsWith("VENUS_MEMORY_MAP=PASS ") } ||
            "VENUS_VK_RESULT=vkWaitForFences:0" !in lines ||
            "VENUS_COMPUTE_READBACK=PASS words=16" !in lines ||
            lines.count { it == "VENUS_HOST_END exit=0" } != 1) return false
        val words = lines.filter { it.startsWith("VENUS_WORD=") }
        if (words.size != 16) return false
        return words.withIndex().all { (i, line) ->
            val seed = 0xa5a50000u + i.toUInt()
            val expected = ((seed xor (0x9e3779b9u + i.toUInt() * 0x045d9f3bu)) * 1664525u) + 1013904223u
            line == "VENUS_WORD=$i actual=$expected expected=$expected"
        }
    }
}
