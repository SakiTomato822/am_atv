package dev.amatv.integration

import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(AndroidJUnit4::class)
class PlaybackMediaSessionSeekTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun liveStationDoesNotAdvertiseOrAcceptSeekEvenIfItsTrackHasDuration() {
        val (owner, controller, seeks) = newSession()
        try {
            publish(owner, station(), durationMs = 0L, isLiveStream = true, canSeek = false)

            assertEquals(
                0L,
                requireNotNull(controller.metadata).getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
            assertFalse(hasSeekAction(controller))
            controller.transportControls.seekTo(8_000L)
            instrumentation.waitForIdleSync()
            assertTrue(seeks.isEmpty())
        } finally {
            instrumentation.runOnMainSync { owner.release() }
        }
    }

    @Test fun nonLiveItemWithoutSeekCapabilityDoesNotAdvertiseOrAcceptSeek() {
        val (owner, controller, seeks) = newSession()
        try {
            publish(owner, song(), durationMs = 30_000L, isLiveStream = false, canSeek = false)

            assertFalse(hasSeekAction(controller))
            controller.transportControls.seekTo(8_000L)
            instrumentation.waitForIdleSync()
            assertTrue(seeks.isEmpty())
        } finally {
            instrumentation.runOnMainSync { owner.release() }
        }
    }

    @Test fun seekableItemAdvertisesSeekAndClampsTransportCallbackToDuration() {
        val (owner, controller, seeks) = newSession()
        try {
            publish(owner, song(), durationMs = 30_000L, isLiveStream = false, canSeek = true)

            assertTrue(hasSeekAction(controller))
            controller.transportControls.seekTo(50_000L)
            instrumentation.waitForIdleSync()
            assertEquals(listOf(30_000L), seeks.toList())
        } finally {
            instrumentation.runOnMainSync { owner.release() }
        }
    }

    private fun newSession(): Triple<PlaybackMediaSession, MediaController, CopyOnWriteArrayList<Long>> {
        var owner: PlaybackMediaSession? = null
        instrumentation.runOnMainSync { owner = PlaybackMediaSession(instrumentation.targetContext) }
        val session = requireNotNull(owner)
        val seeks = CopyOnWriteArrayList<Long>()
        instrumentation.runOnMainSync {
            session.setCallbacks(PlaybackMediaSession.Callbacks(
                play = {},
                pause = {},
                next = {},
                previous = {},
                seekTo = { target -> seeks.add(target) },
            ))
        }
        return Triple(session, MediaController(instrumentation.targetContext, session.sessionToken), seeks)
    }

    private fun publish(
        owner: PlaybackMediaSession,
        track: Track,
        durationMs: Long,
        isLiveStream: Boolean,
        canSeek: Boolean,
    ) {
        instrumentation.runOnMainSync {
            owner.publish(PlaybackSnapshot(
                currentTrack = track,
                queue = listOf(track),
                positionMs = 1_000L,
                durationMs = durationMs,
                isPlaying = true,
                isBuffering = false,
                canSkipNext = false,
                canSkipPrevious = false,
                repeatMode = PlaybackRepeatMode.Off,
                shuffleEnabled = false,
                autoplayEnabled = false,
                canSetRepeat = false,
                canSetShuffle = false,
                canSetAutoplay = false,
                isLiveStream = isLiveStream,
                canSeek = canSeek,
            ), canSkipNext = false, canSkipPrevious = false)
        }
    }

    private fun hasSeekAction(controller: MediaController): Boolean {
        val actions = controller.playbackState?.actions ?: 0L
        return (actions and PlaybackState.ACTION_SEEK_TO) != 0L
    }

    private fun song() = Track(
        id = "song",
        title = "Song",
        artist = "Artist",
        album = "Album",
        durationMs = 30_000L,
        palette = emptyList(),
        lyrics = emptyList(),
    )

    private fun station() = song().copy(
        id = "apple:ra.station",
        title = "Apple Music Radio",
        catalogId = "ra.station",
        catalogKind = CatalogKind.Station,
        durationMs = 60_000L,
    )
}
