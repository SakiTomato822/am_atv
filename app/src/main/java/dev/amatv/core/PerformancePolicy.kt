package dev.amatv.core

private const val LOW_MEMORY_BYTES = 3L * 1024 * 1024 * 1024
private const val LOW_CPU_MAX_KHZ = 2_000_000L
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val SLOW_FRAME_INTERVALS = 1.5
private const val SLOW_FRAME_RATIO = 0.25

enum class LowPerformanceMode { Auto, On, Off }
enum class AutoLowPerformanceReason { Hardware, PlaybackJank }

internal fun migrateLowPerformanceMode(legacyEnabled: Boolean?): LowPerformanceMode =
    if (legacyEnabled == true) LowPerformanceMode.On else LowPerformanceMode.Auto

internal fun isLowPerformanceHardware(
    isLowRam: Boolean,
    totalMemoryBytes: Long,
    cpuMaxFrequencyKhz: Long?,
): Boolean = isLowRam || totalMemoryBytes <= LOW_MEMORY_BYTES ||
    (cpuMaxFrequencyKhz != null && cpuMaxFrequencyKhz <= LOW_CPU_MAX_KHZ)

internal data class PlaybackFrameStats(val frameCount: Int, val slowFrameCount: Int) {
    val slowFrameRatio: Double
        get() = if (frameCount == 0) 0.0 else slowFrameCount.toDouble() / frameCount
    val shouldEnableLowPerformance: Boolean
        get() = slowFrameRatio > SLOW_FRAME_RATIO
}

internal fun assessPlaybackFrames(frameDurationsNanos: List<Long>, refreshRateHz: Float): PlaybackFrameStats {
    val slowFrameThreshold = NANOS_PER_SECOND / refreshRateHz * SLOW_FRAME_INTERVALS
    return PlaybackFrameStats(
        frameCount = frameDurationsNanos.size,
        slowFrameCount = frameDurationsNanos.count { it > slowFrameThreshold },
    )
}
