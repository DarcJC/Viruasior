package pro.darc.viruaisor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pro.darc.viruaisor.root.AvfPermission
import pro.darc.viruaisor.root.AvfPermissionClient
import pro.darc.viruaisor.root.RootCommandExecutor
import pro.darc.viruaisor.root.RootCommandResult

class AvfPermissionClientTest {
    @Test
    fun existingPermissionsNeverExecuteRootOrEnterRollbackJournal() {
        val result = AvfPermissionClient(RootCommandExecutor { _, _ -> error("Unexpected root operation") })
            .grantMissing(null, 0, { true }, { error("Existing grant must not be journaled") })

        assertTrue(result.all { it.wasGranted && it.isGranted })
    }

    @Test
    fun onlyMissingPermissionIsGrantedToFixedPackageAndCurrentUser() {
        val granted = mutableSetOf(AvfPermission.MANAGE_VM)
        val journal = mutableSetOf<AvfPermission>()
        val calls = mutableListOf<List<String>>()
        val client = AvfPermissionClient(RootCommandExecutor { command, timeout ->
            assertEquals(20L, timeout)
            assertTrue(AvfPermission.CUSTOM_VM in journal)
            calls += command
            granted += AvfPermission.CUSTOM_VM
            RootCommandResult(0, "")
        })
        val result = client.grantMissing("/system/bin/su", 10, { it in granted }, { journal += it })

        assertEquals(listOf(listOf("/system/bin/su", "-c", "/system/bin/pm grant --user 10 pro.darc.viruaisor android.permission.USE_CUSTOM_VIRTUAL_MACHINE")), calls)
        assertEquals(setOf(AvfPermission.CUSTOM_VM), journal)
        assertTrue(result.all { it.isGranted })
        assertTrue(result.first().wasGranted)
        assertFalse(result.last().wasGranted)
    }

    @Test
    fun successExitIsNotEnoughWithoutOrdinaryAppPermissionVerification() {
        val result = AvfPermissionClient(RootCommandExecutor { _, _ -> RootCommandResult(0, "") })
            .grantMissing("su", 0, { false }, {})

        assertTrue(result.none { it.isGranted })
        assertTrue(result.all { it.error != null })
    }

    @Test
    fun failedGrantPreservesPartialProgressAndDoesNotExpandScope() {
        val granted = mutableSetOf<AvfPermission>()
        val calls = mutableListOf<List<String>>()
        val result = AvfPermissionClient(RootCommandExecutor { command, _ ->
            calls += command
            if (command.last().endsWith(AvfPermission.MANAGE_VM.androidName)) {
                granted += AvfPermission.MANAGE_VM
                RootCommandResult(0, "")
            } else RootCommandResult(1, "not allowed")
        }).grantMissing("su", 0, { it in granted }, {})

        assertTrue(result.first().isGranted)
        assertFalse(result.last().isGranted)
        assertEquals("not allowed", result.last().error)
        assertEquals(2, calls.size)
        assertTrue(calls.none { it.last().contains("settings") || it.last().contains("setenforce") })
    }

    @Test
    fun journalPersistenceFailurePreventsAnyPrivilegedMutation() {
        var executed = false
        try {
            AvfPermissionClient(RootCommandExecutor { _, _ ->
                executed = true
                RootCommandResult(0, "")
            }).grantMissing("su", 0, { false }, { error("Journal failure") })
        } catch (_: IllegalStateException) {
            // Expected: never perform a mutation without its rollback journal.
        }
        assertFalse(executed)
    }

    @Test
    fun missingRootDoesNotAttemptOrJournalChanges() {
        val result = AvfPermissionClient(RootCommandExecutor { _, _ -> error("No root") })
            .grantMissing(null, 0, { false }, { error("No mutation to journal") })
        assertTrue(result.none { it.isGranted })
    }

    @Test
    fun timedOutGrantIsRecheckedFromOrdinaryAppForAmbiguousSuccess() {
        var granted = false
        val result = AvfPermissionClient(RootCommandExecutor { _, _ ->
            granted = true
            RootCommandResult(-1, "", timedOut = true)
        }).grantMissing("su", 0, { granted }, {})
        assertTrue(result.all { it.isGranted })
    }

    @Test
    fun rollbackRevokesOnlyJournaledOriginallyAbsentPermission() {
        val calls = mutableListOf<List<String>>()
        val client = AvfPermissionClient(RootCommandExecutor { command, _ ->
            calls += command
            RootCommandResult(0, "")
        })
        assertNull(client.revokeAdded("su", 0, setOf(AvfPermission.CUSTOM_VM)))
        assertEquals(listOf(listOf("su", "-c", "/system/bin/pm revoke --user 0 pro.darc.viruaisor android.permission.USE_CUSTOM_VIRTUAL_MACHINE")), calls)
    }

    @Test
    fun emptyRollbackNeedsNoRootProcess() {
        val client = AvfPermissionClient(RootCommandExecutor { _, _ -> error("Unexpected root") })
        assertNull(client.revokeAdded("su", 0, emptySet()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidUserIdFailsBeforeExecutingRoot() {
        AvfPermissionClient(RootCommandExecutor { _, _ -> error("Unexpected root") })
            .grantMissing("su", -1, { false }, {})
    }
}
