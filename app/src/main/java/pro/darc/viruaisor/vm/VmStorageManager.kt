package pro.darc.viruaisor.vm

import android.content.Context
import android.system.Os
import android.system.OsConstants
import android.os.StatFs
import java.io.File
import java.io.FileOutputStream

internal class VmStorageManager(private val context: Context) {
    private val tools = PackagedRootfsTools(
        File(context.applicationInfo.nativeLibraryDir, "librootfs_e2fsck.so"),
        File(context.applicationInfo.nativeLibraryDir, "librootfs_resize2fs.so"))

    private fun transaction(bundle: VmBundle) = RootfsTransaction(bundle.rootfs, tools, ::syncDirectory,
        { first,second -> VmStorageNative.exchange(first.path,second.path) })

    fun state(bundle: VmBundle?, message: String? = null) = VmStorageState(
        rootBytes = bundle?.rootfs?.length() ?: 0,
        recoveryPending = bundle?.let { transaction(it).pending } ?: false,
        sharedPath = runCatching { VmSharedStorage.directory(context).path }.getOrNull(),
        message = message,
    )

    fun pending(bundle: VmBundle) = transaction(bundle).pending
    fun awaitingBoot(bundle: VmBundle) = locked(bundle) { transaction(bundle).awaitingBoot }

    fun preflight(bundle: VmBundle, target: Long) {
        require(target in RootfsCapacity.GIB..(1024 * RootfsCapacity.GIB) && target % RootfsCapacity.GIB == 0L)
        Ext4Geometry.bytes(bundle.rootfs)
        check(target != bundle.rootfs.length()) { "Rootfs 已是目标容量。" }
        check(!pending(bundle)) { "请先启动验证或恢复上一次根盘操作。" }
        check(File(context.applicationInfo.nativeLibraryDir, "librootfs_e2fsck.so").canExecute() &&
            File(context.applicationInfo.nativeLibraryDir, "librootfs_resize2fs.so").canExecute()) { "APK 缺少配套 ext4 工具。" }
        val space = StatFs(bundle.directory.path)
        check(space.availableBytes > bundle.rootfs.length() + 512L * 1024 * 1024) { "宿主可用空间不足以安全创建根盘副本。" }
        check(target < bundle.rootfs.length() + space.availableBytes - 512L * 1024 * 1024) { "目标容量超过宿主可用空间。" }
    }

    fun resize(bundle: VmBundle, target: Long) = locked(bundle) { transaction(bundle).resize(target) }
    fun recover(bundle: VmBundle): Boolean = locked(bundle) { transaction(bundle).recover() }
    fun confirmBoot(bundle: VmBundle, guestBytes: Long) = locked(bundle) {
        check(guestBytes == bundle.rootfs.length()) { "Guest 根分区容量与新镜像不一致，保留恢复盘。" }
        transaction(bundle).confirmBoot()
    }

    private fun <T> locked(bundle: VmBundle, action: () -> T): T {
        val file = File(bundle.directory, ".rootfs-operation.lock")
        check(file.canonicalFile == file.absoluteFile.normalize()) { "Unsafe rootfs operation lock." }
        FileOutputStream(file, true).channel.use { channel ->
            val lock = channel.tryLock() ?: error("Another rootfs operation is active.")
            lock.use { return action() }
        }
    }

    private fun syncDirectory(directory: File) {
        val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
        try {
            check(Os.fstat(fd).st_mode and OsConstants.S_IFMT == OsConstants.S_IFDIR)
            Os.fsync(fd)
        } finally { Os.close(fd) }
    }
}
