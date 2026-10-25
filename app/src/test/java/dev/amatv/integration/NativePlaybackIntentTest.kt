package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class NativePlaybackIntentTest {
    private fun probe(
        now: Long = 1_000L,
        position: Long = 0L,
        playing: Boolean = false,
        buffering: Boolean = false,
        id: String = "song",
        canSeek: Boolean = true,
    ) = NativePlaybackProbe(id, true, position, 200_000L, playing, buffering, canSeek,
        paused = !playing, ended = false, nowMs = now)

    @Test fun restoreSeeksSilentlyAndWaitsForPositionAcknowledgementBeforePlaying() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 80_000L, true, 0L)
        val initial = intent.observe(probe())
        assertEquals(listOf(NativePlaybackCommand.Seek(80_000L)), initial.commands)
        assertFalse(initial.prepared)
        assertFalse(initial.playing)
        assertEquals(80_000L, initial.positionMs)
        assertTrue(intent.observe(probe(now = 1_500L)).commands.isEmpty())
        val acknowledged = intent.observe(probe(now = 2_000L, position = 80_000L))
        assertTrue(acknowledged.prepared)
        assertEquals(listOf(NativePlaybackCommand.Play), acknowledged.commands)
        assertFalse(acknowledged.playing)
        assertTrue(intent.observe(probe(now = 2_250L, position = 80_250L, playing = true)).playing)
    }

    @Test fun pausedRestoreNeverStartsAudio() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 50_000L, false, 0L)
        assertEquals(listOf(NativePlaybackCommand.Seek(50_000L)), intent.observe(probe()).commands)
        val restored = intent.observe(probe(position = 50_000L))
        assertTrue(restored.prepared)
        assertTrue(restored.commands.isEmpty())
        assertFalse(restored.playing)
    }

    @Test fun userPauseDuringRestoreSeekWinsOverSavedAutoplay() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 50_000L, true, 0L)
        intent.observe(probe())
        intent.requestPlaying(false)
        assertTrue(intent.observe(probe(position = 50_000L)).commands.isEmpty())
    }

    @Test fun aSameSongRestartCannotReuseOldNonzeroPlaybackPosition() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        val oldPosition = intent.observe(probe(position = 30_000L))
        assertFalse(oldPosition.prepared)
        assertEquals(listOf(NativePlaybackCommand.Seek(0L)), oldPosition.commands)
        assertEquals(listOf(NativePlaybackCommand.Play), intent.observe(probe(now = 2_000L)).commands)
    }

    @Test fun matchingMetadataIsNotEnoughWhileTheNativeItemIsStillBuffering() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        val pending = intent.observe(probe(buffering = true))
        assertFalse(pending.prepared)
        assertTrue(pending.commands.isEmpty())
        assertEquals(listOf(NativePlaybackCommand.Play), intent.observe(probe(now = 3_000L)).commands)
    }

    @Test fun readyButPausedAfterBufferGetsBoundedResumeWithoutChangingIntent() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 1_250L, position = 250L, playing = true))
        intent.observe(probe(now = 2_000L, position = 250L, buffering = true))
        val recovered = intent.observe(probe(now = 5_000L, position = 250L))
        assertEquals(listOf(NativePlaybackCommand.Play), recovered.commands)
        assertTrue(intent.observe(probe(now = 5_250L, position = 250L)).commands.isEmpty())
    }

    @Test fun pausingDuringBufferingPreventsAutomaticResumeAfterBuffer() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 2_000L, buffering = true))
        intent.requestPlaying(false)
        val ready = intent.observe(probe(now = 5_000L))
        assertTrue(ready.commands.isEmpty())
        assertFalse(ready.playing)
    }

    @Test fun unrelatedOldItemNeverStartsOrMovesTheRequestedSong() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 70_000L, true, 0L)
        val old = intent.observe(probe(id = "old", position = 10_000L, playing = true))
        assertFalse(old.prepared)
        assertFalse(old.playing)
        assertEquals(70_000L, old.positionMs)
        assertTrue(old.commands.isEmpty())
    }

    @Test fun ignoredSeekFailsPausedInsteadOfPlayingFromTheBeginning() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 70_000L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 4_000L))
        intent.observe(probe(now = 7_000L))
        val failure = intent.observe(probe(now = 18_000L))
        assertEquals(PlaybackFailureKind.PositionRestore, failure.failure)
        assertFalse(failure.playing)
        assertEquals(listOf(NativePlaybackCommand.Pause), failure.commands)
    }

    @Test fun repeatedNativeIdleIsBoundedAndIsNotClassifiedAsAnUnavailableSong() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        assertEquals(listOf(NativePlaybackCommand.Play), intent.observe(probe()).commands)
        intent.observe(probe(now = 4_000L))
        intent.observe(probe(now = 7_000L))
        assertTrue(intent.observe(probe(now = 10_000L)).commands.isEmpty())
        assertEquals(PlaybackFailureKind.Network, intent.observe(probe(now = 22_000L)).failure)
    }

    @Test fun failureClassificationDoesNotSkipTracksForNetworkOrAccountErrors() {
        assertEquals(PlaybackFailureKind.Network, classifyPlaybackFailure(3, 503, null))
        assertEquals(PlaybackFailureKind.Network, classifyPlaybackFailure(1, null, null))
        assertEquals(PlaybackFailureKind.Authorization, classifyPlaybackFailure(3, 401, null))
        assertEquals(PlaybackFailureKind.Authorization, classifyPlaybackFailure(2, null, null))
        assertEquals(PlaybackFailureKind.TrackUnavailable, classifyPlaybackFailure(3, 404, null))
        assertEquals(PlaybackFailureKind.TrackUnavailable, classifyPlaybackFailure(3, null, null))
        assertEquals(PlaybackFailureKind.Unexpected, classifyPlaybackFailure(null, null, null))
    }

    @Test fun continuousBufferTimeoutIsRetryableNetworkFailureNotTrackFailure() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 2_000L, position = 1_000L, buffering = true))
        val timedOut = intent.observe(probe(now = 63_000L, position = 1_000L, buffering = true))
        assertEquals(PlaybackFailureKind.Network, timedOut.failure)
        assertFalse(timedOut.playing)
        assertEquals(listOf(NativePlaybackCommand.Pause), timedOut.commands)
    }

    @Test fun explicitPauseDuringLongBufferDoesNotCauseARecoveryFailure() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        intent.observe(probe(now = 2_000L, buffering = true))
        intent.requestPlaying(false)
        val paused = intent.observe(probe(now = 90_000L, buffering = true))
        assertNull(paused.failure)
        assertFalse(paused.playing)
        assertTrue(paused.commands.isEmpty())
    }

    @Test fun completedEventRemainsValidWithoutReadingTheStoppedControllersResetPosition() {
        assertTrue(isCompletedPlaybackItem(7L, 7L, true, "song", "song", 42L, 42L, 200_000L, 200_000L))
        // Stop/skip before the media end is not a completed song.
        assertFalse(isCompletedPlaybackItem(7L, 7L, true, "song", "song", 42L, 42L, 35_000L, 200_000L))
    }

    @Test fun oldSameSongEndRequiresTheCurrentRequestAndNativeEntry() {
        assertFalse(isCompletedPlaybackItem(6L, 7L, true, "song", "song", 42L, 42L, 200_000L, 200_000L))
        assertFalse(isCompletedPlaybackItem(7L, 7L, true, "song", "song", 43L, 42L, 200_000L, 200_000L))
        assertFalse(isCompletedPlaybackItem(7L, 7L, false, "song", "song", null, 42L, 200_000L, 200_000L))
    }

    @Test fun authoritativeEndSurvivesNativeItemAndPositionCleanupAfterALongSong() {
        val intent = NativePlaybackIntent()
        intent.begin("song", 0L, true, 0L)
        intent.observe(probe())
        val ended = intent.observe(probe(now = 200_000L).copy(hasCurrentItem = false, ended = true))
        assertTrue(ended.prepared)
        assertFalse(ended.playing)
        assertNull(ended.failure)
        assertTrue(ended.commands.isEmpty())
    }
}
