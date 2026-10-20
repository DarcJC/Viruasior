package pro.darc.viruaisor.vm

import android.app.ActivityManager
import android.content.Context

internal fun hostMemoryBytes(context: Context): Long = runCatching {
    val info = ActivityManager.MemoryInfo()
    context.getSystemService(ActivityManager::class.java)?.getMemoryInfo(info)
    info.totalMem
}.getOrDefault(0L)
