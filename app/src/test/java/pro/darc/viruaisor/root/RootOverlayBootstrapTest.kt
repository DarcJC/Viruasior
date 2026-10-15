package pro.darc.viruaisor.root

import org.junit.Assert.*
import org.junit.Test

class RootOverlayBootstrapTest {
    @Test fun separatesBootstrapNamespaceBeforeStartingLibsuService() {
        assertEquals(listOf("su", "-c", "exec /system/bin/unshare -m /system/bin/sh"), RootOverlayBootstrap.commands("su"))
    }
    @Test fun refusesArbitraryExecutablesAndShellSyntax() {
        for (path in listOf("/data/local/tmp/su", "su --mount-master", "su; id", "")) {
            assertThrows(SecurityException::class.java) { RootOverlayBootstrap.commands(path) }
        }
    }
}
