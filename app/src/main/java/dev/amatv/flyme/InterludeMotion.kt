package dev.amatv.flyme

import dev.amatv.model.LyricTimeline
import kotlin.math.PI
import kotlin.math.sin

/** A seek-safe countdown whose last dot finishes before the continuous exit begins. */
internal object InterludeMotion {
    fun exitStart(gap: LyricTimeline.InstrumentalGap) = gap.endMs + LyricTimeline.INSTRUMENTAL_LINE_OFFSET_MS

    fun exitStartForLineHandoff(gap: LyricTimeline.InstrumentalGap): Long =
        gap.endMs - MotionTokens.InstrumentalExitExpandMillis - MotionTokens.InstrumentalExitShrinkMillis

    fun dotFraction(
        gap: LyricTimeline.InstrumentalGap, positionMs: Long, index: Int, exitStartMs: Long = exitStart(gap),
    ): Float {
        val segment = ((exitStartMs - gap.startMs).coerceAtLeast(3L) / 3.0)
        return ((positionMs - gap.startMs - index.coerceIn(0, 2) * segment) / segment).toFloat().coerceIn(0f, 1f)
    }

    private fun pulse(elapsedMs: Long): Float {
        val phase = (elapsedMs.coerceAtLeast(0L) % MotionTokens.InstrumentalPulsePeriodMillis).toFloat() /
            MotionTokens.InstrumentalPulsePeriodMillis
        return 1f + (MotionTokens.InstrumentalPulseMaxScale - 1f) * sin(PI * phase).toFloat()
    }

    fun scale(gap: LyricTimeline.InstrumentalGap, positionMs: Long, exitStartMs: Long = exitStart(gap)): Float {
        val elapsed = positionMs - exitStartMs
        if (elapsed < 0L) return pulse(positionMs - gap.startMs)
        if (elapsed < MotionTokens.InstrumentalExitExpandMillis) {
            val entryScale = pulse(exitStartMs - gap.startMs)
            val f = MotionTokens.InstrumentalExitExpandEasing.transform(elapsed.toFloat() / MotionTokens.InstrumentalExitExpandMillis)
            return entryScale + (MotionTokens.InstrumentalPulseMaxScale - entryScale) * f
        }
        val f = ((elapsed - MotionTokens.InstrumentalExitExpandMillis).toFloat() / MotionTokens.InstrumentalExitShrinkMillis).coerceIn(0f, 1f)
        return MotionTokens.InstrumentalPulseMaxScale +
            (MotionTokens.InstrumentalExitMinScale - MotionTokens.InstrumentalPulseMaxScale) * MotionTokens.InstrumentalExitShrinkEasing.transform(f)
    }

    fun alpha(gap: LyricTimeline.InstrumentalGap, positionMs: Long, exitStartMs: Long = exitStart(gap)): Float {
        val f = ((positionMs - exitStartMs - MotionTokens.InstrumentalExitExpandMillis).toFloat() /
            MotionTokens.InstrumentalExitShrinkMillis).coerceIn(0f, 1f)
        return 1f - MotionTokens.InstrumentalExitShrinkEasing.transform(f)
    }
}
