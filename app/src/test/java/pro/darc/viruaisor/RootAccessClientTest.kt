package pro.darc.viruaisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.darc.viruaisor.root.ProcessRootCommandExecutor
import pro.darc.viruaisor.root.RootAccess
import pro.darc.viruaisor.root.RootAccessClient
import pro.darc.viruaisor.root.RootCommandExecutor
import pro.darc.viruaisor.root.RootCommandResult
import java.io.File
import java.io.IOException

class RootAccessClientTest {
    private val rootOutput =
        "VIRUAISOR_ROOT_UID=0\nuid=0(root) gid=0(root) context=u:r:su:s0\nVIRUAISOR_SELINUX=Enforcing\n"

    @Test
    fun kernelSuGrantRequiresActualUidZeroAndPreservesDiagnostics() {
        val calls = mutableListOf<List<String>>()
        val client = RootAccessClient(RootCommandExecutor { command, timeout ->
            calls += command
            if (command[1] == "-c") {
                assertEquals(60L, timeout)
                assertTrue(command[2].contains("id -u"))
                assertTrue(command[2].contains("getenforce"))
                RootCommandResult(0, rootOutput)
            } else {
                assertEquals(listOf("su", "-v"), command)
                RootCommandResult(0, "3.3.0:KernelSU\n")
            }
        })

        val result = client.request() as RootAccess.Granted

        assertEquals("su", result.suPath)
        assertEquals("3.3.0:KernelSU", result.implementation)
        assertTrue(result.identity.startsWith("uid=0(root)"))
        assertEquals("Enforcing", result.selinuxMode)
        assertEquals(2, calls.size)
        assertTrue(calls.none { it.joinToString(" ").contains("setenforce") })
    }

    @Test
    fun zeroExitWithoutRootUidIsNotAGrant() {
        val result = RootAccessClient(RootCommandExecutor { _, _ ->
            RootCommandResult(0, "VIRUAISOR_ROOT_UID=2000\nuid=2000(shell)\n")
        }).request()

        assertTrue(result is RootAccess.Denied)
    }

    @Test
    fun zeroExitWithoutUidMarkerIsNotAGrant() {
        val result = RootAccessClient(RootCommandExecutor { _, _ ->
            RootCommandResult(0, "KernelSU is installed\n")
        }).request()

        assertTrue(result is RootAccess.Denied)
    }

    @Test
    fun nonzeroExitEvenWithUidZeroIsNotAGrant() {
        val result = RootAccessClient(RootCommandExecutor { _, _ ->
            RootCommandResult(1, rootOutput)
        }).request()

        assertTrue(result is RootAccess.Denied)
    }

    @Test
    fun denialStopsWithoutTryingEverySuPathOrPromptingAgain() {
        var calls = 0
        val result = RootAccessClient(RootCommandExecutor { _, _ ->
            calls++
            RootCommandResult(1, "Permission denied\n")
        }).request()

        assertTrue(result is RootAccess.Denied)
        assertTrue((result as RootAccess.Denied).reason.contains("KernelSU"))
        assertEquals(1, calls)
    }

    @Test
    fun missingPathFallsBackToSystemBinSu() {
        val calls = mutableListOf<List<String>>()
        val result = RootAccessClient(RootCommandExecutor { command, _ ->
            calls += command
            if (command[0] == "su") throw IOException("No such file or directory")
            if (command[1] == "-c") RootCommandResult(0, rootOutput)
            else RootCommandResult(0, "3.3.0:KernelSU")
        }).request() as RootAccess.Granted

        assertEquals("/system/bin/su", result.suPath)
        assertEquals(listOf("su", "/system/bin/su", "/system/bin/su"), calls.map { it[0] })
    }

    @Test
    fun noExecutableSuExplainsHowToEnableKernelSu() {
        val result = RootAccessClient(RootCommandExecutor { _, _ ->
            throw IOException("No such file or directory")
        }).request()

        assertTrue(result is RootAccess.Unavailable)
        assertTrue((result as RootAccess.Unavailable).reason.contains("KernelSU"))
    }

    @Test
    fun authorizationTimeoutStopsAndAllowsUserToRetry() {
        var calls = 0
        val result = RootAccessClient(RootCommandExecutor { _, _ ->
            calls++
            RootCommandResult(-1, "", timedOut = true)
        }).request()

        assertTrue(result is RootAccess.Unavailable)
        assertTrue((result as RootAccess.Unavailable).reason.contains("超时"))
        assertEquals(1, calls)
    }

    @Test
    fun unavailableVersionDoesNotDiscardActualRootGrant() {
        val result = RootAccessClient(RootCommandExecutor { command, _ ->
            if (command[1] == "-v") throw IOException("Version unavailable")
            RootCommandResult(0, rootOutput)
        }).request() as RootAccess.Granted

        assertEquals("su (版本未知)", result.implementation)
    }

    @Test
    fun processExecutorCapturesRealChildOutputAndExitStatus() {
        val java = File(System.getProperty("java.home"), "bin/java.exe")
            .takeIf { it.exists() } ?: File(System.getProperty("java.home"), "bin/java")
        val result = ProcessRootCommandExecutor().execute(listOf(java.absolutePath, "-version"), 10)

        assertEquals(0, result.exitCode)
        assertTrue(result.output.contains("version"))
        assertTrue(!result.timedOut)
    }
}
