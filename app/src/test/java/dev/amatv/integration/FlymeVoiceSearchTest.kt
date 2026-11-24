package dev.amatv.integration

import dev.amatv.integration.FlymeVoiceCommand.Search
import dev.amatv.integration.FlymeVoiceCommand.Search.Kind
import dev.amatv.integration.voice.NameEncoder
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlymeVoiceSearchTest {
    private val firstSong = item("1", "First")
    private val secondSong = item("2", "Second")
    private val logs = mutableListOf<String>()

    @Test fun trackAnnouncementsAndMetadataAnswersUseTheCurrentTrack() {
        val track = firstSong.copy(artist = "Artist", album = "Album")
        assertEquals("正在播放 First – Artist", FlymeVoiceFeedback.track(track).text)
        assertEquals("First", FlymeVoiceFeedback.info(FlymeVoiceCommand.Info.Title, track).text)
        assertEquals("Artist", FlymeVoiceFeedback.info(FlymeVoiceCommand.Info.Artist, track).text)
        assertEquals("Album", FlymeVoiceFeedback.info(FlymeVoiceCommand.Info.Album, track).text)
    }

    @Test fun artistUsesExactNormalizedNameAndKeepsTopSongsInOrder() = runTest {
        val artist = item("3", "ＡＢＣ　Ｂａｎｄ", CatalogKind.Artist)
        val otherArtist = item("4", "ABC Band Live", CatalogKind.Artist)
        val queue = resolve(
            Search(Kind.Artist, "", "abc band"),
            listOf(firstSong, otherArtist, artist),
            topSongs = {
                assertEquals(artist, it)
                listOf(secondSong, firstSong)
            },
        )

        assertEquals(FlymeVoiceQueue(listOf(secondSong, firstSong), spokenName = "${artist.title} 的歌"), queue)
        assertTrue(logs.single().contains("path=matched match=exact id=3 name=ＡＢＣ　Ｂａｎｄ queueSize=2"))
    }

    @Test fun missingArtistFallsBackToFirstSongInSearchResults() = runTest {
        val queue = resolve(Search(Kind.Artist, "", "Missing"), listOf(secondSong, firstSong))

        assertEquals(FlymeVoiceQueue(listOf(secondSong)), queue)
        assertTrue(logs.any { it.contains("fallback=artist-not-found") })
        assertTrue(logs.last().contains("queueSize=1"))
    }

    @Test fun nonExactArtistUsesFirstArtistAndLogsFallback() = runTest {
        val artist = item("3", "ABC Band", CatalogKind.Artist)
        val queue = resolve(Search(Kind.Artist, "", "ABC"), listOf(firstSong, artist), topSongs = {
            assertEquals(artist, it)
            listOf(secondSong)
        })

        assertEquals(FlymeVoiceQueue(listOf(secondSong), spokenName = "${artist.title} 的歌"), queue)
        assertTrue(logs.single().contains("path=best-match-fallback(selection-protocol-unverified) match=fallback-first-result id=3"))
    }

    @Test fun albumMatchesTitleAndOptionalArtistAndKeepsTrackOrder() = runTest {
        val otherAlbum = item("3", "Volume 1", CatalogKind.Album, "Other Artist")
        val album = item("4", "Ｖｏｌｕｍｅ　１", CatalogKind.Album, "ＡＢＣ")
        val tracks = listOf(secondSong, firstSong, secondSong)
        val candidates = listOf(firstSong, otherAlbum, album)

        val withArtist = resolve(Search(Kind.ArtistAlbum, "volume 1", "abc"), candidates, albumTracks = {
            assertEquals(album, it)
            tracks
        })
        val withoutArtist = resolve(Search(Kind.Album, "volume 1", ""), candidates, albumTracks = {
            assertEquals(otherAlbum, it)
            tracks
        })

        assertEquals(FlymeVoiceQueue(tracks, album, "专辑 ${album.title}"), withArtist)
        assertEquals(FlymeVoiceQueue(tracks, otherAlbum, "专辑 ${otherAlbum.title}"), withoutArtist)
        assertTrue(logs.first().contains("match=exact id=4 name=Ｖｏｌｕｍｅ　１ queueSize=3"))
    }

    @Test fun songAndArtistPlaysOnlyFirstExactSong() = runTest {
        val song = secondSong.copy(title = "first", artist = "ＡＢＣ")
        val anotherMatch = firstSong.copy(artist = "abc")
        val queue = resolve(Search(Kind.ArtistSong, "First", "abc"), listOf(firstSong, song, anotherMatch))

        assertEquals(FlymeVoiceQueue(listOf(song)), queue)
        assertTrue(logs.single().contains("match=exact-credit id=2"))
    }

    @Test fun buGaiTitleRetryFindsTheDuetInsteadOfPlayingKuBuChuLai() = runTest {
        val command = Search(Kind.ArtistSong, "不该", "张惠妹")
        val wrong = item("wrong", "哭不出來", artist = "張惠妹")
        val solo = item("solo", "不該", artist = "周杰倫")
        val duet = item("duet", "不該", artist = "周杰倫 & 張惠妹")
        val terms = mutableListOf<String>()
        val queue = resolveFlymeVoiceSearch(
            command,
            search = { term ->
                terms += term
                if (term == command.query) listOf(wrong) else listOf(solo, duet)
            },
            artistTopSongs = { error("Unexpected artist lookup") },
            collectionTracks = { error("Unexpected collection lookup") },
            log = logs::add,
            index = VoiceCatalogIndex(command, NameEncoder(simplify = { it.replace('該', '该').replace('張', '张') })),
        )

        assertEquals(listOf("不该 张惠妹", "不该"), terms)
        assertEquals(FlymeVoiceQueue(listOf(duet)), queue)
        assertTrue(logs.single().contains("match=exact-credit id=duet"))
    }

    @Test fun misrecognizedFavoriteSongsMatchesLocalizedLibraryPlaylist() {
        val command = FlymeVoiceCommand.Style("好歌曲", "影视综艺好歌曲")
        val chinese = item("zh", "喜好歌曲", CatalogKind.Playlist)
        val english = item("en", "Favorite Songs", CatalogKind.Playlist)
        val unrelated = item("other", "摇滚乐", CatalogKind.Playlist)
        assertEquals(chinese, matchVoiceStylePlaylist(command, listOf(unrelated, chinese)))
        assertEquals(english, matchVoiceStylePlaylist(command, listOf(unrelated, english)))
    }

    @Test fun stylePlaylistMatchesOriginContainmentAndPinyin() {
        val film = item("film", "影视综艺", CatalogKind.Playlist)
        assertEquals(film, matchVoiceStylePlaylist(FlymeVoiceCommand.Style("主题", "播放影视综艺"), listOf(film)))

        val jazz = item("jazz", "爵士", CatalogKind.Playlist)
        val encoder = NameEncoder(pinyin = { "jue shi" })
        assertEquals(jazz, matchVoiceStylePlaylist(FlymeVoiceCommand.Style("绝世"), listOf(jazz), encoder))
    }

    @Test fun missingAlbumDoesNotPlayAnUnrelatedSong() = runTest {
        val queue = resolve(Search(Kind.Album, "Missing", ""), listOf(firstSong))

        assertEquals(FlymeVoiceQueue(emptyList()), queue)
        assertTrue(logs.single().contains("match=none id=null name=null queueSize=0"))
    }

    @Test fun artistAlbumFallbackKeepsTheRequestedPerformer() = runTest {
        val otherArtist = item("3", "Volume 1", CatalogKind.Album, "Other")
        val sameArtist = item("4", "Volume 2", CatalogKind.Album, "ABC")
        val queue = resolve(Search(Kind.ArtistAlbum, "Volume 1", "ABC"), listOf(otherArtist, sameArtist),
            albumTracks = {
                assertEquals(sameArtist, it)
                listOf(firstSong)
            },
        )
        assertEquals(FlymeVoiceQueue(listOf(firstSong), collection = sameArtist, spokenName = "专辑 ${sameArtist.title}"), queue)
        assertTrue(logs.single().contains("match=fallback-first-result id=4"))
    }

    @Test fun exactLibraryMatchUsesNormalizedTraditionalAndSimplifiedNamesBeforeCatalog() = runTest {
        val library = item("library", "張惠妹", CatalogKind.Artist)
        val command = Search(Kind.Artist, "", "张惠妹")
        val queue = resolveFlymeVoiceSearch(
            command,
            search = { error("Unexpected catalog lookup") },
            artistTopSongs = { assertEquals(library, it); listOf(firstSong) },
            collectionTracks = { error("Unexpected collection lookup") },
            log = { logs.add(it) },
            librarySearch = { term, collection ->
                assertEquals("张惠妹", term)
                assertEquals(LibraryCollection.Artists, collection)
                listOf(library)
            },
            index = VoiceCatalogIndex(command, NameEncoder(simplify = { it.replace('張', '张') })),
        )
        assertEquals(FlymeVoiceQueue(listOf(firstSong), spokenName = "${library.title} 的歌"), queue)
        assertTrue(logs.single().contains("match=exact id=library"))
    }

    @Test fun aliasRecallsCanonicalArtistBeforeFirstResultFallback() = runTest {
        val artist = item("artist", "Bruno Mars", CatalogKind.Artist)
        val command = Search(Kind.Artist, "", "布鲁诺马尔斯")
        val queue = resolveFlymeVoiceSearch(
            command,
            search = { term -> if (term == "Bruno Mars") listOf(artist) else listOf(firstSong) },
            artistTopSongs = {
                assertEquals(artist, it)
                listOf(firstSong, secondSong)
            },
            collectionTracks = { error("Unexpected collection lookup") },
            log = { logs.add(it) },
        )
        assertEquals(FlymeVoiceQueue(listOf(firstSong, secondSong), spokenName = "${artist.title} 的歌"), queue)
        assertTrue(logs.single().contains("match=alias id=artist"))
    }

    @Test fun libraryAlbumUsesItsCompleteCatalogTrackList() = runTest {
        val library = item("library", "Volume 1", CatalogKind.Album, "ABC")
            .copy(resourceType = LibraryCollection.Albums.resourceType)
        val catalog = item("catalog", "Volume 1", CatalogKind.Album, "ABC")
        val tracks = listOf(secondSong, firstSong)
        val queue = resolveFlymeVoiceSearch(
            Search(Kind.ArtistAlbum, "Volume 1", "ABC"),
            search = { error("Unexpected catalog search") },
            artistTopSongs = { error("Unexpected artist lookup") },
            collectionTracks = {
                assertEquals(catalog, it)
                tracks
            },
            log = { logs.add(it) },
            librarySearch = { _, _ -> listOf(library) },
            catalogAlbum = {
                assertEquals(library, it)
                catalog
            },
        )
        assertEquals(FlymeVoiceQueue(tracks, collection = catalog, spokenName = "专辑 ${catalog.title}"), queue)
        assertTrue(logs.single().contains("match=exact id=catalog"))
    }

    @Test fun pinyinRecallRanksTheMatchingArtistAheadOfAnUnrelatedArtist() = runTest {
        val artist = item("artist", "张惠妹", CatalogKind.Artist)
        val unrelated = item("other", "ABC", CatalogKind.Artist)
        val command = Search(Kind.Artist, "", "张慧妹")
        val encoder = NameEncoder(pinyin = { name ->
            "zhang hui mei".takeIf { name == command.artist || name == artist.title }
        })
        val queue = resolveFlymeVoiceSearch(
            command,
            search = { term -> if (term == "zhang hui mei") listOf(artist) else listOf(unrelated) },
            artistTopSongs = {
                assertEquals(artist, it)
                listOf(secondSong)
            },
            collectionTracks = { error("Unexpected collection lookup") },
            log = { logs.add(it) },
            index = VoiceCatalogIndex(command, encoder),
        )
        assertEquals(FlymeVoiceQueue(listOf(secondSong), spokenName = "${artist.title} 的歌"), queue)
        assertTrue(logs.single().contains("match=phonetic id=artist"))
    }

    private suspend fun resolve(
        command: Search,
        results: List<Track>,
        topSongs: suspend (Track) -> List<Track> = { error("Unexpected artist lookup") },
        albumTracks: suspend (Track) -> List<Track> = { error("Unexpected album lookup") },
    ) = resolveFlymeVoiceSearch(
        command,
        search = { term -> assertEquals(command.query, term); results },
        artistTopSongs = topSongs,
        collectionTracks = albumTracks,
        log = { logs.add(it) },
    )

    private fun item(id: String, title: String, kind: CatalogKind = CatalogKind.Song, artist: String = "") =
        Track(id, title, artist, "", 0, emptyList(), emptyList(), catalogId = id, catalogKind = kind)
}
