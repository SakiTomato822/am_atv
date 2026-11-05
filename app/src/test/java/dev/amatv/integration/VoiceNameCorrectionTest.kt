package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceNameCorrectionTest {
    // ICU is not on the JVM: a small table stands in for pinyin.
    private val table = mapOf(
        '西' to "xi", '凉' to "liang", '昔' to "xi", '涟' to "lian", '播' to "bo", '放' to "fang",
        '的' to "de", '歌' to "ge", '林' to "lin", '忆' to "yi", '莲' to "lian", '园' to "yuan",
        '张' to "zhang", '惠' to "hui", '妹' to "mei", '会' to "hui", '没' to "mei", '晴' to "qing", '天' to "tian",
    )
    private fun fix(text: String, vararg names: String) = correctNames(text, names.toList()) { table[it] }

    @Test
    fun chineseNamesBySound() {
        assertEquals("播放昔涟的歌", fix("播放西凉的歌", "昔涟"))
        assertEquals("播放张惠妹", fix("播放张会没", "张惠妹"))
    }

    @Test
    fun differentSoundsStay() {
        // 林园 (lin yuan) is not 林忆莲 (lin yi lian): no length or sound match, nothing changes.
        assertEquals("播放林园", fix("播放林园", "林忆莲"))
        assertEquals("播放晴天", fix("播放晴天", "昔涟"))
    }

    @Test
    fun latinNamesByLetters() {
        assertEquals("播放 HOYO-MiX", fix("播放 hoyomex", "HOYO-MiX"))
        assertEquals("播放 HOYO-MiX 的歌", fix("播放 hoyo mex 的歌", "HOYO-MiX"))
        assertEquals("play Easy On Me", fix("play easy on mi", "Easy On Me"))
    }

    @Test
    fun nicknamesBecomeAppleNames() {
        assertEquals("播放トゲナシトゲアリ", applyAliases("播放无刺有刺"))
        assertEquals("播放 MyGO!!!!! 的歌", applyAliases("播放 my go 的歌"))
        assertEquals("播放MyGO!!!!!", applyAliases("播放MyGO!!!!!"))
        assertEquals("播放晴天", applyAliases("播放晴天"))
    }

    @Test
    fun nasalsSwapForSearch() {
        assertEquals("xi lian", nasalVariant("xi liang"))
        assertEquals("fan da tong", nasalVariant("fang da tong"))
        assertEquals(null, nasalVariant("shou du"))
    }

    @Test
    fun exactAndFarAwayAreLeftAlone() {
        assertEquals("播放 hoyomix", fix("播放 hoyomix", "hoyomix"))
        assertEquals("播放 adele", fix("播放 adele", "HOYO-MiX"))
        // Short names would match too much.
        assertEquals("播放 void", fix("播放 void", "Voi"))
    }
}
