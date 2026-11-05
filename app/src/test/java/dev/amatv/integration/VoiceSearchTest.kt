package dev.amatv.integration

import dev.amatv.model.Track
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSearchTest {
    private fun song(title: String, artist: String, album: String = "") = Track(
        id = "apple:$title", title = title, artist = artist, album = album, durationMs = 1L,
        palette = emptyList(), lyrics = emptyList(),
    )

    private val ahMei = listOf(song("我可以抱你吗", "张惠妹"), song("听海", "张惠妹", "BAD BOY"))

    @Test
    fun rightNameMatches() {
        assertTrue(songsMatch("张惠妹", ahMei))
        assertTrue(songsMatch("张惠妹 听海", ahMei))
    }

    @Test
    fun soundAlikeCharactersDoNotMatch() {
        // What the recognizer heard: same sound, other characters. The pinyin search takes over.
        assertFalse(songsMatch("张会没", ahMei))
        assertFalse(songsMatch("更加会", listOf(song("相见恨晚", "彭佳慧"))))
    }

    @Test
    fun nothingFoundIsNoMatch() {
        assertFalse(songsMatch("周杰伦", emptyList()))
    }
}
