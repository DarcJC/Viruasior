package pro.darc.viruaisor.vm

import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.os.SystemClock
import java.io.ByteArrayOutputStream

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

    internal fun storageStatus(port: Long) = GuestStorageProtocol.parse(exchange(port, "STORAGE_STATUS", 15_000))

    fun startDesktop(port: Long, profile: VmGpuProfile) =
        request(port, GuestDesktopProtocol.start(profile), if (profile == VmGpuProfile.VENUS) 95_000 else COMMAND_TIMEOUT_MS)

    fun stopDesktop(port: Long, profile: VmGpuProfile) =
        request(port, GuestDesktopProtocol.stop(profile))

    fun displayModes(port: Long) = GuestDisplayProtocol.parseConfigs(exchange(port, "DISPLAY_CONFIGS", 12_000))

    fun resize(port: Long, mode: DisplayMode) =
        GuestDisplayProtocol.validateResize(exchange(port, GuestDisplayProtocol.resize(mode), 12_000), mode)

    fun changeDroidPassword(port: Long, password: String) =
        GuestPasswordProtocol.confirm(exchange(port,GuestPasswordProtocol.command(password),15_000))

    private fun request(port: Long, command: String, timeoutMillis: Int = COMMAND_TIMEOUT_MS) {
        GuestControlProtocol.validateResponse(exchange(port, command, timeoutMillis))
    }

    private fun exchange(port: Long, command: String, timeoutMillis: Int): String? {
        val descriptor = engine.connectVsock(port)
        try {
            GuestControlProtocol.validateGreeting(readLine(descriptor, timeoutMillis))
            val bytes = "$command\n".toByteArray(Charsets.UTF_8)
            try {
                var offset = 0
                while (offset < bytes.size) {
                    val written = Os.write(descriptor.fileDescriptor, bytes, offset, bytes.size - offset)
                    check(written > 0) { "Guest control command write returned $written" }
                    offset += written
                }
            } finally { bytes.fill(0) }
            val response = readLine(descriptor, timeoutMillis)
            descriptor.close()
            return response
        } catch (error: Throwable) {
            runCatching { descriptor.close() }
            throw error
        }
    }

    private fun readLine(descriptor: ParcelFileDescriptor, timeoutMillis: Int): String? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        val output = ByteArrayOutputStream()
        val byte = ByteArray(1)
        while (output.size() < MAX_REPLY_BYTES) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtMost(Int.MAX_VALUE.toLong())
            if (remaining <= 0 || !waitUntilReadable(descriptor, remaining.toInt())) return null
            val count = Os.read(descriptor.fileDescriptor, byte, 0, 1)
            if (count <= 0) return null
            if (byte[0] == '\n'.code.toByte()) {
                return output.toString(Charsets.UTF_8.name()).trimEnd('\r')
            }
            output.write(byte[0].toInt())
        }
        error("Guest control reply exceeded $MAX_REPLY_BYTES bytes")
    }

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
        const val COMMAND_TIMEOUT_MS = 15_000
        const val MAX_REPLY_BYTES = 2048
    }
}

internal object GuestDesktopProtocol {
    private fun name(profile: VmGpuProfile) = if (profile == VmGpuProfile.VENUS) "venus" else profile.avfBackend
    fun start(profile: VmGpuProfile): String = "START_DESKTOP ${name(profile)}"

    fun stop(profile: VmGpuProfile): String = "STOP_DESKTOP ${name(profile)}"
}

internal object GuestControlProtocol {
    fun validate(greeting: String?, response: String?) {
        validateGreeting(greeting)
        validateResponse(response)
    }

    fun validateGreeting(greeting: String?) {
        check(greeting == "READY") { "Guest control greeting failed: ${greeting ?: "no response"}" }
    }

    fun validateResponse(response: String?) {
        check(response == "OK") { "Guest control command failed: ${response ?: "no response"}" }
    }
}
