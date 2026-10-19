package pro.darc.viruaisor.root

import org.junit.Assert.assertThrows
import org.junit.Test

class RootStorageAuthorizationTest {
    @Test fun onlyFullApplicationUidAndKnownCapabilityAreAccepted() {
        RootStorageAuthorization.enforce(10278, 10278, 1234, 1)
        RootStorageAuthorization.enforce(1010278, 1010278, 1234, 3)
        for (caller in listOf(0, 1010278, 10279)) {
            assertThrows(SecurityException::class.java) { RootStorageAuthorization.enforce(10278, caller, 1234, 1) }
        }
        assertThrows(SecurityException::class.java) { RootStorageAuthorization.enforce(0, 0, 1234, 1) }
        assertThrows(SecurityException::class.java) { RootStorageAuthorization.enforce(10278, 10278, 1, 1) }
        assertThrows(SecurityException::class.java) { RootStorageAuthorization.enforce(10278, 10278, 1234, 99) }
    }

    @Test fun exactVmProcessWithNoCapabilitiesIsRequired() {
        val packageName = "org.example.virtualmachine.debug"
        val status = "Uid:\t10278\t10278\t10278\t10278\nCapEff:\t0000000000000000\n"
        RootStorageAuthorization.verifyProcess(status, "$packageName:venus_vm\u0000", 10278, packageName)
        for (command in listOf("$packageName\u0000", "$packageName:venus_vm_bad\u0000", "$packageName:venus_vm\u0000extra\u0000", "another.package:venus_vm\u0000")) {
            assertThrows(SecurityException::class.java) { RootStorageAuthorization.verifyProcess(status, command, 10278, packageName) }
        }
        assertThrows(SecurityException::class.java) { RootStorageAuthorization.verifyProcess(status.replace("10278\t10278\n", "10278\t0\n"), "$packageName:venus_vm\u0000", 10278, packageName) }
        assertThrows(SecurityException::class.java) { RootStorageAuthorization.verifyProcess(status.replace("0000000000000000", "0000000000000001"), "$packageName:venus_vm\u0000", 10278, packageName) }
        assertThrows(SecurityException::class.java) { RootStorageAuthorization.verifyProcess("", "$packageName:venus_vm\u0000", 10278, packageName) }
    }

    @Test fun aSessionCannotBeControlledByAnotherProcessOrToken() {
        val token = Any()
        RootStorageAuthorization.enforceOwner(10278, 1234, token, 10278, 1234, token)
        assertThrows(SecurityException::class.java) { RootStorageAuthorization.enforceOwner(10278, 1234, token, 10278, 2345, token) }
        assertThrows(SecurityException::class.java) { RootStorageAuthorization.enforceOwner(10278, 1234, token, 10279, 1234, token) }
        assertThrows(SecurityException::class.java) { RootStorageAuthorization.enforceOwner(10278, 1234, token, 10278, 1234, Any()) }
    }
}
