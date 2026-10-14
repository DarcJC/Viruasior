package pro.darc.viruaisor.vm

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.FileInputStream
import java.io.FileOutputStream

class VsockControlClient(private val engine: AvfVmEngine) {
    fun awaitReady(port: Long, timeoutMillis: Int = 2_000): Boolean {
        val descriptor = engine.connectVsock(port)
        if (!waitUntilReadable(descriptor, timeoutMillis)) {
            descriptor.close()
            return false
        }
        return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            .bufferedReader(Charsets.UTF_8)
            .use { reader -> reader.readLine()?.trim() == READY }
    }

    fun shutdown(port: Long) = send(port, SHUTDOWN)

    fun startDesktop(port: Long, profile: VmGpuProfile) = send(port, GuestDesktopProtocol.start(profile))

    fun stopDesktop(port: Long, profile: VmGpuProfile) = send(port, GuestDesktopProtocol.stop(profile))

    private fun send(port: Long, command: String) {
        val descriptor = engine.connectVsock(port)
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).bufferedWriter(Charsets.UTF_8).use {
            it.write(command)
            it.newLine()
            it.flush()
        }
    }

    private fun waitUntilReadable(descriptor: ParcelFileDescriptor, timeoutMillis: Int): Boolean {
        val poll = StructPollfd().apply {
            fd = descriptor.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        return try {
            Os.poll(arrayOf(poll), timeoutMillis) > 0 &&
                poll.revents.toInt() and OsConstants.POLLIN != 0
        } catch (_: ErrnoException) {
            false
        }
    }

    private companion object {
        const val READY = "READY"
        const val SHUTDOWN = "SHUTDOWN"
    }
}

internal object GuestDesktopProtocol {
    fun start(profile: VmGpuProfile): String = "START_DESKTOP ${profile.avfBackend}"

    fun stop(profile: VmGpuProfile): String = "STOP_DESKTOP ${profile.avfBackend}"
}
