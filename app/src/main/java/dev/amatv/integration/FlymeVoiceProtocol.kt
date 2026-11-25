package dev.amatv.integration

import android.os.IBinder
import android.os.Parcel
import dev.amatv.model.CatalogKind
import org.json.JSONArray
import org.json.JSONObject

internal sealed interface FlymeVoiceCommand {
    enum class Control : FlymeVoiceCommand { Play, Pause, Previous, Next, Favorite, Replay }
    data class Seek(val milliseconds: Long, val relative: Boolean) : FlymeVoiceCommand
    enum class Mode(val repeat: PlaybackRepeatMode, val shuffle: Boolean) : FlymeVoiceCommand {
        ListLoop(PlaybackRepeatMode.All, false),
        SingleLoop(PlaybackRepeatMode.One, false),
        Shuffle(PlaybackRepeatMode.Off, true),
    }
    enum class Page : FlymeVoiceCommand { Player, Lyrics }
    enum class Info : FlymeVoiceCommand { Title, Artist, Album }
    enum class ListSource(val label: String) : FlymeVoiceCommand {
        Favorites("喜好歌曲"), History("最近听过的歌"), Recommendations("每日推荐"), Chart("热门榜"),
    }
    data class Style(val name: String, val originInfo: String = "") : FlymeVoiceCommand
    data class Search(
        val kind: Kind,
        val title: String,
        val artist: String,
        val recognitionText: String = "",
        val searchOnly: Boolean = false,
    ) : FlymeVoiceCommand {
        enum class Kind(val catalogKind: CatalogKind, val libraryCollection: LibraryCollection) {
            Song(CatalogKind.Song, LibraryCollection.Songs),
            Artist(CatalogKind.Artist, LibraryCollection.Artists),
            ArtistSong(CatalogKind.Song, LibraryCollection.Songs),
            Album(CatalogKind.Album, LibraryCollection.Albums),
            ArtistAlbum(CatalogKind.Album, LibraryCollection.Albums),
        }

        val targetTitle: String get() = if (kind == Kind.Artist) artist.ifEmpty { title } else title

        val query: String
            get() = if (kind == Kind.Artist) targetTitle.ifEmpty { recognitionText }
                else listOf(title, artist).filter(String::isNotEmpty).joinToString(" ").ifEmpty { recognitionText }

        fun acceptsVoiceKind(candidate: CatalogKind): Boolean = kind.catalogKind == candidate
    }
    data class Ignored(val semantic: Int) : FlymeVoiceCommand
}

internal object FlymeVoiceProtocol {
    const val SERVICE = "mediacenter"
    const val BROKER = "com.ecarx.eas.framework.sdk.IEASFrameworkService"
    const val CONNECT_CALLBACK = "com.ecarx.eas.framework.sdk.IConnectCallback"
    const val EX_CALLBACK = "com.ecarx.eas.xsf.mediacenter.IExCallback"
    const val SEMANTIC_LISTENER = "vrSemanticDataListener"
    const val TTS_LISTENER = "VrTtsResultListener"
    const val SEND_TTS = "sendVrTtsActionResult"
    private const val SUPPORT_REPLY = "com.ecarx.sdk.openapi.msg.SupportServiceRetMessage"
    private const val MODULE = "MediaCenterAPI"

    private const val PLAY = 33554433
    private const val PAUSE = 33554434
    private const val NEXT = 33554436
    private const val PREVIOUS = 33554440
    private const val FAVORITE = 33554448
    private const val MODE = 33555456
    private const val STOP = 33556480
    private const val START = 33558528
    private const val CONTINUE = 33562624
    private const val REPLAY = 33619968
    private const val REWIND = 33685504
    private const val FORWARD = 33816576
    private const val PLAYER = 34603008
    private const val LYRICS = 35651584
    private const val SEEK = 37748736
    private const val SONG = 67108866
    private const val ARTIST = 67108868
    private const val ARTIST_SONG = 67108872
    private const val ALBUM = 67108880
    private const val ARTIST_ALBUM = 67108896
    private const val STYLE = 67108928
    private const val CHART = 67108992
    private const val FAVORITES = 67109120
    private const val SEARCH_SONG = 67125248
    private const val SEARCH_ARTIST = 67141632
    private const val SEARCH_ARTIST_SONG = 67174400
    private const val RECOMMENDATIONS = 67239936
    private const val HISTORY = 67633152
    private const val CONTINUE_MUSIC = 69206016
    private const val QUERY_TITLE = 268435457
    private const val QUERY_ARTIST = 268435458
    private const val QUERY_ALBUM = 268435488

    // Parcelable body. The AIDL presence marker and optional Binder belong to the caller.
    fun writeMessage(
        method: String,
        parameter: String,
        writeString: (String) -> Unit,
        writeByteArray: (ByteArray) -> Unit,
    ) = writeMessage(MODULE, method, parameter.toByteArray(Charsets.UTF_8), writeString, writeByteArray)

    fun writeMessage(
        module: String,
        method: String,
        parameter: ByteArray,
        writeString: (String) -> Unit,
        writeByteArray: (ByteArray) -> Unit,
    ) {
        writeString(SERVICE)
        writeString(module)
        writeString(method)
        writeByteArray(parameter)
        writeByteArray(byteArrayOf())
    }

    fun readReply(parcel: Parcel): Reply<String>? = readReply(parcel) { it.toString(Charsets.UTF_8) }

