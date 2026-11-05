package dev.amatv.integration.voice

import org.junit.Assert.assertEquals
import org.junit.Test

class HotwordLinesTest {
    // Stand-in for ICU's Traditional-Simplified on the JVM.
    private val simplify: (String) -> String = { it.replace('復', '复').replace('遠', '远').replace('黃', '黄').replace('豐', '丰') }

    @Test
    fun onlyHanSurvives() {
        // ":" made sherpa parse a score and abort the app; brackets, Latin and dots go too.
        assertEquals(listOf("复黑王永远是你的", "二日醉黄瑞丰", "林忆莲"),
            hotwordLines(listOf("復黑王：永遠是你的", "二日醉 (feat. 黃瑞豐)", "林忆莲", "HOYO-MiX", "0", "Easy On Me"), simplify))
    }

    @Test
    fun syntaxCharactersNeverReachTheModel() {
        val lines = hotwordLines(listOf("a:1", "歌名:2.5", "#标签", "左/右", "林忆莲 0"), simplify)
        assertEquals(listOf("歌名", "标签", "左右", "林忆莲"), lines)
        lines.forEach { line -> assertEquals(false, line.any { it in ":#/ ()" }) }
    }
}
