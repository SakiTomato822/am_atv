package dev.amatv.ui

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.amatv.BuildConfig
import dev.amatv.MainActivity
import dev.amatv.data.MockCatalog
import dev.amatv.integration.LyricTextSize
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PlayerLiveSeekTest {
    @Test
    fun liveStationShowsLiveStatusAndNonSeekableTrackNeverSeeks() {
        assumeTrue("The touch progress slider is part of the Flyme UI", BuildConfig.FLYME_AUTO)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val track = Track(
            id = "live-seek-test",
            title = "Live station",
            artist = "Test station",
            album = "Live",
            durationMs = 60_000L,
            palette = MockCatalog.tracks.first().palette,
            lyrics = emptyList(),
            catalogId = "live-seek-test",
            catalogKind = CatalogKind.Station,
        )
        val live = androidx.compose.runtime.mutableStateOf(true)
        val seekCalls = AtomicInteger()

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AmAtvTheme {
                        CarLayout {
                            PlayerScreen(
                                track = track,
                                positionAnchorMs = 15_000L,
                                playStartedRealtimeMs = 0L,
                                isPlaying = false,
                                queue = listOf(track),
                                canSkipPrevious = false,
                                canSkipNext = false,
                                repeatMode = PlaybackRepeatMode.Off,
                                shuffleEnabled = false,
                                autoplayEnabled = false,
                                canSetRepeat = false,
                                canSetShuffle = false,
                                canSetAutoplay = false,
                                lyricsLoading = false,
                                statusMessage = null,
                                onTogglePlayback = {},
                                onSkipPrevious = {},
                                onSkipNext = {},
                                onSeekBy = { seekCalls.incrementAndGet() },
                                onSelectTrack = {},
                                onCycleRepeat = {},
                                onToggleShuffle = {},
                                onToggleAutoplay = {},
                                canSeek = false,
                                isLiveStream = live.value,
                                queueSessionId = 1L,
                                lyricTextSize = LyricTextSize.Standard,
                            )
                        }
                    }
                }
            }

            val liveStatus = device.wait(Until.findObject(By.res("player-live-status")), 8_000L)
            assertNotNull("Live station status should be shown", liveStatus)
            assertNull("Live playback must not show a seek progress control",
                device.findObject(By.res("player-touch-progress")))

            scenario.onActivity { live.value = false }
            assertTrue("Live status should leave when the test switches to non-live",
                device.wait(Until.gone(By.res("player-live-status")), 5_000L))
            assertNotNull("Non-live playback should restore the progress control",
                device.wait(Until.findObject(By.res("player-touch-progress")), 5_000L))

            val seekBar = device.wait(Until.findObject(By.clazz("android.widget.SeekBar")), 5_000L)
            assertNotNull("Progress slider should be exposed", seekBar)
            assertFalse("canSeek=false must disable the progress slider", seekBar!!.isEnabled)

            val bounds = seekBar.visibleBounds
            assertTrue("Progress slider should have a usable hit region", bounds.width() > 1 && bounds.height() > 1)
            device.click(bounds.centerX(), bounds.centerY())
            device.swipe(
                bounds.left + bounds.width() * 3 / 4,
                bounds.centerY(),
                bounds.left + bounds.width() / 4,
                bounds.centerY(),
                12,
            )
            instrumentation.waitForIdleSync()
            assertEquals("A disabled slider must not dispatch seeks", 0, seekCalls.get())
        }
    }
}
