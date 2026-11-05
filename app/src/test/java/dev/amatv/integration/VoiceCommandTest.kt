package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceCommandTest {
    private fun play(q: String) = VoiceCommand.Play(q, title = q)

    private fun artist(name: String) = VoiceCommand.Play(name, VoiceCommand.Kind.Artist, artist = name, kindExplicit = true)

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
        assertEquals(artist("周杰伦"), parseVoiceCommand("播放周杰伦的歌"))
        assertEquals(VoiceCommand.Play("周杰伦 晴天", VoiceCommand.Kind.Song, title = "晴天", artist = "周杰伦"),
            parseVoiceCommand("我想听周杰伦的晴天"))
        assertEquals(play("晴天"), parseVoiceCommand("来一首晴天"))
        assertEquals(artist("张惠妹"), parseVoiceCommand("帮我放点张惠妹的歌吧"))
        assertEquals(play("taylor swift"), parseVoiceCommand("播放 Taylor Swift"))
        assertEquals(play("五月天"), parseVoiceCommand("五月天"))
        assertEquals(VoiceCommand.Play("喜好歌曲", VoiceCommand.Kind.Playlist, kindExplicit = true), parseVoiceCommand("播放我的喜好歌曲"))
        assertEquals(play("我的天空"), parseVoiceCommand("播放我的天空"))
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
        // 往前 could mean either way: ask instead of guessing.
        assertEquals(VoiceCommand.SeekDirectionUnclear, parseVoiceCommand("往前一点"))
        assertEquals(VoiceCommand.SeekDirectionUnclear, parseVoiceCommand("向前跳10秒"))
        assertEquals(VoiceCommand.Next, parseVoiceCommand("下一首"))
        assertEquals(VoiceCommand.NowPlaying, parseVoiceCommand("打开歌词页面"))
        assertEquals(VoiceCommand.Play("音乐寻宝电台", VoiceCommand.Kind.Station, title = "音乐寻宝电台", kindExplicit = true),
            parseVoiceCommand("音乐寻宝电台"))
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
        assertEquals(listOf(VoiceCommand.Cancel), parseVoiceSteps("不要切歌"))
        assertEquals(listOf(VoiceCommand.Cancel), parseVoiceSteps("别暂停"))
        assertEquals(listOf(play("天黑黑"), VoiceCommand.Pause),
            parseVoiceSteps("播放天黑黑然后暂停"))
        assertEquals(listOf(VoiceCommand.Pause, play("天黑黑")),
            parseVoiceSteps("暂停然后播放天黑黑"))
        assertEquals(listOf(VoiceCommand.Go(VoiceCommand.Page.Library), VoiceCommand.Open("专辑")),
            parseVoiceSteps("进资料库看专辑"))
        assertEquals(listOf(VoiceCommand.Open("喜好歌曲"), VoiceCommand.Pick(3)),
            parseVoiceSteps("播放喜好歌曲里的第三首"))
        assertEquals(listOf(VoiceCommand.Go(VoiceCommand.Page.Home), VoiceCommand.Scroll(VoiceCommand.Direction.Down, null)),
            parseVoiceSteps("回主页然后向下滑"))
        assertEquals(listOf(artist("周杰伦")), parseVoiceSteps("播放周杰伦的歌"))
    }

    @Test
    fun separatesPlaybackSearchAndOtherActions() {
        assertEquals(play("下一首情歌"), parseVoiceCommand("播放下一首情歌"))
        for (spoken in listOf("我不要切歌", "先别暂停", "不要播放", "取消")) {
            assertEquals(listOf(VoiceCommand.Cancel), parseVoiceSteps(spoken))
        }
        assertEquals(VoiceCommand.Search("天黑黑"), parseVoiceCommand("搜索天黑黑"))
        assertEquals(VoiceCommand.Search("天黑黑"), parseVoiceCommand("帮我找天黑黑"))
        assertEquals(VoiceCommand.Go(VoiceCommand.Page.Search), parseVoiceCommand("打开搜索"))
        assertEquals(listOf(VoiceCommand.Pause, play("天黑黑")), parseVoiceSteps("先暂停再播放天黑黑"))
        assertEquals(listOf(play("再见")), parseVoiceSteps("先播放再见"))
        assertEquals(VoiceCommand.Seek(30), parseVoiceCommand("快进半分钟"))
        assertEquals(VoiceCommand.Seek(-10), parseVoiceCommand("往后退十秒"))
        assertEquals(VoiceCommand.Pick(3), parseVoiceCommand("打开第三张专辑"))
        assertEquals(VoiceCommand.Pick(1), parseVoiceCommand("播放搜索结果第一首"))
        assertEquals(VoiceCommand.Play("喜好歌曲", VoiceCommand.Kind.Playlist, kindExplicit = true), parseVoiceCommand("播放我喜欢的歌"))
        assertEquals(VoiceCommand.Nothing, parseVoiceCommand("调大音量"))
        assertEquals(VoiceCommand.Pause, parseVoiceCommand("关闭音乐"))
    }

    @Test
    fun handlesReviewCases() {
        assertEquals(VoiceCommand.Play("孙燕姿 天黑黑", VoiceCommand.Kind.Song, title = "天黑黑", artist = "孙燕姿", kindExplicit = true),
            parseVoiceCommand("播放歌手孙燕姿的歌曲天黑黑"))
        assertEquals(VoiceCommand.CurrentAlbum, parseVoiceCommand("播放当前专辑"))
        assertEquals(VoiceCommand.Favorite, parseVoiceCommand("把这首歌加到喜好歌曲"))
        assertEquals(VoiceCommand.Nothing, parseVoiceCommand("我不喜欢这首"))
        assertEquals(VoiceCommand.Search("天黑黑"), parseVoiceCommand("帮我找歌天黑黑"))
    }

    @Test
    fun keepsRequestedTitleAndMediaType() {
        assertEquals(play("首都"), parseVoiceCommand("播放首都"))
        assertEquals(play("首都"), parseVoiceCommand("播放一首首都"))
        assertEquals(play("首都"), parseVoiceCommand("来一首首都"))
        assertEquals(play("首尔之夜"), parseVoiceCommand("播放首尔之夜"))
        assertEquals(play("天黑黑"), parseVoiceCommand("放首天黑黑"))
        assertEquals(play("i’m nobody"), parseVoiceCommand("播放 I’m Nobody"))
        assertEquals(VoiceCommand.Search("都"), parseVoiceCommand("搜索都"))
        for ((artist, title) in listOf("罗大佑" to "首都", "张惠妹" to "纯妹妹",
            "陶喆" to "黑色柳丁", "方大同" to "危险世界")) {
            assertEquals(VoiceCommand.Play("$artist $title", VoiceCommand.Kind.Song, title = title, artist = artist),
                parseVoiceCommand("播放${artist}的$title"))
            assertEquals(VoiceCommand.Play("$artist $title", VoiceCommand.Kind.Album, artist = artist, album = title, kindExplicit = true),
                parseVoiceCommand("播放${artist}的专辑$title"))
        }
        assertEquals(VoiceCommand.Play("adele easy on me", VoiceCommand.Kind.Song, title = "easy on me", artist = "adele"),
            parseVoiceCommand("播放 Adele 的 Easy On Me"))
    }

    @Test
    fun keepsMediaFieldsAndVersions() {
        val album = VoiceCommand.Play("林忆莲 0", VoiceCommand.Kind.Album, artist = "林忆莲", album = "0", kindExplicit = true)
        assertEquals(album, parseVoiceCommand("林忆莲的专辑0"))
        assertEquals(album, parseVoiceCommand("播放林忆莲的0专辑"))
        assertEquals(VoiceCommand.Play("林忆莲 0", VoiceCommand.Kind.Song, title = "0", artist = "林忆莲"),
            parseVoiceCommand("林忆莲的0"))
        assertEquals(VoiceCommand.Play("孙燕姿 天黑黑", VoiceCommand.Kind.Song, title = "天黑黑", artist = "孙燕姿"),
            parseVoiceCommand("孙燕姿来一首天黑黑"))
        assertEquals(VoiceCommand.Play("孙燕姿 天黑黑 live", VoiceCommand.Kind.Song, title = "天黑黑 live", artist = "孙燕姿"),
            parseVoiceCommand("孙燕姿唱的天黑黑 (Live)"))
        assertEquals(listOf(artist("孙燕姿"), VoiceCommand.Shuffle), parseVoiceSteps("随机播放孙燕姿的歌"))
        assertEquals(VoiceCommand.Play("音乐寻宝电台", VoiceCommand.Kind.Station, title = "音乐寻宝电台", kindExplicit = true),
            parseVoiceCommand("播放音乐寻宝电台"))
    }

    @Test
    fun songWordIsNotATitle() {
        assertEquals(artist("林忆莲"), parseVoiceCommand("播放林忆莲的歌曲"))
        assertEquals(VoiceCommand.Play("罗大佑 首都", VoiceCommand.Kind.Song, title = "首都", artist = "罗大佑", kindExplicit = true),
            parseVoiceCommand("播放罗大佑的歌曲首都"))
        assertEquals(VoiceCommand.Play("罗大佑 首都", VoiceCommand.Kind.Song, title = "首都", artist = "罗大佑"),
            parseVoiceCommand("播放罗大佑的首都"))
    }
}
