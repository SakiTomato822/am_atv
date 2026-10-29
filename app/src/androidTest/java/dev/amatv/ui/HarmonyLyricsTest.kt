package dev.amatv.ui

import androidx.activity.compose.setContent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.amatv.MainActivity
import dev.amatv.integration.LyricTextSize
import dev.amatv.integration.PlaybackRepeatMode
import dev.amatv.model.CatalogKind
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricTiming
import dev.amatv.model.LyricWord
import dev.amatv.model.Track
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HarmonyLyricsTest {
    private val device: UiDevice
        get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test fun harmonyLineIsVisiblySmallerThanPrimaryForEveryTimingMode() {
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        for (timing in listOf(LyricTiming.Word, LyricTiming.Line, LyricTiming.None)) {
            val track = sampleTrack(timing)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        AmAtvTheme {
                            CarLayout {
                                PlayerScreen(
                                    track = track,
                                    positionAnchorMs = 1_000L,
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
                                    onSeekBy = {},
                                    onSelectTrack = {},
                                    onCycleRepeat = {},
                                    onToggleShuffle = {},
                                    onToggleAutoplay = {},
                                    queueSessionId = timing.ordinal.toLong() + 1L,
                                    lyricTextSize = LyricTextSize.Large,
                                )
                            }
                        }
                    }
                }

                assertNotNull("Lyrics panel for $timing", device.wait(
                    Until.findObject(By.res("lyrics-panel")), 8_000L,
                ))
                val primary = device.wait(Until.findObject(By.text("主歌词主歌词")), 8_000L)
                val harmonyText = if (timing == LyricTiming.Word) "和声和声" else "(和声和声)"
                val harmony = device.wait(Until.findObject(By.text(harmonyText)), 8_000L)
                assertNotNull("Primary lyric for $timing", primary)
                assertNotNull("Harmony lyric for $timing", harmony)
                device.waitForIdle()
                Thread.sleep(900L) // Let the lyric focus and scale transitions settle.

                val primaryHeight = primary!!.visibleBounds.height()
                val harmonyHeight = harmony!!.visibleBounds.height()
                val ratio = harmonyHeight.toFloat() / primaryHeight.toFloat()
                File(targetContext.filesDir, "harmony-${timing.name}.txt").writeText(
                    "primaryHeight=$primaryHeight\nharmonyHeight=$harmonyHeight\nratio=$ratio\n",
                )
                assertTrue(
                    "$timing harmony height $harmonyHeight px should be below 80% of primary $primaryHeight px (ratio=$ratio)",
                    primaryHeight > 0 && harmonyHeight > 0 && ratio < 0.8f,
                )
                assertTrue(
                    "Screenshot for $timing",
                    device.takeScreenshot(File(targetContext.filesDir, "harmony-${timing.name}.png")),
                )
            }
        }
    }

    private fun sampleTrack(timing: LyricTiming): Track {
        val line = LyricLine(
            words = listOf(LyricWord("主歌词主歌词", 0L, 6_000L)),
            backgroundWords = listOf(
                LyricWord("和声和声", 0L, 6_000L, isBackgroundVocal = true),
            ),
            isStatic = timing == LyricTiming.None,
            timing = timing,
        )
        return Track(
            id = "harmony-${timing.name}",
            title = "Harmony size check",
            artist = "Test Artist",
            album = "Test Album",
            durationMs = 6_000L,
            palette = dev.amatv.data.MockCatalog.tracks.first().palette,
            lyrics = listOf(line),
            catalogId = "harmony-test",
            catalogKind = CatalogKind.Song,
        )
    }
}
