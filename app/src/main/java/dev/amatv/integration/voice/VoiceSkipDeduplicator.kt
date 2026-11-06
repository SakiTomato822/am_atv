package dev.amatv.integration.voice

/** Match a car media skip to at most one ASR command from the same listening turn. */
internal class VoiceSkipDeduplicator {
    private var mediaSkip: Pair<Int, Long>? = null

    fun record(direction: Int, elapsedMs: Long) {
        mediaSkip = direction to elapsedMs
    }

    fun consume(direction: Int, listenStartedMs: Long?): Boolean {
        val (recordedDirection, recordedAt) = mediaSkip ?: return false
        if (listenStartedMs == null || recordedAt < listenStartedMs || direction != recordedDirection) return false
        mediaSkip = null
        return true
    }
}
