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
    fun personalStation() {
        assertEquals(VoiceCommand.PersonalStation, parseVoiceCommand("播放我的电台"))
        assertEquals(VoiceCommand.PersonalStation, parseVoiceCommand("我的电台"))
        assertEquals(VoiceCommand.PersonalStation, parseVoiceCommand("放一下个人电台吧"))
    }

    @Test
    fun emptyOrNoise() {
        assertEquals(VoiceCommand.Nothing, parseVoiceCommand(""))
        assertEquals(VoiceCommand.Nothing, parseVoiceCommand("。"))
        assertEquals(VoiceCommand.Nothing, parseVoiceCommand("a"))
    }

    // The user's list of things to say (2026-11-05).
    @Test
    fun requestedCommands() {
        assertEquals(VoiceCommand.Seek(10), parseVoiceCommand("向后一点"))
        assertEquals(VoiceCommand.Seek(30), parseVoiceCommand("快进30秒"))
        assertEquals(VoiceCommand.Seek(30), parseVoiceCommand("快进三十秒"))
        assertEquals(VoiceCommand.Seek(-15), parseVoiceCommand("后退十五秒"))
        // As the recognizer writes them.
        assertEquals(VoiceCommand.Seek(-10), parseVoiceCommand("退10s"))
        assertEquals(VoiceCommand.Seek(-10), parseVoiceCommand("退 10 秒。"))
        assertEquals(VoiceCommand.Seek(60), parseVoiceCommand("快进一分钟"))
        assertEquals(VoiceCommand.Seek(-30), parseVoiceCommand("回退30秒钟"))
        assertEquals(VoiceCommand.Next, parseVoiceCommand("下一首"))
        assertEquals(VoiceCommand.NowPlaying, parseVoiceCommand("打开歌词页面"))
        assertEquals(play("音乐寻宝电台"), parseVoiceCommand("音乐寻宝电台"))
        assertEquals(VoiceCommand.Shuffle, parseVoiceCommand("随机播放"))
        assertEquals(VoiceCommand.Scroll(VoiceCommand.Direction.Down, null), parseVoiceCommand("向下滑"))
        assertEquals(VoiceCommand.Scroll(VoiceCommand.Direction.Right, "热门音乐"), parseVoiceCommand("热门音乐向右滑"))
        assertEquals(VoiceCommand.Go(VoiceCommand.Page.Home), parseVoiceCommand("回主页"))
        assertEquals(VoiceCommand.CurrentAlbum, parseVoiceCommand("进整张专辑"))
        assertEquals(VoiceCommand.CurrentArtist, parseVoiceCommand("看歌手主页"))
        assertEquals(VoiceCommand.Favorite, parseVoiceCommand("收藏这首歌"))
        assertEquals(VoiceCommand.Open("电台"), parseVoiceCommand("打开电台"))
    }

    @Test
    fun twoStepSentences() {
        assertEquals(listOf(VoiceCommand.Go(VoiceCommand.Page.Library), VoiceCommand.Open("专辑")),
            parseVoiceSteps("进资料库看专辑"))
        assertEquals(listOf(VoiceCommand.Open("喜好歌曲"), VoiceCommand.Pick(3)),
            parseVoiceSteps("播放喜好歌曲里的第三首"))
        assertEquals(listOf(VoiceCommand.Go(VoiceCommand.Page.Home), VoiceCommand.Scroll(VoiceCommand.Direction.Down, null)),
            parseVoiceSteps("回主页然后向下滑"))
        assertEquals(listOf(play("周杰伦")), parseVoiceSteps("播放周杰伦的歌"))
    }
}
