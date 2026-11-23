package dev.amatv.flyme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import dev.amatv.core.ContentState
import dev.amatv.core.PlayHistoryEntry
import dev.amatv.integration.LyricsLocalizationRequest
import dev.amatv.integration.LyricsRequestResult
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.MusicServices
import dev.amatv.integration.requestLyricsWithRetry
import dev.amatv.model.CatalogKind
import dev.amatv.model.Destination
import dev.amatv.model.LyricLine
import dev.amatv.model.LyricWord
import dev.amatv.model.Track
import dev.amatv.model.forDisplay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Immutable
internal data class WelcomeDemoContent(
    val wallRows: List<List<Track>>,
    val showcaseTracks: List<Track>,
    val lyricLines: List<LyricLine>,
    val showLyricsDemo: Boolean,
)

private const val WALL_ROW_COUNT = 5
private const val WALL_TILES_PER_ROW = 14
private const val MAX_WELCOME_LYRIC_LINES = 8

internal fun buildWelcomeWallRows(contentState: ContentState): List<List<Track>> {
    val candidates = contentState.shelvesFor(Destination.Browse)
        .flatMap { it.items }
        .filter { !it.artworkUrl.isNullOrBlank() }
        .distinctBy { it.artworkUrl }
    if (candidates.isEmpty()) return emptyList()

    return List(WALL_ROW_COUNT) { row ->
        List(WALL_TILES_PER_ROW) { col ->
            candidates[(row * 5 + col * 3) % candidates.size]
        }
    }
}

internal fun selectWelcomeShowcaseTracks(
    connectionState: MusicConnectionState,
    contentState: ContentState,
    playHistoryEntries: List<PlayHistoryEntry>,
): List<Track> {
    val browseItems = contentState.shelvesFor(Destination.Browse).flatMap { it.items }
    val browseSongs = browseItems.filter { it.catalogKind == CatalogKind.Song && !it.artworkUrl.isNullOrBlank() }
    val browseWithArtwork = browseItems.filter { !it.artworkUrl.isNullOrBlank() }
    val editorialBrowse = (browseSongs + browseWithArtwork).distinctBy { it.title to it.artist }

    return if (connectionState == MusicConnectionState.SignedIn) {
        val historyTracks = playHistoryEntries.map { it.toTrack() }
        (historyTracks + contentState.recentTracks + editorialBrowse)
            .filter { it.title.isNotBlank() && !it.artworkUrl.isNullOrBlank() }
            .distinctBy { it.title to it.artist }
            .take(3)
    } else {
        editorialBrowse.take(3)
    }
}

/** Rebases the opening lyric slice so the welcome demo starts singing immediately at t = 0. */
internal fun rebaseWelcomeLyricSlice(
    rawLines: List<LyricLine>,
    maxLines: Int = MAX_WELCOME_LYRIC_LINES,
): List<LyricLine> {
    val slice = rawLines
        .filter { !it.isTranslation && it.text.isNotBlank() }
        .take(maxLines)
    if (slice.isEmpty()) return emptyList()
    val baseStart = slice.first().startMs
    return slice.map { line ->
        line.copy(
            lineStartMs = line.lineStartMs?.let { (it - baseStart).coerceAtLeast(0L) },
            lineEndMs = line.lineEndMs?.let { (it - baseStart).coerceAtLeast(0L) },
            words = shiftWords(line.words, baseStart),
            backgroundWords = shiftWords(line.backgroundWords, baseStart),
            pronunciationWords = shiftWords(line.pronunciationWords, baseStart),
            backgroundPronunciationWords = shiftWords(line.backgroundPronunciationWords, baseStart),
        )
    }
}

private fun shiftWords(words: List<LyricWord>, baseStartMs: Long): List<LyricWord> = words.map { w ->
    w.copy(
        startMs = (w.startMs - baseStartMs).coerceAtLeast(0L),
        endMs = (w.endMs - baseStartMs).coerceAtLeast(0L),
    )
}

@Composable
internal fun rememberWelcomeDemoContent(
    connectionState: MusicConnectionState,
    contentState: ContentState,
    playHistoryEntries: List<PlayHistoryEntry>,
    services: MusicServices,
    lyricTranslationEnabled: Boolean,
    lyricPronunciationEnabled: Boolean,
): WelcomeDemoContent {
    val wallRows = remember(contentState.shelvesByDestination) {
        buildWelcomeWallRows(contentState)
    }
    val showcaseTracks = remember(connectionState, contentState.shelvesByDestination, contentState.recentTracks, playHistoryEntries) {
        selectWelcomeShowcaseTracks(connectionState, contentState, playHistoryEntries)
    }

    val appLanguageTag = LocalConfiguration.current.locales[0].toLanguageTag()
    val leadTrack = showcaseTracks.firstOrNull()
    var fetchedLyrics by remember { mutableStateOf<Pair<String, List<LyricLine>>?>(null) }

    LaunchedEffect(
        connectionState,
        leadTrack?.id,
        leadTrack?.catalogId,
        lyricTranslationEnabled,
        lyricPronunciationEnabled,
        appLanguageTag,
    ) {
        if (connectionState != MusicConnectionState.SignedIn || leadTrack?.catalogId == null) {
            fetchedLyrics = null
            return@LaunchedEffect
        }
        val key = "${leadTrack.lyricsIdentity()}|$lyricTranslationEnabled|$lyricPronunciationEnabled"
        val loc = LyricsLocalizationRequest(
            languageTag = appLanguageTag,
            pronunciationLanguageTag = "en-US",
            includeTranslations = lyricTranslationEnabled,
            includePronunciations = lyricPronunciationEnabled,
        )
        val result = withContext(Dispatchers.IO) {
            requestLyricsWithRetry({ services.lyricsFor(leadTrack, loc) })
        }
        val lines = when (result) {
            is LyricsRequestResult.Available -> result.lines
                .map { it.forDisplay(showTranslation = true, showPronunciation = lyricPronunciationEnabled) }
            LyricsRequestResult.Unavailable,
            is LyricsRequestResult.RetryableFailure,
            -> emptyList()
        }
        fetchedLyrics = key to rebaseWelcomeLyricSlice(lines)
    }

    val currentKey = leadTrack?.let { "${it.lyricsIdentity()}|$lyricTranslationEnabled|$lyricPronunciationEnabled" }
    val activeLyrics = if (connectionState == MusicConnectionState.SignedIn && fetchedLyrics?.first == currentKey) {
        fetchedLyrics?.second.orEmpty()
    } else {
        emptyList()
    }

    return WelcomeDemoContent(
        wallRows = wallRows,
        showcaseTracks = showcaseTracks,
        lyricLines = activeLyrics,
        showLyricsDemo = connectionState == MusicConnectionState.SignedIn && activeLyrics.isNotEmpty(),
    )
}
