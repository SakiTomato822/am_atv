package dev.amatv.integration

import androidx.compose.ui.graphics.Color
import dev.amatv.model.Track
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs

class LoudnessTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun parsesMeasuredGainsAndUsesTheLargerStereoPeak() {
        val kouShiXinFei = SoundCheckParser.parseTag(rawTag("236 229"))!!
        val ai = SoundCheckParser.parseTag(rawTag("1142 14E0"))!!
        assertEquals(2.47f, kouShiXinFei.gainDb, 0.01f)
        assertEquals(-7.28f, ai.gainDb, 0.01f)
        assertEquals(-6.0206f, ai.peakDb, 0.001f)
    }

    @Test fun readsOnlyTheNamedFreeformTagFromAnIncompleteMp4Prefix() {
        val tag = rawTag("236 229")
        val other = freeform("iTunSMPB", tag)
        val soundCheck = freeform("iTunNORM", tag)
        val prefix = ByteBuffer.allocate(8).putInt(100_000).put("moov".toByteArray()).array() + other + soundCheck
        assertEquals(SoundCheckParser.parseTag(tag), SoundCheckParser.parsePreview(prefix))
        assertNull(SoundCheckParser.parsePreview(prefix.copyOf(prefix.size - 1)))
        assertNull(SoundCheckParser.parsePreview(other))
        assertNull(SoundCheckParser.parseTag(rawTag("0 0")))
        assertNull(SoundCheckParser.parseTag("236 229"))
    }

    @Test fun appliesFourModesAndAllowsPeakHeadroomOnlyInLoudAndLoudest() {
        val tag = SoundCheck(gainDb = 2f, peakDb = -12f)
        assertEquals(0f, tag.gainFor(LoudnessMode.Off), 0f)
        assertEquals(2f, tag.gainFor(LoudnessMode.Standard), 0f)
        assertEquals(6f, tag.gainFor(LoudnessMode.Loud), 0f)
        assertEquals(9f, tag.gainFor(LoudnessMode.Loudest), 0f)
        assertEquals(LoudnessMode.Loud, LoudnessMode.fromPreference(null))
        val highPeak = SoundCheck(gainDb = 7f, peakDb = -0.5f)
        assertEquals(0f, highPeak.gainFor(LoudnessMode.Off), 0f)
        assertEquals(-0.5f, highPeak.gainFor(LoudnessMode.Standard), 0f)
        assertEquals(5.5f, highPeak.gainFor(LoudnessMode.Loud), 0f)
        assertEquals(8.5f, highPeak.gainFor(LoudnessMode.Loudest), 0f)
        assertEquals(-11f / 3f, SoundCheck(-9f, -0.5f).gainFor(LoudnessMode.Loud), 0.001f)
        // Luna Say Maybe (preview tag -11.46 dB) versus 还有什么更好的 (-5.08 dB) on the car.
        assertEquals(-4.488f, SoundCheck(-11.463141f, -0.3007f).gainFor(LoudnessMode.Loud), 0.001f)
        assertEquals(-1.077f, SoundCheck(-5.07721f, -0.0786f).gainFor(LoudnessMode.Loud), 0.001f)
    }

    @Test fun balancesTheFourMeasuredTracksWithinOneAndAHalfDbOfTheLoudReference() {
        val tracks = mapOf(
            "周華健《皇后大道東上彩虹妹妹愛的故事》" to SoundCheck(0.6f, -0.4f),
            "張雨生《口是心非》" to SoundCheck(2.5f, -3.6f),
            "孫盛希《人樣》" to SoundCheck(-8.8f, -0.1f),
            "孫盛希《曖》" to SoundCheck(-7.3f, -1.7f),
        )
        val loudReferenceDb = 4f
        for ((name, tag) in tracks) {
            val relativeLoudnessDb = tag.gainFor(LoudnessMode.Loud) - tag.gainDb
            assertTrue(
                "$name: expected +$loudReferenceDb dB relative to the Sound Check reference, got $relativeLoudnessDb dB",
                abs(relativeLoudnessDb - loudReferenceDb) <= 1.5f,
            )
        }
    }

    @Test fun reportsOnlyTheHeadroomUsedByTheTargetGain() {
        val tag = SoundCheck(gainDb = 0.6f, peakDb = -0.4f)
        assertEquals(0f, tag.targetHeadroomUsedDb(LoudnessMode.Off), 0f)
        assertEquals(0f, tag.targetHeadroomUsedDb(LoudnessMode.Standard), 0f)
        assertEquals(5.2f, tag.targetHeadroomUsedDb(LoudnessMode.Loud), 0.001f)
        assertEquals(8.2f, tag.targetHeadroomUsedDb(LoudnessMode.Loudest), 0.001f)
        assertEquals(0f, SoundCheck(-8.8f, -0.1f).targetHeadroomUsedDb(LoudnessMode.Loud), 0f)
    }

    @Test fun persistsCacheAndEvictsTheOldestStoredEntry() {
        val file = File(folder.root, "sound-check.json")
        val cache = LoudnessCache(file, maxEntries = 2)
        val tag = SoundCheck(-5f, -2f)
        cache.put("1", tag)
        cache.put("2", tag)
        cache.put("1", tag.copy(gainDb = 2f))
        cache.put("3", tag)
        assertNull(cache["2"])
        val reopened = LoudnessCache(file, maxEntries = 2).apply { load() }
        assertEquals(tag.copy(gainDb = 2f), reopened["1"])
        assertEquals(tag, reopened["3"])
        assertNull(reopened["2"])
        val reduced = LoudnessCache(file, maxEntries = 1).apply { load() }
        assertNull(reduced["1"])
        assertEquals(tag, reduced["3"])
    }

    @Test fun keepsLoudnessAndFadeAdditive() {
        assertEquals(-3f, combinedPlaybackGain(4f, -7f), 0f)
        assertEquals(-11f, combinedPlaybackGain(-4f, -7f), 0f)
        assertEquals(3f, combinedPlaybackGain(3f, 0f), 0f)
    }

    @Test fun aLateResultRampsOnceAndThenStaysFixed() {
        val pending = CompletableDeferred<LoudnessLookup>()
        val state = NativeTrackLoudness(
            1L, "apple:1", 0, "1", LoudnessMode.Loud,
            LoudnessLookup(null, "missing", "lookup-pending"), pending,
        )
        assertEquals(0f, state.gainAt(playing = true, nowMs = 20L), 0f)
        pending.complete(LoudnessLookup(SoundCheck(-10f, -1f), "network"))
        assertEquals(0f, state.gainAt(playing = true, nowMs = 100L), 0f)
        assertEquals(-2f, state.gainAt(playing = true, nowMs = 600L), 0f)
        assertEquals(-4f, state.gainAt(playing = true, nowMs = 1_100L), 0f)
        assertEquals(-4f, state.gainAt(playing = true, nowMs = 5_000L), 0f)
    }

    @Test fun preservesThePreviewInSessionJsonAndReadsOlderSessions() {
        val track = Track("apple:1", "Song", "Artist", "Album", 1000L, listOf(Color.Black), emptyList(),
            previewUrl = "https://example.com/preview.m4a")
        val encoded = track.toSessionJson()
        assertEquals(track.previewUrl, encoded.toSessionTrack().previewUrl)
        assertEquals(track.previewUrl, track.toContentSnapshot().toContentTrack().previewUrl)
        encoded.remove("previewUrl")
        assertNull(encoded.toSessionTrack().previewUrl)
    }

    @Test fun albumUsesTheAverageAndHighestPeakForEachMember() {
        AlbumLoudness.select(setOf("101", "102", "103"))
        try {
            AlbumLoudness.record("101", SoundCheck(-1f, -4f))
            assertEquals(AlbumLoudness.Summary(SoundCheck(-1f, -4f), 1), AlbumLoudness.forTrack("102"))
            AlbumLoudness.record("102", SoundCheck(-9f, -1f))
            AlbumLoudness.record("104", SoundCheck(10f, 5f))
            val shared = AlbumLoudness.Summary(SoundCheck(-5f, -1f), 2)
            assertEquals(shared, AlbumLoudness.forTrack("101"))
            assertEquals(shared, AlbumLoudness.forTrack("103"))
            assertNull(AlbumLoudness.forTrack("104"))
            val loudness = NativeTrackLoudness(1L, "apple:101", 0, "101", LoudnessMode.Standard,
                LoudnessLookup(SoundCheck(-1f, -4f), "cache"), null)
            assertEquals(-3f - 2f / 3f, loudness.gainAt(playing = true, nowMs = 0L), 0.001f)
        } finally {
            AlbumLoudness.select(emptySet())
        }
    }

    private fun rawTag(power: String) = " $power 00000000 00000000 00000000 00000000 00003000 00004000 0 0"

    private fun freeform(name: String, tag: String): ByteArray = box(
        "----",
        box("mean", ByteArray(4) + "com.apple.iTunes".toByteArray()) +
            box("name", ByteArray(4) + name.toByteArray()) +
            box("data", byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0) + tag.toByteArray()),
    )

    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray()).put(payload).array()
}
