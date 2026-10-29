package dev.amatv.integration

import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import java.net.URLDecoder
import org.junit.Assert.*
import org.junit.Test

class EvccLyricsMetadataTest {
    @Test fun ordinaryLyricsRemainReadableLrc() {
        val document = document("主歌词 + ordinary 100%")
        assertEquals(document.timedLrc, evccLyricsMetadataValue(document))
    }

    @Test fun evccConditionalDecodePreservesLiteralPercentEscapesAndPlusSigns() {
        val document = document("原文 %20 + %E4%B8%AD")
        val transmitted = evccLyricsMetadataValue(document)
        assertNotEquals(document.timedLrc, transmitted)
        assertEquals(document.timedLrc, URLDecoder.decode(transmitted, "UTF-8"))
    }

    @Test fun encodedExpansionIsRejectedInsteadOfTruncatingLyrics() {
        val document = document("%20+中".repeat(16_000))
        assertNotNull(document.timedLrc)
        assertEquals("", evccLyricsMetadataValue(document))
        assertEquals("", evccLyricsMetadataValue(null))
    }

    private fun document(text: String) = PlaybackLyricExport.create("song",
        listOf(LyricLine(listOf(LyricWord(text, 1000L, 3000L)))), includeBackgroundVocals = false)
}
