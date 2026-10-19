package pro.darc.viruaisor.vm

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files


class VmSharedStorageTest {
    @Test fun privilegedObserverDerivesOnlyThisPackagesUserScopedExport() {
        val packageName = "org.example.virtualmachine.debug"
        assertEquals(java.io.File("/storage/emulated/0/Android/data/$packageName/files/storage/0"),
            VmSharedStorage.privilegedDirectory(packageName, 10278))
        assertEquals(java.io.File("/storage/emulated/10/Android/data/$packageName/files/storage/0"),
            VmSharedStorage.privilegedDirectory(packageName, 1010278))
        for (uid in listOf(-1, 0, 1000, 9999, 20000)) {
            assertThrows(IllegalArgumentException::class.java) { VmSharedStorage.privilegedDirectory(packageName, uid) }
        }
        assertThrows(IllegalArgumentException::class.java) { VmSharedStorage.privilegedDirectory("../other", 10278) }
    }
    @Test fun privateBackendInheritsOnlyTheReservedSocketAndUsesAppIds() {
        assertEquals(listOf("/app/libvenus_desktop_crosvm.so", "device", "fs", "--fd=3",
            "--tag=viruaisor-storage-0", "--shared-dir=/export", "--cfg",
            "cache=never,timeout=0,negative_timeout=0,writeback=false,ugid_map='1000 984 10278 10278 18 /'", "--disable-sandbox", "--skip-pivot-root=true",
            "--app-storage-owner-pid=123"), VmSharedStorage.privateBackendCommand("/app/libvenus_desktop_crosvm.so",10278,"/export",123))
    }
    @Test fun socketUsesTheCanonicalApplicationDirectory() {
        val root = Files.createTempDirectory("shared-socket").toFile()
        try {
            val actual = java.io.File(root, "user-0").apply { mkdirs() }
            val alias = java.io.File(root, "data")
            Files.createSymbolicLink(alias.toPath(), actual.toPath())
            assertEquals(java.io.File(actual, "shared/0.sock"), VmSharedStorage.socketPath(alias))
        } finally { root.deleteRecursively() }
    }
    @Test fun delegatedBackendAuthenticationRejectsChangedDescriptorsAndScope() {
        val executable="/app/libvenus_desktop_crosvm.so"
        val command=VmSharedStorage.privateBackendCommand(executable,10278,"/export",123,true)
        assertTrue(VmSharedStorage.isBackendCommand(command,10278,"/export","/socket",executable))
        assertFalse(VmSharedStorage.isBackendCommand(command,10278,"/other","/socket",executable))
        assertFalse(VmSharedStorage.isBackendCommand(command.dropLast(1)+"--app-storage-client-fd=6",10278,"/export","/socket",executable))
        assertFalse(VmSharedStorage.isBackendCommand(command+"--other",10278,"/export","/socket",executable))
    }
    @Test fun mountArgumentIsFixedAndNonBlocking() {
        assertEquals("systemd.mount-extra=viruaisor-storage-0:/mnt/shared:virtiofs:rw,nodev,nosuid,nofail,x-systemd.device-timeout=10s", VmSharedStorage.MOUNT_ARGUMENT)
    }
    @Test fun backendAuthenticationRequiresTheExactExportAndSocket() {
        val command = listOf("/apex/com.android.virt/bin/crosvm", "device", "fs",
            "--socket=/data/user/0/pro.darc.viruaisor/files/shared/0.sock",
            "--tag=viruaisor-storage-0", "--shared-dir=/storage/emulated/0/Android/data/pro.darc.viruaisor/files/storage/0",
            "--cfg", "ugid_map='1000 984 10278 10278 18 /'", "--disable-sandbox", "--skip-pivot-root=true")
        assertTrue(VmSharedStorage.isBackendCommand(command, 10278, command[5].substringAfter('='), command[3].substringAfter('=')))
        assertFalse(VmSharedStorage.isBackendCommand(command, 10279, command[5].substringAfter('='), command[3].substringAfter('=')))
        assertFalse(VmSharedStorage.isBackendCommand(command + "--other", 10278, command[5].substringAfter('='), command[3].substringAfter('=')))
        assertFalse(VmSharedStorage.isBackendCommand(command.toMutableList().apply { set(5,"--shared-dir=/sdcard") }, 10278, command[5].substringAfter('='), command[3].substringAfter('=')))
    }
}
