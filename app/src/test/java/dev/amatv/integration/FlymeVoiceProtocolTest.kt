package dev.amatv.integration

import dev.amatv.integration.FlymeVoiceCommand.Control
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
            listOf(33554433, 33554434, 33554440, 33554436, 67108866, 67108868, 67108872, 67108880, 67108896, 33554448),
            List(types.length(), types::getInt),
        )
    }

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

    @Test fun searchCombinesVendorSlots() {
        assertEquals(FlymeVoiceCommand.Search("晴天 周杰伦"), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":67108872,"titleName":"晴天","artistName":"周杰伦","originInfo":"播放周杰伦的晴天"}""",
        ))
        assertEquals(FlymeVoiceCommand.Search("周杰伦"), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":67108868,"artistName":"周杰伦"}""",
        ))
        assertEquals(FlymeVoiceCommand.Search("叶惠美 周杰伦"), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":67108896,"albumName":"叶惠美","artistName":"周杰伦"}""",
        ))
    }

    @Test fun searchFallsBackToRecognitionText() {
        // asrText is the experiment's compatibility field. j.java reads originInfo.
        assertEquals(FlymeVoiceCommand.Search("播放晴天"), FlymeVoiceProtocol.parseSemantic(
            """{"semantic":67108866,"titleName":null,"asrText":"播放晴天"}""",
        ))
        assertEquals(FlymeVoiceCommand.Search("播放晴天"), FlymeVoiceProtocol.parseSemantic(
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
