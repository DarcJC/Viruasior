package pro.darc.viruaisor

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AppIdentityTest {
    @Test fun installedIdControlsActionsAndExactProcessNames() {
        assertEquals(BuildConfig.APPLICATION_ID + ".action.", AppIdentity.ACTION_PREFIX)
        assertEquals("org.example.virtualmachine.debug:venus_vm", AppIdentity.vmProcess("org.example.virtualmachine.debug"))
        assertEquals("org.example.virtualmachine.debug:root:0", AppIdentity.rootProcess("org.example.virtualmachine.debug"))
        for (value in listOf("", "../other", "a.b/x", "a.b;id", "a.b\n", "a..b", "a")) {
            assertThrows(IllegalArgumentException::class.java) { AppIdentity.requirePackageName(value) }
        }
    }

    @Test fun snapshotScopeFollowsTrustedFilesDirAndItsCanonicalAlias() {
        val root = Files.createTempDirectory("app-identity").toFile()
        try {
            val files = File(root, "org.example.vm/files").apply { mkdirs() }
            val alias = File(root, "alias")
            Files.createSymbolicLink(alias.toPath(), files.toPath())
            val leaf = "0123456789abcdef0123456789abcdef.snapshot"
            assertTrue(AppIdentity.isOverlaySnapshot(files, File(files, "venus-overlay-lab/$leaf").path))
            assertTrue(AppIdentity.isOverlaySnapshot(files, File(alias, "venus-overlay-lab/$leaf").path))
            assertFalse(AppIdentity.isOverlaySnapshot(files, File(root, "other/files/venus-overlay-lab/$leaf").path))
            assertFalse(AppIdentity.isOverlaySnapshot(files, File(files, "venus-overlay-lab/bad.snapshot").path))
        } finally { root.deleteRecursively() }
    }
}
