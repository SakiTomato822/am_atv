package dev.amatv.integration

import android.os.IBinder
import android.os.Parcel
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface FlymeVoiceCommand {
    enum class Control : FlymeVoiceCommand { Play, Pause, Previous, Next, Favorite }
    data class Search(val query: String) : FlymeVoiceCommand
    data class Ignored(val semantic: Int) : FlymeVoiceCommand
}

internal object FlymeVoiceProtocol {
    const val SERVICE = "mediacenter"
    const val BROKER = "com.ecarx.eas.framework.sdk.IEASFrameworkService"
    const val CONNECT_CALLBACK = "com.ecarx.eas.framework.sdk.IConnectCallback"
    const val EX_CALLBACK = "com.ecarx.eas.xsf.mediacenter.IExCallback"
    const val SEMANTIC_LISTENER = "vrSemanticDataListener"
    private const val SUPPORT_REPLY = "com.ecarx.sdk.openapi.msg.SupportServiceRetMessage"
    private const val MODULE = "MediaCenterAPI"

    private const val PLAY = 33554433
    private const val PAUSE = 33554434
    private const val NEXT = 33554436
    private const val PREVIOUS = 33554440
    private const val FAVORITE = 33554448
    private const val SONG = 67108866
    private const val ARTIST = 67108868
    private const val ARTIST_SONG = 67108872
    private const val ALBUM = 67108880
    private const val ARTIST_ALBUM = 67108896

    // Parcelable body. The AIDL presence marker and optional Binder belong to the caller.
    fun writeMessage(
        method: String,
        parameter: String,
        writeString: (String) -> Unit,
        writeByteArray: (ByteArray) -> Unit,
    ) {
        writeString(SERVICE)
        writeString(MODULE)
        writeString(method)
        writeByteArray(parameter.toByteArray(Charsets.UTF_8))
        writeByteArray(byteArrayOf())
    }

    fun readReply(parcel: Parcel): Reply? = with(parcel) {
        if (readInt() == 0) return null
        val brokerCode = readInt()
        val brokerMessage = readString()
        createByteArray() // attachInfo
        val className = readString() // writeParcelable writes the class name before the body
        if (className == null) return Reply(brokerCode, brokerMessage)
        check(className == SUPPORT_REPLY) { "Unexpected EAS reply class: $className" }
        Reply(brokerCode, brokerMessage, readInt(), readString(),
            createByteArray()?.toString(Charsets.UTF_8), readStrongBinder())
    }

    data class Reply(
        val brokerCode: Int,
        val brokerMessage: String?,
        val serviceCode: Int? = null,
        val serviceMessage: String? = null,
        val data: String? = null,
        val binder: IBinder? = null,
    ) {
        val accepted get() = brokerCode == 200 && serviceCode == 200
        fun describe() = "broker=$brokerCode ($brokerMessage) service=$serviceCode ($serviceMessage) " +
            "data=$data binderPresent=${binder != null}"
    }

    fun channelInfo(packageName: String, version: String): JSONObject =
        JSONObject().put("vrChannelInfo", JSONObject()
            .put("mediaPackageName", packageName)
            .put("mediaVersion", version)
            .put("mediaDescription", "AM Folio")
            .put("channelDataType", 0))

    fun declaration(packageName: String, version: String): JSONObject = channelInfo(packageName, version)
        .put("semanticsTypes", JSONArray(listOf(PLAY, PAUSE, PREVIOUS, NEXT,
            SONG, ARTIST, ARTIST_SONG, ALBUM, ARTIST_ALBUM, FAVORITE)))

    fun parseSemantic(raw: String): FlymeVoiceCommand {
        val json = JSONObject(raw)
        val semantic = json.optInt("semantic")
        return when (semantic) {
            PLAY -> FlymeVoiceCommand.Control.Play
            PAUSE -> FlymeVoiceCommand.Control.Pause
            PREVIOUS -> FlymeVoiceCommand.Control.Previous
            NEXT -> FlymeVoiceCommand.Control.Next
            FAVORITE -> FlymeVoiceCommand.Control.Favorite
            SONG, ARTIST, ARTIST_SONG, ALBUM, ARTIST_ALBUM -> {
                val title = if (semantic == ALBUM || semantic == ARTIST_ALBUM) "albumName" else "titleName"
                val query = listOf(json.slot(title), json.slot("artistName"))
                    .filter(String::isNotEmpty).joinToString(" ")
                    .ifBlank { json.slot("asrText").ifBlank { json.slot("originInfo") } }
                if (query.isEmpty()) FlymeVoiceCommand.Ignored(semantic) else FlymeVoiceCommand.Search(query)
            }
            else -> FlymeVoiceCommand.Ignored(semantic)
        }
    }

    private fun JSONObject.slot(name: String): String = if (isNull(name)) "" else optString(name).trim()
}
