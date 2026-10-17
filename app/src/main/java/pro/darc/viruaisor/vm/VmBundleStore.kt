package pro.darc.viruaisor.vm

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

class VmBundleStore(private val context: Context) {
    private val vmsDirectory: File
        get() = File(context.filesDir, "vms")
    private val defaultDirectory: File
        get() = File(vmsDirectory, "default")

    fun currentBundle(): VmBundle? =
        if (File(defaultDirectory, MANIFEST_NAME).isFile) load(defaultDirectory) else null

    fun import(uri: Uri): VmBundle {
        vmsDirectory.mkdirs()
        val staging = File(vmsDirectory, ".default-importing")
        val backup = File(vmsDirectory, ".default-backup")
        staging.deleteRecursively()
        backup.deleteRecursively()
        check(staging.mkdirs()) { "Could not create the bundle staging directory." }

        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                ZipInputStream(BufferedInputStream(input)).use { zip -> extract(zip, staging) }
            } ?: error("Could not open the selected bundle.")

            val bundle = load(staging)
            if (defaultDirectory.exists() && !defaultDirectory.renameTo(backup)) {
                error("Could not replace the current VM bundle.")
            }
            if (!staging.renameTo(defaultDirectory)) {
                backup.renameTo(defaultDirectory)
                error("Could not activate the imported VM bundle.")
            }
            backup.deleteRecursively()
            return load(defaultDirectory)
        } catch (error: Throwable) {
            staging.deleteRecursively()
            if (!defaultDirectory.exists() && backup.exists()) backup.renameTo(defaultDirectory)
            throw error
        }
    }

    private fun extract(zip: ZipInputStream, destination: File) {
        val rootPath = destination.canonicalPath + File.separator
        val names = hashSetOf<String>()
        var entryCount = 0
        var entry: ZipEntry? = zip.nextEntry
        while (entry != null) {
            entryCount++
            require(entryCount <= MAX_ENTRIES) { "The bundle contains too many files." }
            require(names.add(entry.name)) { "The bundle contains a duplicate entry: ${entry.name}" }

            val output = File(destination, entry.name)
            require(output.canonicalPath.startsWith(rootPath)) {
                "The bundle contains an unsafe path: ${entry.name}"
            }
            if (entry.isDirectory) {
                require(output.mkdirs() || output.isDirectory) { "Could not create ${entry.name}." }
            } else {
                val parent = output.parentFile ?: error("Invalid bundle entry: ${entry.name}")
                require(parent.mkdirs() || parent.isDirectory) { "Could not create ${parent.name}." }
                FileOutputStream(output).buffered().use { file -> zip.copyTo(file) }
            }
            zip.closeEntry()
            entry = zip.nextEntry
        }
    }

    private fun load(directory: File): VmBundle {
        val manifestFile = File(directory, MANIFEST_NAME)
        require(manifestFile.isFile) { "The bundle must contain $MANIFEST_NAME at its root." }
        val json = JSONObject(manifestFile.readText())
        require(json.optInt("version", -1) == BUNDLE_VERSION) {
            "Unsupported bundle manifest version: ${json.opt("version")}."
        }

        val kernel = resolveFile(directory, json.getString("kernel"), "kernel")
        val initrd = json.optString("initrd").takeIf(String::isNotBlank)?.let {
            resolveFile(directory, it, "initrd")
        }
        val rootfsName = when {
            json.has("rootfs") -> json.getString("rootfs")
            json.has("disk") -> json.getString("disk")
            else -> error("$MANIFEST_NAME must define rootfs.")
        }
        val rootfs = resolveFile(directory, rootfsName, "rootfs")
        val rootfsPartition = json.optJSONObject("rootfsPartition")
        val rootfsPartitionLabel = rootfsPartition?.getString("label")
        val rootfsPartitionGuid = rootfsPartition?.getString("guid")
        if (rootfsPartition != null) {
            require(rootfsPartitionLabel?.isNotBlank() == true) { "rootfsPartition.label is required." }
            require(rootfsPartitionGuid?.isNotBlank() == true) { "rootfsPartition.guid is required." }
        }
        val extraDisks = buildList {
            val configured = json.optJSONArray("extraDisks")
            if (configured != null) {
                for (index in 0 until configured.length()) {
                    val disk = configured.getJSONObject(index)
                    add(
                        VmDisk(
                            image = resolveFile(
                                directory,
                                disk.getString("image"),
                                "extraDisks[$index].image",
                            ),
                            writable = disk.optBoolean("writable", false),
                        ),
                    )
                }
            }
        }
        val osName = json.optString("osName", "debian")
        require(osName.isNotBlank()) { "osName must not be empty." }
        val memoryMiB = json.optInt("memoryMiB", DEFAULT_MEMORY_MIB)
        require(memoryMiB in MIN_MEMORY_MIB..MAX_MEMORY_MIB) {
            "memoryMiB must be between $MIN_MEMORY_MIB and $MAX_MEMORY_MIB."
        }
        val vsockPort = json.optLong("vsockPort", DEFAULT_VSOCK_PORT)
        require(vsockPort in MIN_VSOCK_PORT..MAX_VSOCK_PORT) { "Invalid vsockPort: $vsockPort." }

        val cpuTopology = json.optString("cpuTopology", "match_host")
        require(cpuTopology == "match_host" || cpuTopology == "one_cpu") {
            "cpuTopology must be match_host or one_cpu."
        }
        val gpuProfile = when (val configured = json.optString("gpuProfile", "2d")) {
            "2d" -> VmGpuProfile.SOFTWARE_2D
            "gfxstream" -> VmGpuProfile.GFXSTREAM
            "venus" -> VmGpuProfile.VENUS
            else -> error("gpuProfile must be 2d, gfxstream or venus, not $configured.")
        }
        val displayJson = json.optJSONObject("display")
        val display = VmDisplayConfig(
            width = displayJson?.optInt("width", DEFAULT_DISPLAY_WIDTH)
                ?: DEFAULT_DISPLAY_WIDTH,
            height = displayJson?.optInt("height", DEFAULT_DISPLAY_HEIGHT)
                ?: DEFAULT_DISPLAY_HEIGHT,
            horizontalDpi = displayJson?.optInt("horizontalDpi", DEFAULT_DISPLAY_DPI)
                ?: DEFAULT_DISPLAY_DPI,
            verticalDpi = displayJson?.optInt("verticalDpi", DEFAULT_DISPLAY_DPI)
                ?: DEFAULT_DISPLAY_DPI,
            refreshRate = displayJson?.optInt("refreshRate", DEFAULT_REFRESH_RATE)
                ?: DEFAULT_REFRESH_RATE,
        )
        require(display.width in MIN_DISPLAY_DIMENSION..MAX_DISPLAY_DIMENSION) {
            "display.width is outside the supported range."
        }
        require(display.height in MIN_DISPLAY_DIMENSION..MAX_DISPLAY_DIMENSION) {
            "display.height is outside the supported range."
        }
        require(display.horizontalDpi > 0 && display.verticalDpi > 0) {
            "display DPI values must be positive."
        }
        require(display.refreshRate in 1..MAX_REFRESH_RATE) {
            "display.refreshRate is outside the supported range."
        }

        val configuredArgs = json.optJSONArray("kernelArgs")?.let { configured ->
            List(configured.length()) { index -> configured.getString(index) }
        }.orEmpty()
        val args = normalizeKernelArgs(configuredArgs)

        return VmBundle(
            directory = directory,
            kernel = kernel,
            initrd = initrd,
            rootfs = rootfs,
            rootfsPartitionLabel = rootfsPartitionLabel,
            rootfsPartitionGuid = rootfsPartitionGuid,
            extraDisks = extraDisks,
            osName = osName,
            memoryMiB = memoryMiB,
            matchHostCpuTopology = cpuTopology == "match_host",
            vsockPort = vsockPort,
            network = json.optBoolean("network", false),
            autoMemoryBalloon = json.optBoolean("autoMemoryBalloon", false),
            debuggable = json.optBoolean("debuggable", false),
            consoleInputDevice = json.optString("consoleInputDevice")
                .takeIf(String::isNotBlank),
            kernelArgs = args,
            gpuProfile = gpuProfile,
            display = display,
        )
    }

    private fun resolveFile(directory: File, relativePath: String, label: String): File {
        require(relativePath.isNotBlank()) { "$label path must not be empty." }
        require(!File(relativePath).isAbsolute && '\\' !in relativePath) {
            "$label must be a relative bundle path."
        }
        val rootPath = directory.canonicalPath + File.separator
        val file = File(directory, relativePath)
        require(file.canonicalPath.startsWith(rootPath) && file.isFile) {
            "$label file is missing or outside the bundle: $relativePath"
        }
        return file
    }

    private companion object {
        const val MANIFEST_NAME = "vm.json"
        const val BUNDLE_VERSION = 1
        const val MAX_ENTRIES = 256
        const val DEFAULT_MEMORY_MIB = 2048
        const val MIN_MEMORY_MIB = 256
        const val MAX_MEMORY_MIB = 32768
        const val DEFAULT_VSOCK_PORT = 4050L
        const val DEFAULT_DISPLAY_WIDTH = 1280
        const val DEFAULT_DISPLAY_HEIGHT = 720
        const val DEFAULT_DISPLAY_DPI = 240
        const val DEFAULT_REFRESH_RATE = 60
        const val MIN_DISPLAY_DIMENSION = 320
        const val MAX_DISPLAY_DIMENSION = 8192
        const val MAX_REFRESH_RATE = 240
        const val MIN_VSOCK_PORT = 1024L
        const val MAX_VSOCK_PORT = 4_294_967_295L
    }
}

internal fun normalizeKernelArgs(configured: List<String>): List<String> = buildList {
    val blacklistedModules = linkedSetOf<String>()
    configured.forEach { argument ->
        when {
            argument.startsWith("systemd.unit=") -> Unit
            argument.startsWith("modprobe.blacklist=") -> {
                blacklistedModules += argument.substringAfter('=').split(',')
                    .filter(String::isNotBlank)
            }
            else -> add(argument)
        }
    }
    // Keep DRM/KMS untouched during headless boot. The explicit desktop service may modprobe
    // virtio_gpu (and its drm dependency) immediately before Weston is started.
    blacklistedModules += listOf("drm", "virtio_gpu")
    add("modprobe.blacklist=${blacklistedModules.joinToString(",")}")
    add("systemd.unit=multi-user.target")
    if (none { it == "systemd.mask=display-manager.service" }) {
        add("systemd.mask=display-manager.service")
    }
    if (none { it == "systemd.mask=graphical.target" }) add("systemd.mask=graphical.target")
}
