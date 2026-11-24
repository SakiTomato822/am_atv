package dev.amatv.integration

import dev.amatv.integration.FlymeVoiceCommand.Control
import dev.amatv.integration.FlymeVoiceCommand.Info
import dev.amatv.integration.FlymeVoiceCommand.ListSource
import dev.amatv.integration.FlymeVoiceCommand.Mode
import dev.amatv.integration.FlymeVoiceCommand.Page
import dev.amatv.integration.FlymeVoiceCommand.Search
import dev.amatv.integration.FlymeVoiceCommand.Search.Kind
import dev.amatv.integration.FlymeVoiceCommand.Seek
import dev.amatv.integration.FlymeVoiceCommand.Style
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FlymeVoiceProtocolTest {
    @Test fun messageWritesParcelFieldsInVendorOrder() {
        val fields = mutableListOf<Any>()
        FlymeVoiceProtocol.writeMessage("registerEx", "点歌", { fields.add(it) }, { fields.add(it) })

        assertEquals(5, fields.size)
        assertEquals(listOf("mediacenter", "MediaCenterAPI", "registerEx"), fields.take(3))
        assertArrayEquals("点歌".toByteArray(Charsets.UTF_8), fields[3] as ByteArray)
        assertArrayEquals(byteArrayOf(), fields[4] as ByteArray)
    }

    @Test fun declarationUsesChannelInfoAndSemanticIds() {
        val declaration = FlymeVoiceProtocol.declaration("dev.test.voice", "1.2.3")
        val channel = declaration.getJSONObject("vrChannelInfo")
        assertEquals(setOf("vrChannelInfo", "semanticsTypes"), declaration.keys().asSequence().toSet())
        assertEquals(
            setOf("mediaPackageName", "mediaVersion", "mediaDescription", "channelDataType"),
            channel.keys().asSequence().toSet(),
        )
        assertEquals("dev.test.voice", channel.getString("mediaPackageName"))
        assertEquals("1.2.3", channel.getString("mediaVersion"))
        assertEquals("AM Folio", channel.getString("mediaDescription"))
        assertEquals(0, channel.getInt("channelDataType"))
        val types = declaration.getJSONArray("semanticsTypes")
        assertEquals(
            listOf(33554433, 33554434, 33554440, 33554436, 67108866, 67108868, 67108872, 67108880, 67108896, 33554448,
                33555456, 33556480, 33558528, 33562624, 33619968, 33685504, 33816576, 34603008, 35651584, 37748736,
                67108928, 67108992, 67109120, 67125248, 67141632, 67174400, 67239936, 67633152,
                69206016, 268435457, 268435458, 268435488),
            List(types.length(), types::getInt),
        )
    }

    @Test fun additionalSemanticIdsMapToCommands() {
        mapOf(
            33556480 to Control.Pause,
            33558528 to Control.Play,
            33562624 to Control.Play,
            69206016 to Control.Play,
            33619968 to Control.Replay,
            34603008 to Page.Player,
            35651584 to Page.Lyrics,
            67108928 to Style("Jazz"),
            67108992 to ListSource.Chart,
            67109120 to ListSource.Favorites,
            67239936 to ListSource.Recommendations,
            67633152 to ListSource.History,
            67125248 to Search(Kind.Song, "First", "ABC", searchOnly = true),
            67141632 to Search(Kind.Artist, "First", "ABC", searchOnly = true),
            67174400 to Search(Kind.ArtistSong, "First", "ABC", searchOnly = true),
            268435457 to Info.Title,
            268435458 to Info.Artist,
            268435488 to Info.Album,
        ).forEach { (code, expected) ->
            assertEquals(expected, parse(code, "search_slot_style" to "Jazz", "titleName" to "First", "artistName" to "ABC"))
        }
    }

    @Test fun convertsProgressToSignedPlaybackMilliseconds() {
        assertEquals(Seek(-10_000, true), parse(33685504, "ctrl_progress" to 10_000))
        assertEquals(Seek(10_000, true), parse(33816576, "ctrl_progress" to 10_000))
        assertEquals(Seek(-15_000, true), parse(33685504, "ctrl_progress" to 0))
        assertEquals(Seek(15_000, true), parse(33816576))
        assertEquals(Seek(90_000, false), parse(37748736, "ctrl_progress" to 90_000))
        assertEquals(Seek(0, false), parse(37748736, "ctrl_progress" to 0))
    }

    @Test fun repeatAndShuffleAreAbsolute() {
        assertEquals(Mode.ListLoop, parse(33555456, "modeType" to 0))
        assertEquals(Mode.SingleLoop, parse(33555456, "modeType" to 1))
        assertEquals(Mode.Shuffle, parse(33555456, "modeType" to 2))
        assertEquals(PlaybackRepeatMode.All to false, Mode.ListLoop.repeat to Mode.ListLoop.shuffle)
        assertEquals(PlaybackRepeatMode.One to false, Mode.SingleLoop.repeat to Mode.SingleLoop.shuffle)
        assertEquals(PlaybackRepeatMode.Off to true, Mode.Shuffle.repeat to Mode.Shuffle.shuffle)
        assertEquals(FlymeVoiceCommand.Ignored(33555456), parse(33555456))
    }

    @Test fun ttsUsesMediaCenterKeysAndKeepsMessageText() {
        val message = FlymeVoiceFeedback.playing("专辑 《叶惠美》")
        val encoded = FlymeVoiceProtocol.ttsResult(message.text)
        assertEquals(setOf("msg", "hintId"), encoded.keys().asSequence().toSet())
        assertEquals("正在播放 专辑 《叶惠美》", encoded.getString("msg"))
        assertEquals("", encoded.getString("hintId"))
        assertEquals(FlymeVoiceFeedback("没找到 当前歌曲", failure = true), FlymeVoiceFeedback.info(Info.Title, null))
    }

    private fun parse(code: Int, vararg slots: Pair<String, Any>): FlymeVoiceCommand =
        FlymeVoiceProtocol.parseSemantic(JSONObject(mapOf("semantic" to code, *slots)).toString())

    // Vendor fields come from GeelyAutoSystemUIPlugin_jadx/sources/com/ecarx/eas/sdk/mediacenter/j.java.
    @Test fun playbackControlsAndFavoriteUseSemanticIds() {
        mapOf(
            33554433 to Control.Play,
            33554434 to Control.Pause,
            33554436 to Control.Next,
            33554440 to Control.Previous,
            33554448 to Control.Favorite,
        ).forEach { (id, command) ->
            assertEquals(command, FlymeVoiceProtocol.parseSemantic("""{"semantic":$id,"mediaCtrl":"control"}"""))
        }
    }

    @Test fun cancellationUsesOnlyTheDeclaredChannelIdentity() {
        val cancel = FlymeVoiceProtocol.channelInfo("dev.test.voice", "1.2.3")
        val declaration = FlymeVoiceProtocol.declaration("dev.test.voice", "1.2.3")
        assertEquals(setOf("vrChannelInfo"), cancel.keys().asSequence().toSet())
        val cancelledChannel = cancel.getJSONObject("vrChannelInfo")
        val declaredChannel = declaration.getJSONObject("vrChannelInfo")
        val keys = declaredChannel.keys().asSequence().toSet()
        assertEquals(keys, cancelledChannel.keys().asSequence().toSet())
        keys.forEach { key -> assertEquals(declaredChannel.get(key), cancelledChannel.get(key)) }
    }

    @Test fun searchPreservesVendorSlotsAndKind() {
        assertEquals(Search(Kind.ArtistSong, "晴天", "周杰伦"), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":67108872,"titleName":"晴天","artistName":"周杰伦"}""",
        ))
        assertEquals(Search(Kind.Artist, "", "周杰伦"), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":67108868,"artistName":"周杰伦"}""",
        ))
        assertEquals(Search(Kind.Album, "叶惠美", ""), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":67108880,"albumName":"叶惠美"}""",
        ))
        assertEquals(Search(Kind.ArtistAlbum, "叶惠美", "周杰伦"), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":67108896,"albumName":"叶惠美","artistName":"周杰伦"}""",
        ))
    }

    @Test fun searchFallsBackToRecognitionText() {
        // asrText is the experiment's compatibility field. j.java reads originInfo.
        assertEquals(Search(Kind.Song, "", "", "播放晴天"), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":67108866,"titleName":null,"asrText":"播放晴天"}""",
        ))
        assertEquals(Search(Kind.Song, "", "", "播放晴天"), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":67108866,"originInfo":"播放晴天"}""",
        ))
    }

    @Test fun unsupportedSemanticsAndEmptySearchAreIgnored() {
        assertEquals(FlymeVoiceCommand.Ignored(33554464), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":33554464,"mediaCtrl":"cancel_collect"}""",
        ))
        assertEquals(FlymeVoiceCommand.Ignored(67108866), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":67108866,"titleName":""}""",
        ))
    }
}
