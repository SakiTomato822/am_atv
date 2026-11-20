package dev.amatv.core

import android.app.ActivityManager
import android.content.Context
import java.io.File
import java.io.IOException

internal fun detectLowPerformanceHardware(context: Context): Boolean {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val memory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
    return isLowPerformanceHardware(
        isLowRam = activityManager.isLowRamDevice,
        totalMemoryBytes = memory.totalMem,
        cpuMaxFrequencyKhz = readCpuMaxFrequencyKhz(),
    )
}

private fun readCpuMaxFrequencyKhz(): Long? {
    val cpuDirectories = File("/sys/devices/system/cpu").listFiles { _, name ->
        name.matches(Regex("cpu[0-9]+"))
    } ?: return null
    return try {
        cpuDirectories.maxOfOrNull { cpu ->
            cpu.resolve("cpufreq/cpuinfo_max_freq").readText().trim().toLongOrNull() ?: return null
        }
    } catch (_: IOException) {
        null
    }
}
