package pro.darc.viruaisor.vm

import java.io.File
import java.nio.file.Files

/** Models one indivisible exchange for journal checkpoints; native tests exercise renameat2 itself. */
internal fun exchangeFixture(first: File, second: File) {
    val temporary=File(first.parentFile,"exchange-fixture")
    Files.move(first.toPath(),temporary.toPath())
    Files.move(second.toPath(),first.toPath())
    Files.move(temporary.toPath(),second.toPath())
}
