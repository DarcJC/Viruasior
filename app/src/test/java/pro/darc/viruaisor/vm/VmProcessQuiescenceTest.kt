package pro.darc.viruaisor.vm

import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VmProcessQuiescenceTest {
    @get:Rule val temp = TemporaryFolder()
    private val proc by lazy { temp.newFolder("proc") }
    private val owners = mutableMapOf<String,Int>()
    private fun process(pid: String, uid: Int, vararg command: String) {
        val directory=File(proc,pid).apply { mkdirs() }
        owners[pid]=uid
        File(directory,"cmdline").writeText(command.joinToString("\u0000")+"\u0000")
    }
    private fun guard() = VmProcessQuiescence(proc,10278,{owners.getValue(it.name)},
        { it == listOf("/apex/com.android.virt/bin/crosvm","device","fs") })
    @Test fun liveOwnedVmCannotBeHiddenByANewEngineInstance() {
        process("123",10278,"crosvm_default")
        assertThrows(IllegalStateException::class.java) { guard().requireStopped() }
    }
    @Test fun unknownPackagedCrosvmCannotBeIgnored() {
        process("123",10278,"/app/libvenus_desktop_crosvm.so","run")
        assertThrows(IllegalStateException::class.java) { guard().requireStopped() }
    }
    @Test fun idleVirtmgrAndOwnedFsDoNotRequireCreatingAnAvfClient() {
        process("123",10278,"virtmgr_app")
        process("456",10278,"/apex/com.android.virt/bin/crosvm","device","fs")
        process("789",1000,"crosvm_other")
        guard().requireStopped()
    }
    @Test fun unreadableOwnedProcessFailsClosed() {
        process("123",10278,"crosvm_default")
        File(proc,"123/cmdline").delete()
        assertThrows(IllegalStateException::class.java) { guard().requireStopped() }
    }
}
