package pro.darc.viruaisor.root

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

class RootOverlayChildCleanupTest {
    @Test fun permitsBoundedMultiChildAndMountReleaseDrain() {
        withChild(1500) { child, marker ->
            child.outputStream.close()
            RootOverlayChildCleanup.finish(child)
            assertTrue("All descendant exit and mount-reference draining needs a bounded grace", marker.isFile)
            assertFalse(child.isAlive)
        }
    }
    @Test fun allowsOwnerDeathCleanupToCompleteBeforeKillingChild() {
        withChild(200) { child, marker ->
            child.outputStream.close()
            RootOverlayChildCleanup.finish(child)
            assertTrue("Native-style cleanup must finish before helper shutdown", marker.isFile)
            assertEquals("CLEAN", marker.readText())
            assertFalse(child.isAlive)
        }
    }
    @Test fun stillTerminatesAStuckChildWithinBoundedGrace() {
        withChild(10000) { child, marker ->
            child.outputStream.close()
            val started = System.nanoTime()
            RootOverlayChildCleanup.finish(child)
            assertTrue(child.waitFor(1, TimeUnit.SECONDS))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000)
            assertFalse(marker.exists())
        }
    }
    private fun withChild(delayMs: Int, check: (Process, File) -> Unit) {
        val directory = Files.createTempDirectory("viruaisor-overlay-child-").toFile()
        val marker = File(directory, "cleanup.txt")
        val java = File(requireNotNull(System.getProperty("java.home")), "bin/" + if (System.getProperty("os.name").orEmpty().startsWith("Windows")) "java.exe" else "java")
        val source = requireNotNull(requireNotNull(OverlayCleanupFixture::class.java.protectionDomain).codeSource)
        val classes = File(source.location.toURI())
        val child = ProcessBuilder(java.path, "-cp", classes.path, OverlayCleanupFixture::class.java.name, marker.path, delayMs.toString())
            .redirectErrorStream(true).start()
        try {
            assertEquals("READY", child.inputStream.bufferedReader().readLine())
            check(child, marker)
        } finally {
            child.destroyForcibly()
            child.waitFor(1, TimeUnit.SECONDS)
            marker.delete(); directory.delete()
        }
    }
}
