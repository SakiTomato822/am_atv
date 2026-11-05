package dev.amatv.integration

import dev.amatv.model.Track
import dev.amatv.model.CatalogKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSearchTest {
    private fun song(title: String, artist: String, album: String = "") = Track(
        id = "apple:$title/$artist", title = title, artist = artist, album = album, durationMs = 1L,
        palette = emptyList(), lyrics = emptyList(),
    )

    private fun ranked(spoken: String, items: List<Track>, library: List<Track> = emptyList()): VoiceCatalogMatch =
        rankCatalog(parseVoiceCommand(spoken) as VoiceCommand.Play, items, library, { it }, { null })

    @Test
    fun exactSongWinsAndArtistDoesNotMatchAlbumField() {
        val cover = song("晴天 (女版)", "田各田各")
        val wrongArtist = song("晴天", "其他歌手", "周杰伦精选")
        val original = song("晴天", "周杰伦")
        val match = ranked("播放周杰伦的晴天", listOf(cover, wrongArtist, original))
        assertTrue(match.confident)
        assertEquals(listOf(original), match.candidates)
        val ambiguous = ranked("播放晴天", listOf(wrongArtist, cover, original), listOf(original))
        assertFalse(ambiguous.confident)
        assertEquals(listOf(original, wrongArtist), ambiguous.candidates)
    }

    @Test
    fun songAndAlbumWithSameTitleNeedExplicitKind() {
        for ((artist, title) in listOf("罗大佑" to "首都", "张惠妹" to "纯妹妹",
            "陶喆" to "黑色柳丁", "方大同" to "危险世界")) {
            val song = song(title, artist)
            val album = song.copy(id = "album:$title", catalogKind = CatalogKind.Album)
            val items = listOf(song, album)
            for (phrase in listOf("播放$title", "播放${artist}的$title")) {
                val match = ranked(phrase, items)
                assertFalse(match.confident)
                assertEquals(items, match.candidates)
            }
            assertEquals(VoiceCatalogMatch(listOf(song), true), ranked("播放${artist}的歌曲$title", items))
            assertEquals(VoiceCatalogMatch(listOf(album), true), ranked("播放${title}这张专辑", items))
        }
    }

    @Test
    fun shortSubstringCannotMatchAndTitleFallbackKeepsParticle() {
        val capital = song("首都", "罗大佑")
        assertTrue(ranked("播放都", listOf(capital, song("成都", "赵雷"))).candidates.isEmpty())
        val literal = song("都", "艺人")
        assertEquals(VoiceCatalogMatch(listOf(literal), true), ranked("播放都", listOf(capital, literal)))
        val fullTitle = song("最好的我", "房祖名")
        assertEquals(VoiceCatalogMatch(listOf(fullTitle), true), ranked("播放最好的我", listOf(fullTitle)))
    }

    @Test
    fun versionsAndEnglishNamesAreKeptSeparate() {
        val original = song("Easy On Me", "Adele")
        val live = song("Easy On Me (Live)", "Adele")
        val originalMatch = ranked("播放 Adele Easy On Me", listOf(live, original))
        assertTrue(originalMatch.confident)
        assertEquals(listOf(original), originalMatch.candidates)
        assertEquals(listOf(original, live), originalMatch.alternatives)
        assertEquals(VoiceCatalogMatch(listOf(live), true),
            ranked("播放 Adele 的 Easy On Me Live", listOf(original, live)))
        val english = song("INTERNET OVERDOSE", "艺人")
        val match = ranked("播放 Internet OverDose", listOf(song("INTERNET OVERDOSE (Cover)", "翻唱歌手"), english))
        assertTrue(match.confident)
        assertEquals(listOf(english), match.candidates)
    }
}
