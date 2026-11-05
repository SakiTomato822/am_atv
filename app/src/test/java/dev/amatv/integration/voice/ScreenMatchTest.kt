package dev.amatv.integration.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenMatchTest {
    private val normalize: (String) -> String = { it.lowercase().replace(Regex("[\\s，。、·]"), "") }
    // A tiny stand-in for the platform pinyin conversion.
    private val table = mapOf('张' to "zhang", '惠' to "hui", '会' to "hui", '妹' to "mei", '没' to "mei",
        '专' to "zhuan", '辑' to "ji", '热' to "re", '门' to "men", '歌' to "ge", '曲' to "qu")
    private val pinyin: (String) -> String? = { s -> if (s.all { it in table }) s.map { table.getValue(it) }.joinToString(" ") else null }

    private fun t(label: String, top: Float = 0f, clickable: Boolean = true, h: Boolean = false, v: Boolean = false, area: Float = 100f) =
        ScreenTarget(label, 0f, top, area / 10f, top + 10f, clickable, h, v)

    @Test
    fun exactAndContainedLabels() {
        val targets = listOf(t("专辑"), t("播放列表"), t("随机播放"))
        assertEquals("专辑", bestTarget("专辑", targets, normalize, pinyin)?.label)
        assertEquals("随机播放", bestTarget("随机", targets, normalize, pinyin)?.label)
    }

    @Test
    fun soundAlikeNameStillMatches() {
        val targets = listOf(t("张惠妹"), t("专辑"))
        assertEquals("张惠妹", bestTarget("张会没", targets, normalize, pinyin)?.label)
    }

    @Test
    fun nothingCloseIsNull() {
        assertNull(bestTarget("资料库", listOf(t("专辑")), normalize, pinyin))
        assertNull(bestTarget("专辑", listOf(t("专辑", clickable = false)), normalize, pinyin))
    }

    @Test
    fun scrollsTheRowUnderItsHeading() {
        val targets = listOf(
            t("热门歌曲", top = 100f, clickable = false),
            t("", top = 120f, h = true, area = 500f),
            t("热门专辑", top = 300f, clickable = false),
            t("", top = 320f, h = true, area = 900f),
            t("", top = 0f, v = true, area = 5000f),
        )
        assertEquals(120f, scrollTarget("热门歌曲", true, targets, normalize, pinyin)?.top)
        assertEquals(320f, scrollTarget("热门专辑", true, targets, normalize, pinyin)?.top)
        assertEquals(5000f, scrollTarget(null, false, targets, normalize, pinyin)?.area)
    }
}
