package pro.darc.viruaisor.vm

import org.junit.Assert.*
import org.junit.Test

class SharedPathApiTest {
    class DeviceApi(val path: String, val hostUid: Int, val hostGid: Int,
        val guestUid: Int, val guestGid: Int, val mask: Int,
        val tag: String, val socketName: String, val socketPath: String)
    class NewApi(val path: String, val hostUid: Int, val hostGid: Int,
        val guestUid: Int, val guestGid: Int, val mask: Int,
        val tag: String, val socketName: String, val appDomain: Boolean, val socketPath: String)

    private fun create(type: Class<*>) = SharedPathApi.create(type, "/export", 10278,
        1000, 984, 18, "storage", "0.sock", "/private/0.sock")

    @Test fun currentDeviceUsesNineArguments() {
        assertFalse(SharedPathApi.supportsApplicationDomain(DeviceApi::class.java))
        val result = create(DeviceApi::class.java) as DeviceApi
        assertEquals("/export", result.path)
        assertEquals(10278, result.hostUid)
        assertEquals(10278, result.hostGid)
        assertEquals(1000, result.guestUid)
        assertEquals(984, result.guestGid)
        assertEquals("/private/0.sock", result.socketPath)
    }
    @Test fun newerApiRunsBackendInApplicationDomain() {
        assertTrue(SharedPathApi.supportsApplicationDomain(NewApi::class.java))
        val result = create(NewApi::class.java) as NewApi
        assertEquals("/export", result.path)
        assertTrue(result.appDomain)
        assertEquals("0.sock", result.socketName)
        assertEquals("/private/0.sock", result.socketPath)
    }
}
