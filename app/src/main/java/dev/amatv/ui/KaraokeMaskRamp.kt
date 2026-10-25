package dev.amatv.ui

/** Clamped transition coordinates of the existing four-stop lyric mask. */
internal data class KaraokeMaskRamp(val start: Float, val end: Float) {
    /** After a collapsed edge, the right-hand color wins (a zero-area boundary). */
    fun fractionAt(x: Float): Float = if (end > start) {
        ((x - start) / (end - start)).coerceIn(0f, 1f)
    } else if (x < start) 0f else 1f
}

internal fun karaokeMaskRamp(progress: Float, feather: Float, isRtl: Boolean): KaraokeMaskRamp =
    if (isRtl) {
        val start = progress.coerceIn(0f, 1f)
        KaraokeMaskRamp(start, (progress + feather).coerceIn(start, 1f))
    } else {
        val end = progress.coerceIn(0f, 1f)
        KaraokeMaskRamp((progress - feather).coerceIn(0f, end), end)
    }
