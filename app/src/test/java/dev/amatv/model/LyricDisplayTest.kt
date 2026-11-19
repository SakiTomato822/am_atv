package dev.amatv.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricDisplayTest {
    @Test fun translationDisplayNeverReplacesBackgroundVocals() {
        val line = LyricLine(
            words = listOf(LyricWord("Lead", 1_000, 2_000)),
            translation = "主唱翻譯",
            backgroundWords = listOf(
                LyricWord("Harmony", 1_200, 2_200, isBackgroundVocal = true),
            ),
            backgroundTranslation = "和聲翻譯",
        )

        val localized = line.forDisplay(showTranslation = true, showPronunciation = false)

        assertEquals("主唱翻譯", localized.translation)
        assertEquals("Harmony", localized.backgroundText)
        assertEquals("和聲翻譯", localized.backgroundTranslation)
    }

    @Test fun hidingLocalizationNeverChangesTimingSingerOrBackgroundVocals() {
        val line = LyricLine(
            words = listOf(
                LyricWord("主唱", 1_000, 2_000, pronunciationText = "zhu chang"),
            ),
            translation = "Lead",
            backgroundWords = listOf(
                LyricWord("和聲", 1_500, 2_500, isBackgroundVocal = true, pronunciationText = "he sheng"),
            ),
            backgroundTranslation = "Harmony",
            pronunciationText = "zhu chang",
            backgroundPronunciationText = "he sheng",
            lineStartMs = 1_000,
            lineEndMs = 2_500,
            agentId = "duet",
            isOppositeAgent = true,
            isMultiAgentSong = true,
            sourceKey = "L1",
        )

        val originalOnly = line.forDisplay(showTranslation = false, showPronunciation = false)

        assertEquals(line.startMs, originalOnly.startMs)
        assertEquals(line.endMs, originalOnly.endMs)
        assertEquals(line.agentId, originalOnly.agentId)
        assertEquals(line.isOppositeAgent, originalOnly.isOppositeAgent)
        assertEquals(line.isMultiAgentSong, originalOnly.isMultiAgentSong)
        assertEquals(line.backgroundText, originalOnly.backgroundText)
        assertEquals(null, originalOnly.translation)
        assertEquals(null, originalOnly.backgroundTranslation)
        assertEquals(null, originalOnly.words.single().pronunciationText)
        assertEquals(null, originalOnly.backgroundWords.single().pronunciationText)
        assertEquals("L1", originalOnly.sourceKey)
    }
}
