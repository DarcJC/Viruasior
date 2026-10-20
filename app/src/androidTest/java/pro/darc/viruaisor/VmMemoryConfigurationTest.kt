package pro.darc.viruaisor

import android.content.Context
import android.content.ContextWrapper
import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import pro.darc.viruaisor.vm.VmBundleStore

class VmMemoryConfigurationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val hostBytes = 8192L * 1024 * 1024
    private val context: Context by lazy {
        object : ContextWrapper(InstrumentationRegistry.getInstrumentation().targetContext) {
            override fun getFilesDir(): File = temporary.root
        }
    }
    private val directory by lazy { File(context.filesDir, "vms/default").apply { check(mkdirs()) } }
    private val manifest by lazy {
        File(directory, "kernel").writeText("kernel fixture")
        File(directory, "rootfs.img").writeText("rootfs fixture")
        File(directory, "vm.json").apply {
            writeText("""{"version":1,"kernel":"kernel","rootfs":"rootfs.img","memoryMiB":4096,
                "network":false,"autoMemoryBalloon":true,"extension":{"keep":"untouched"}}""")
        }
    }

    @Test fun updateSurvivesNewStoreAndPreservesOtherManifestFields() {
        val before = JSONObject(manifest.readText())
        val result = VmBundleStore(context).updateMemory(2048, hostBytes)
        assertEquals(2048, result.memoryMiB)
        assertEquals(2048, VmBundleStore(context).currentBundle()!!.memoryMiB)
        val after = JSONObject(manifest.readText())
        before.put("memoryMiB", 2048)
        assertEquals(before.toString(), after.toString())
        assertEquals("rootfs fixture", File(directory, "rootfs.img").readText())
    }

    @Test fun invalidMemoryDoesNotWriteManifest() {
        val before = manifest.readBytes()
        for (value in listOf(0, 255, 8193, 32769)) {
            assertThrows(IllegalArgumentException::class.java) {
                VmBundleStore(context).updateMemory(value, hostBytes)
            }
            assertArrayEquals(before, manifest.readBytes())
        }
        assertThrows(IllegalArgumentException::class.java) {
            VmBundleStore(context).updateMemory(2048, 0)
        }
        assertArrayEquals(before, manifest.readBytes())
    }

    @Test fun interruptedAtomicWriteLoadsPreviousMemory() {
        val atomic = AtomicFile(manifest)
        val output = atomic.startWrite()
        output.write("{broken partial write".toByteArray())
        output.fd.sync()
        output.close() // Simulate process death before finishWrite/failWrite.
        assertEquals(4096, VmBundleStore(context).currentBundle()!!.memoryMiB)
    }

    @Test fun legacyBackupWithoutBaseManifestRecoversOnLoad() {
        check(manifest.renameTo(File(directory, "vm.json.bak")))
        assertEquals(4096, VmBundleStore(context).currentBundle()!!.memoryMiB)
        assertTrue(manifest.isFile)
    }

    @Test fun failedWritePreservesPreviousMemory() {
        val before = manifest.readBytes()
        check(File(directory, "vm.json.new").mkdir())
        File(directory, "vm.json.new/blocker").writeText("prevent replacement")
        assertThrows(java.io.IOException::class.java) {
            VmBundleStore(context).updateMemory(2048, hostBytes)
        }
        assertArrayEquals(before, manifest.readBytes())
        assertEquals(4096, VmBundleStore(context).currentBundle()!!.memoryMiB)
    }
}
