package pro.darc.viruaisor.gpu

import org.junit.Assert.*
import org.junit.Test

class VenusGuestReportTest {
    private val good = """
        [drm] cap set 0: id 4, max-version 0, max-size 160
        VENUS_GUEST_DEVICE name=Virtio-GPU Venus (Mali) vendor=13b5 type=1 driver=22 driverName=venus
        VENUS_GUEST_CACHE_POLICY=explicit-noncoherent
        VENUS_GUEST_PERF=no_fence_feedback,no_semaphore_feedback,no_event_feedback,no_query_feedback
        VENUS_GUEST_MEMORY type=15 flags=b size=1024
        VENUS_GUEST_SHARED_MAPPING=true
        VENUS_GUEST_READBACK pass=0 seed=7 words=256 correct=256
        VENUS_GUEST_READBACK pass=1 seed=12345678 words=256 correct=256
        VENUS_GUEST_READBACK pass=2 seed=fedcba98 words=256 correct=256
        VENUS_GUEST_RESULT=PASS physicalGpu=true passes=3 wordsPerPass=256
    """.trimIndent()
    @Test fun acceptsOnlyFullGuestEvidence() {
        assertTrue(VenusGuestReport.passed(good))
        for (missing in listOf("cap set", "VENUS_GUEST_DEVICE", "VENUS_GUEST_SHARED_MAPPING", "pass=1", "VENUS_GUEST_RESULT")) {
            assertFalse(VenusGuestReport.passed(good.lineSequence().filterNot { it.contains(missing) }.joinToString("\n")))
        }
    }
    @Test fun rejectsHostOnlySoftwareStaleAndFailedResults() {
        assertFalse(VenusGuestReport.passed("VENUS_HOST_RESULT=PASS"))
        assertFalse(VenusGuestReport.passed(good.replace("vendor=13b5", "vendor=10005")))
        assertFalse(VenusGuestReport.passed(good.replace("type=1", "type=4")))
        assertFalse(VenusGuestReport.passed(good.replace("seed=12345678", "seed=7")))
        assertFalse(VenusGuestReport.passed(good.replace("correct=256", "correct=255")))
        assertFalse(VenusGuestReport.passed(good + "\nVENUS_GUEST_RESULT=FAIL"))
    }
    @Test fun requiresRealNoncoherentCachedMemoryAndEnabledSync() {
        assertFalse(VenusGuestReport.passed(good.replace("VENUS_GUEST_PERF=", "MISSING_PERF=")))
        assertFalse(VenusGuestReport.passed(good.replace("flags=b", "flags=7")))
        assertFalse(VenusGuestReport.passed(good.replace("VENUS_GUEST_CACHE_POLICY=explicit-noncoherent", "")))
        assertFalse(VenusGuestReport.passed(good + "\nVENUS_GUEST_SYNC_SKIPPED=true"))
    }
    @Test fun negativeControlMustCompleteGpuWorkThenDetectStaleReadback() {
        val negative = good.lineSequence().filterNot { it.startsWith("VENUS_GUEST_READBACK") || it.startsWith("VENUS_GUEST_RESULT") }.joinToString("\n") + "\n" + """

            VENUS_GUEST_SYNC_SKIPPED=true
            VENUS_GUEST_READBACK_MISMATCH pass=0 words=256 incorrect=256 observed0=7 expected0=1234
            VENUS_GUEST_NEGATIVE_CONTROL=EXPECTED_STALE_READBACK gpuFenceCompleted=true
            VENUS_GUEST_RESULT=FAIL stage=negative-cache-readback
        """.trimIndent()
        assertTrue(VenusGuestReport.negativeRejected(negative))
        assertFalse(VenusGuestReport.negativeRejected(good))
        assertFalse(VenusGuestReport.negativeRejected(negative.replace("gpuFenceCompleted=true", "gpuFenceCompleted=false")))
        assertFalse(VenusGuestReport.negativeRejected(negative + "\nVENUS_GUEST_VK_ERROR=-5"))
        assertFalse(VenusGuestReport.negativeRejected(negative.replace("flags=b", "flags=7")))
    }
}
