package dev.amatv.integration.voice

import dev.amatv.integration.VoiceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceIntentModelTest {
    @Test
    fun readsSteps() {
        assertEquals(listOf(VoiceCommand.Seek(-10)), parseIntentJson("""{"steps":[{"action":"seek","seconds":-10}]}"""))
        assertEquals(
            listOf(VoiceCommand.Go(VoiceCommand.Page.Library), VoiceCommand.Open("专辑")),
            parseIntentJson("""{"steps":[{"action":"go","page":"library"},{"action":"open","label":"专辑"}]}"""),
        )
        assertEquals(listOf(VoiceCommand.Scroll(VoiceCommand.Direction.Right, "热门歌曲")),
            parseIntentJson("""{"steps":[{"action":"scroll","direction":"right","section":"热门歌曲"}]}"""))
    }

    @Test
    fun toleratesCodeFencesAndDropsUnknownSteps() {
        assertEquals(listOf(VoiceCommand.Play("纯妹妹")),
            parseIntentJson("```json\n{\"steps\":[{\"action\":\"play\",\"query\":\"纯妹妹\"},{\"action\":\"dance\"}]}\n```"))
    }

    @Test
    fun readsTypedPlayAndSearch() {
        assertEquals(
            listOf(VoiceCommand.Play("林忆莲 0", VoiceCommand.Kind.Album, artist = "林忆莲", album = "0"),
                VoiceCommand.Search("天黑黑"), VoiceCommand.Cancel),
            parseIntentJson("""{"steps":[{"action":"play","kind":"album","artist":"林忆莲","album":"0"},{"action":"search","query":"天黑黑"},{"action":"cancel"}]}"""),
        )
    }

    @Test
    fun notJsonIsNull() {
        assertNull(parseIntentJson("我不知道"))
    }
}
