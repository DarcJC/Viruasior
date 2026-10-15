package pro.darc.viruaisor.gpu

internal object VenusGuestReport {
    fun negativeRejected(console: String): Boolean {
        if (console.contains("VENUS_GUEST_VK_ERROR") || console.contains("VENUS_GUEST_RESULT=PASS")) return false
        val lines = console.lineSequence().map { it.trimEnd('\r') }.toList()
        val mismatch = Regex("VENUS_GUEST_READBACK_MISMATCH pass=[0-2] words=256 incorrect=(\\d+) observed0=[0-9a-f]+ expected0=[0-9a-f]+")
        return hasSharedCachedMemory(console, lines) &&
            "VENUS_GUEST_SYNC_SKIPPED=true" in lines &&
            lines.any { line -> mismatch.matchEntire(line)?.groupValues?.get(1)?.toIntOrNull() in 1..256 } &&
            "VENUS_GUEST_NEGATIVE_CONTROL=EXPECTED_STALE_READBACK gpuFenceCompleted=true" in lines &&
            "VENUS_GUEST_RESULT=FAIL stage=negative-cache-readback" in lines
    }
    fun passed(console: String): Boolean {
        if (console.contains("VENUS_GUEST_RESULT=FAIL") || console.contains("VENUS_GUEST_SYNC_SKIPPED=true")) return false
        val lines = console.lineSequence().map { it.trimEnd('\r') }.toList()
        return hasSharedCachedMemory(console, lines) &&
            listOf("7", "12345678", "fedcba98").withIndex().all { (pass, seed) ->
                "VENUS_GUEST_READBACK pass=$pass seed=$seed words=256 correct=256" in lines
            } && "VENUS_GUEST_RESULT=PASS physicalGpu=true passes=3 wordsPerPass=256" in lines
    }
    private fun hasSharedCachedMemory(console: String, lines: List<String>): Boolean {
        val memory = Regex("VENUS_GUEST_MEMORY type=\\d+ flags=([0-9a-f]+) size=\\d+").find(console)
        val flags = memory?.groupValues?.get(1)?.toIntOrNull(16) ?: return false
        return flags and 10 == 10 && flags and 4 == 0 &&
            "VENUS_GUEST_CACHE_POLICY=explicit-noncoherent" in lines &&
            "VENUS_GUEST_PERF=no_fence_feedback,no_semaphore_feedback,no_event_feedback,no_query_feedback" in lines &&
            lines.any { it.contains("cap set 0: id 4, max-version 0, max-size 160") } &&
            lines.any { it.startsWith("VENUS_GUEST_DEVICE ") && it.contains(" vendor=13b5 type=1 driver=22 ") } &&
            "VENUS_GUEST_SHARED_MAPPING=true" in lines
    }
}
