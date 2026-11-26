package dev.amatv.integration

import androidx.compose.ui.graphics.Color
import dev.amatv.model.Track
import kotlinx.coroutines.CompletableDeferred
import kotlin.math.log10
import kotlin.math.pow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer

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

    @Test fun appliesFourModesAndPreservesSoftenedCuts() {
        val tag = SoundCheck(gainDb = 2f, peakDb = -12f)
        assertEquals(0f, tag.gainFor(LoudnessMode.Off), 0f)
        assertEquals(2f, tag.gainFor(LoudnessMode.Standard), 0f)
        assertEquals(6f, tag.gainFor(LoudnessMode.Loud), 0f)
        assertEquals(9f, tag.gainFor(LoudnessMode.Loudest), 0f)
        assertEquals(LoudnessMode.Loud, LoudnessMode.fromPreference(null))
        val highPeak = SoundCheck(gainDb = 7f, peakDb = -0.5f)
        assertEquals(0f, highPeak.gainFor(LoudnessMode.Off), 0f)
        assertEquals(-0.5f, highPeak.gainFor(LoudnessMode.Standard), 0f)
        assertEquals(0f, highPeak.gainFor(LoudnessMode.Loud), 0f)
        assertEquals(0f, highPeak.gainFor(LoudnessMode.Loudest), 0f)
        assertEquals(-11f / 3f, SoundCheck(-9f, -0.5f).gainFor(LoudnessMode.Loud), 0.001f)
        // Luna Say Maybe (preview tag -11.46 dB) versus 还有什么更好的 (-5.08 dB) on the car.
        assertEquals(-4.488f, SoundCheck(-11.463141f, -0.3007f).gainFor(LoudnessMode.Loud), 0.001f)
        assertEquals(-1.077f, SoundCheck(-5.07721f, -0.0786f).gainFor(LoudnessMode.Loud), 0.001f)
    }

    @Test fun capsPositiveGainAtThePreviewSafetyCeilingInEveryMode() {
        for (mode in LoudnessMode.entries) {
            for (requested in -12..12) {
                for (peak in -20..10) {
                    val tag = SoundCheck(requested.toFloat(), peak.toFloat())
                    val gain = tag.gainFor(mode)
                    // A cut or unity gain cannot raise a tag already above the safety ceiling.
                    assertTrue(
                        "$mode: tag=$tag, gain=$gain",
                        tag.peakDb + gain <= maxOf(LOUDNESS_BOOST_CEILING_DB, tag.peakDb),
                    )
                }
            }
        }
        val quietMaster = SoundCheck(6f, -6f)
        assertEquals(0f, quietMaster.gainFor(LoudnessMode.Off), 0f)
        for (mode in listOf(LoudnessMode.Standard, LoudnessMode.Loud, LoudnessMode.Loudest)) {
            assertEquals(3f, quietMaster.gainFor(mode), 0f)
        }
    }

    @Test fun preservesCutsEvenForTagsAboveTheOldModePeakCaps() {
        assertEquals(-1f, SoundCheck(-0.5f, 0f).gainFor(LoudnessMode.Standard), 0f)
        assertEquals(-2f, SoundCheck(9f, 7f).gainFor(LoudnessMode.Loud), 0f)
        assertEquals(-2f, SoundCheck(9f, 10f).gainFor(LoudnessMode.Loudest), 0f)
    }

    @Test fun dropsAnAlbumBoostWhenTheEffectiveTagDisappears() {
        AlbumLoudness.select(mapOf("boosted" to 1000L))
        try {
            AlbumLoudness.record("boosted", SoundCheck(6f, -6f))
            val loudness = NativeTrackLoudness(1L, "apple:boosted", 0, "boosted", LoudnessMode.Loud,
                LoudnessLookup(null, "missing"), null)
            assertEquals(3f, loudness.gainAt(playing = true, nowMs = 0L), 0f)
            AlbumLoudness.select(emptyMap())
            assertEquals(0f, loudness.gainAt(playing = true, nowMs = 100L), 0f)
        } finally {
            AlbumLoudness.select(emptyMap())
        }
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
        AlbumLoudness.select(mapOf("101" to 1000L, "102" to 1000L, "103" to 1000L))
        try {
            AlbumLoudness.record("101", SoundCheck(-1f, -4f))
            assertEquals(AlbumLoudness.Summary(SoundCheck(-1f, -4f), 1), AlbumLoudness.forTrack("102"))
            AlbumLoudness.record("102", SoundCheck(-9f, -1f))
            AlbumLoudness.record("104", SoundCheck(10f, 5f))
            val shared = AlbumLoudness.Summary(SoundCheck((-10 * log10((10.0.pow(0.1) + 10.0.pow(0.9)) / 2)).toFloat(), -1f), 2)
            assertEquals(shared, AlbumLoudness.forTrack("101"))
            assertEquals(shared, AlbumLoudness.forTrack("103"))
            assertNull(AlbumLoudness.forTrack("104"))
            val loudness = NativeTrackLoudness(1L, "apple:101", 0, "101", LoudnessMode.Standard,
                LoudnessLookup(SoundCheck(-1f, -4f), "cache"), null)
            assertEquals(-3f + (shared.tag!!.gainDb + 3f) / 3f, loudness.gainAt(playing = true, nowMs = 0L), 0.001f)
        } finally {
            AlbumLoudness.select(emptyMap())
        }
    }

    @Test fun shortQuietIntroDoesNotLiftTheAlbumGain() {
        // 丁世光《神經誌》: preview tags and durations of all 12 tracks; the 14 s intro reads +11.3 dB.
        val tags = listOf(11.31f, -5.81f, -3.98f, -4.57f, -5.01f, -1.42f, -2.77f, 3.90f, -5.08f, -2.01f, -4.81f, -2.12f)
        val seconds = listOf(14L, 247L, 275L, 416L, 248L, 251L, 192L, 110L, 219L, 308L, 301L, 251L)
        AlbumLoudness.select(tags.indices.associate { "$it" to seconds[it] * 1000 })
        try {
            tags.forEachIndexed { i, tag -> AlbumLoudness.record("$i", SoundCheck(tag, -0.4f)) }
            val album = AlbumLoudness.forTrack("0")!!.tag!!
            assertEquals(-3.85f, album.gainDb, 0.01f)
            assertEquals(0f, album.gainFor(LoudnessMode.Loud), 0f)
        } finally {
            AlbumLoudness.select(emptyMap())
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
