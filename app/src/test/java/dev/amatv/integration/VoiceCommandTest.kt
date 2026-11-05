package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandTest {
    private fun play(q: String) = VoiceCommand.Play(q)

    @Test
    fun transportControls() {
        assertEquals(VoiceCommand.Pause, parseVoiceCommand("暂停"))
        assertEquals(VoiceCommand.Pause, parseVoiceCommand("暂停播放。"))
        assertEquals(VoiceCommand.Pause, parseVoiceCommand("帮我停一下"))
        assertEquals(VoiceCommand.Resume, parseVoiceCommand("继续播放"))
        assertEquals(VoiceCommand.Resume, parseVoiceCommand("继续吧"))
        assertEquals(VoiceCommand.Resume, parseVoiceCommand("播放"))
        assertEquals(VoiceCommand.Next, parseVoiceCommand("下一首"))
        assertEquals(VoiceCommand.Next, parseVoiceCommand("换一首歌"))
        assertEquals(VoiceCommand.Next, parseVoiceCommand("切歌吧"))
        assertEquals(VoiceCommand.Previous, parseVoiceCommand("上一首"))
    }

    @Test
    fun searchPhrasings() {
        assertEquals(play("周杰伦"), parseVoiceCommand("播放周杰伦的歌"))
        assertEquals(play("周杰伦 晴天"), parseVoiceCommand("我想听周杰伦的晴天"))
        assertEquals(play("晴天"), parseVoiceCommand("来一首晴天"))
        assertEquals(play("张惠妹"), parseVoiceCommand("帮我放点张惠妹的歌吧"))
        assertEquals(play("taylor swift"), parseVoiceCommand("播放 Taylor Swift"))
        assertEquals(play("五月天"), parseVoiceCommand("五月天"))
    }

    @Test
    fun pickByPosition() {
        assertEquals(VoiceCommand.Pick(3), parseVoiceCommand("第三个"))
        assertEquals(VoiceCommand.Pick(2), parseVoiceCommand("播放第二首"))
        assertEquals(VoiceCommand.Pick(2), parseVoiceCommand("点第两个"))
        assertEquals(VoiceCommand.Pick(12), parseVoiceCommand("选第十二项"))
        assertEquals(VoiceCommand.Pick(20), parseVoiceCommand("第二十首歌"))
        assertEquals(VoiceCommand.Pick(5), parseVoiceCommand("第5个"))
        // A song title that merely contains 第 is still a search.
        assertEquals(play("第一次"), parseVoiceCommand("播放第一次"))
    }

    @Test
    fun emptyOrNoise() {
        assertEquals(VoiceCommand.Nothing, parseVoiceCommand(""))
        assertEquals(VoiceCommand.Nothing, parseVoiceCommand("。"))
        assertEquals(VoiceCommand.Nothing, parseVoiceCommand("a"))
    }
}
