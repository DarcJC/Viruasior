package pro.darc.viruaisor

import org.junit.Test
import pro.darc.viruaisor.display.RootDisplayAuthorization

class RootDisplayAuthorizationTest {
    @Test
    fun onlyOwnFullUidCanUseBothCapabilitiesAndDescriptor() {
        val gate = RootDisplayAuthorization(10278)
        gate.enforce(10278, RootDisplayAuthorization.ATTACH)
        gate.enforce(10278, RootDisplayAuthorization.DETACH)
        gate.enforce(10278, RootDisplayAuthorization.INTERFACE_TRANSACTION)
    }

    @Test(expected = SecurityException::class)
    fun otherApplicationIsDenied() = RootDisplayAuthorization(10278).enforce(10279, RootDisplayAuthorization.ATTACH)

    @Test(expected = SecurityException::class)
    fun sameAppIdFromOtherAndroidUserIsDenied() = RootDisplayAuthorization(10278).enforce(1010278, RootDisplayAuthorization.ATTACH)

    @Test(expected = SecurityException::class)
    fun shellIsDenied() = RootDisplayAuthorization(10278).enforce(2000, RootDisplayAuthorization.DETACH)

    @Test(expected = SecurityException::class)
    fun systemUidIsNotImplicitlyTrusted() = RootDisplayAuthorization(10278).enforce(1000, RootDisplayAuthorization.ATTACH)

    @Test(expected = SecurityException::class)
    fun rootIsNotAnExtraCallerCapability() = RootDisplayAuthorization(10278).enforce(0, RootDisplayAuthorization.ATTACH)

    @Test(expected = SecurityException::class)
    fun arbitraryOperationIsRejectedEvenForOwnUid() = RootDisplayAuthorization(10278).enforce(10278, 99)

    @Test(expected = IllegalArgumentException::class)
    fun invalidBootstrapApplicationUidFailsClosed() { RootDisplayAuthorization(0) }
}
