package dev.amatv.ui

/** A geometric retention boundary may move several pixels per frame, but its tint must not snap. */
internal fun advanceLyricPresence(current: Float, desired: Float, elapsedMs: Long): Float {
    val from = current.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    val to = desired.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
    // The live karaoke/line-sync envelope already owns activation timing.
    if (to >= from) return to
    val maximumFall = elapsedMs.coerceIn(0L, 64L) / 300f
    return maxOf(to, from - maximumFall)
}
