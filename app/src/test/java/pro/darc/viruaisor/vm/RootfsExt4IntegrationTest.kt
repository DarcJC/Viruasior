package pro.darc.viruaisor.vm

import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Opt in with ROOTFS_INTEGRATION=1 on Windows/WSL. Uses only temporary host images. */
class RootfsExt4IntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun path(value: String): String = if (Regex("^[A-Za-z]:[\\\\/].*").matches(value))
        "/mnt/${value[0].lowercaseChar()}${value.substring(2).replace('\\','/')}" else value

    private fun command(arguments: List<String>): RootfsToolResult {
        val process = ProcessBuilder(listOf("wsl.exe", "--exec") + arguments.map(::path)).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor(120,TimeUnit.SECONDS)) { "Host ext4 fixture tool timeout" }
        return RootfsToolResult(process.exitValue(),output)
    }

    @Test fun realExt4GrowShrinkAndRollbackPreserveFiles() {
        assumeTrue(System.getenv("ROOTFS_INTEGRATION") == "1")
        val image = temporary.newFile("root_part")
        val source = temporary.newFolder("content")
        File(source,"marker.txt").writeText("rootfs transactional storage fixture\n")
        val mib = 1024L * 1024
        RandomAccessFile(image,"rw").use { it.setLength(512*mib) }
        val created = command(listOf("/usr/sbin/mkfs.ext4","-q","-F","-O","^metadata_csum_seed,^orphan_file","-d",source.path,image.path))
        assertEquals(created.output,0,created.code)
        val tools = PackagedRootfsTools(File("e2fsck"),File("resize2fs")) { args ->
            command(listOf("/usr/sbin/${args.first()}") + args.drop(1))
        }
        val transaction = RootfsTransaction(image,tools,{},::exchangeFixture)
        transaction.resize(1024*mib)
        assertEquals(1024*mib,Ext4Geometry.bytes(image))
        assertMarker(image)
        transaction.recover()
        assertEquals(512*mib,Ext4Geometry.bytes(image))
        assertMarker(image)
        transaction.resize(384*mib)
        assertEquals(384*mib,Ext4Geometry.bytes(image))
        assertMarker(image)
        transaction.confirmBoot()
        assertFalse(transaction.pending)
        assertThrows(IllegalArgumentException::class.java) { transaction.resize(64*mib) }
        transaction.recover()
        assertEquals(384*mib,Ext4Geometry.bytes(image))
        assertMarker(image)
    }

    private fun assertMarker(image: File) {
        val result = command(listOf("/usr/sbin/debugfs","-R","cat /marker.txt",image.path))
        assertEquals(result.output,0,result.code)
        assertTrue(result.output.contains("rootfs transactional storage fixture\n"))
    }
}