    fun <T> readReply(parcel: Parcel, decode: (ByteArray) -> T): Reply<T>? = with(parcel) {
        if (readInt() == 0) return null
        val brokerCode = readInt()
        val brokerMessage = readString()
        createByteArray() // attachInfo
        val className = readString() // writeParcelable writes the class name before the body
        if (className == null) return Reply(brokerCode, brokerMessage)
        check(className == SUPPORT_REPLY) { "Unexpected EAS reply class: $className" }
        Reply(brokerCode, brokerMessage, readInt(), readString(),
            createByteArray()?.let(decode), readStrongBinder())
    }

    data class Reply<T>(
        val brokerCode: Int,
        val brokerMessage: String?,
        val serviceCode: Int? = null,
        val serviceMessage: String? = null,
        val data: T? = null,
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
            SONG, ARTIST, ARTIST_SONG, ALBUM, ARTIST_ALBUM, FAVORITE,
            MODE, STOP, START, CONTINUE, REPLAY, REWIND, FORWARD, PLAYER, LYRICS, SEEK,
            STYLE, CHART, FAVORITES, SEARCH_SONG, SEARCH_ARTIST, SEARCH_ARTIST_SONG,
            RECOMMENDATIONS, HISTORY, CONTINUE_MUSIC, QUERY_TITLE, QUERY_ARTIST, QUERY_ALBUM)))

    fun ttsResult(message: String): JSONObject = JSONObject().put("msg", message).put("hintId", "")

    fun parseSemantic(raw: String): FlymeVoiceCommand {
        val json = JSONObject(raw)
        val semantic = json.optInt("semantic")
        return when (semantic) {
            PLAY, START, CONTINUE, CONTINUE_MUSIC -> FlymeVoiceCommand.Control.Play
            PAUSE, STOP -> FlymeVoiceCommand.Control.Pause
            PREVIOUS -> FlymeVoiceCommand.Control.Previous
            NEXT -> FlymeVoiceCommand.Control.Next
            FAVORITE -> FlymeVoiceCommand.Control.Favorite
            REPLAY -> FlymeVoiceCommand.Control.Replay
            REWIND, FORWARD -> {
                // MediaCenter forwards RECOG_SLOT_TIME unchanged. Use the playback API's milliseconds.
                val milliseconds = json.optLong("ctrl_progress").takeIf { it > 0 } ?: 15_000L
                FlymeVoiceCommand.Seek(if (semantic == REWIND) -milliseconds else milliseconds, relative = true)
            }
            SEEK -> FlymeVoiceCommand.Seek(json.optLong("ctrl_progress"), relative = false)
            MODE -> when (json.optInt("modeType", -1)) {
                0 -> FlymeVoiceCommand.Mode.ListLoop
                1 -> FlymeVoiceCommand.Mode.SingleLoop
                2 -> FlymeVoiceCommand.Mode.Shuffle
                else -> FlymeVoiceCommand.Ignored(semantic)
            }
            PLAYER -> FlymeVoiceCommand.Page.Player
            LYRICS -> FlymeVoiceCommand.Page.Lyrics
            QUERY_TITLE -> FlymeVoiceCommand.Info.Title
            QUERY_ARTIST -> FlymeVoiceCommand.Info.Artist
            QUERY_ALBUM -> FlymeVoiceCommand.Info.Album
            FAVORITES -> FlymeVoiceCommand.ListSource.Favorites
            HISTORY -> FlymeVoiceCommand.ListSource.History
            RECOMMENDATIONS -> FlymeVoiceCommand.ListSource.Recommendations
            CHART -> FlymeVoiceCommand.ListSource.Chart
            STYLE -> FlymeVoiceCommand.Style(
                json.slot("search_slot_style").ifEmpty { json.slot("typeName") }.ifEmpty { json.slot("subTypeName") },
                json.slot("originInfo"),
            )
            SONG, ARTIST, ARTIST_SONG, ALBUM, ARTIST_ALBUM, SEARCH_SONG, SEARCH_ARTIST, SEARCH_ARTIST_SONG -> {
                val kind = when (semantic) {
                    SONG, SEARCH_SONG -> FlymeVoiceCommand.Search.Kind.Song
                    ARTIST, SEARCH_ARTIST -> FlymeVoiceCommand.Search.Kind.Artist
                    ARTIST_SONG, SEARCH_ARTIST_SONG -> FlymeVoiceCommand.Search.Kind.ArtistSong
                    ALBUM -> FlymeVoiceCommand.Search.Kind.Album
                    else -> FlymeVoiceCommand.Search.Kind.ArtistAlbum
                }
                val titleSlot = if (semantic == ALBUM || semantic == ARTIST_ALBUM) "albumName" else "titleName"
                val search = FlymeVoiceCommand.Search(
                    kind,
                    json.slot(titleSlot),
                    json.slot("artistName"),
                    json.slot("asrText").ifEmpty { json.slot("originInfo") },
                    searchOnly = semantic == SEARCH_SONG || semantic == SEARCH_ARTIST || semantic == SEARCH_ARTIST_SONG,
                )
                if (search.query.isEmpty()) FlymeVoiceCommand.Ignored(semantic) else search
            }
            else -> FlymeVoiceCommand.Ignored(semantic)
        }
    }

    private fun JSONObject.slot(name: String): String = if (isNull(name)) "" else optString(name).trim()
}
