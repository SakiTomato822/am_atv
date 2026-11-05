package dev.amatv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import kotlinx.coroutines.delay

/**
 * Touch search for the car, after Apple Music: a search bar that types on tap, results grouped by
 * kind, and a compact list above the keyboard while it is up (it can cover half of a 16:9 screen).
 */
@Composable
internal fun FlymeSearchScreen(
    tracks: List<Track>,
    voiceSearchTerm: String?,
    recentTracks: List<Track>,
    loading: Boolean,
    setupMessage: String?,
    onSearch: (String) -> Unit,
    onOpenTrack: (Track, List<Track>) -> Unit,
    firstFocus: FocusRequester?,
) {
    val context = LocalContext.current
    val historyStore = remember(context) { SearchHistoryStore(context) }
    var history by remember { mutableStateOf(historyStore.load()) }
    fun saveSearch(term: String) {
        history = pushSearchHistory(history, term)
        historyStore.save(history)
    }
    var query by rememberSaveable { mutableStateOf("") }
    var lastExecutedQuery by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(voiceSearchTerm) {
        if (voiceSearchTerm != null) {
            query = voiceSearchTerm
            lastExecutedQuery = voiceSearchTerm
        }
    }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val keyboardOverlap = LocalCarKeyboardOverlap.current
    val typing = keyboardOverlap > 0.dp
    val fieldFocus = firstFocus ?: remember { FocusRequester() }

    fun submit(term: String, closeKeyboard: Boolean) {
        val trimmed = term.trim()
        if (closeKeyboard) {
            keyboard?.hide()
            focusManager.clearFocus()
        }
        if (closeKeyboard && trimmed.isNotEmpty()) saveSearch(trimmed)
        if (trimmed.isNotEmpty() && trimmed != lastExecutedQuery) {
            lastExecutedQuery = trimmed
            onSearch(trimmed)
        }
    }

    LaunchedEffect(query) {
        if (query.isNotBlank()) {
            delay(400L)
            submit(query, closeKeyboard = false)
        } else lastExecutedQuery = ""
    }

    val results = if (lastExecutedQuery.isNotBlank()) tracks else emptyList()
    val open: (Track) -> Unit = { item ->
        // Opening a result is what makes a typed query worth keeping.
        if (lastExecutedQuery.isNotBlank()) saveSearch(lastExecutedQuery)
        keyboard?.hide()
        focusManager.clearFocus()
        onOpenTrack(item, results.ifEmpty { recentTracks })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 27.dp)) {
        FlymeSearchBar(
            query = query,
            onQueryChange = { query = it },
            onSubmit = { submit(query, closeKeyboard = true) },
            onClear = { query = "" },
            focusRequester = fieldFocus,
        )
        Spacer(Modifier.height(14.dp))
        when {
            lastExecutedQuery.isBlank() -> FlymeSearchStart(
                history = history,
                recentTracks = recentTracks,
                compact = typing,
                onTerm = { term -> query = term; submit(term, closeKeyboard = true) },
                onRemove = { term ->
                    history = history - term
                    historyStore.save(history)
                },
                onClearHistory = {
                    history = emptyList()
                    historyStore.save(history)
                },
                onOpen = open,
            )
            results.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), Alignment.TopCenter) {
                if (loading) AppleLoadingIndicator()
                else Text(
                    stringResource(if (setupMessage.isNullOrBlank()) R.string.browse_search_empty else R.string.browse_search_error),
                    color = FlymeInk.copy(alpha = 0.6f), fontSize = 16.sp,
                )
            }
            typing -> FlymeCompactResults(results, bottomPadding = keyboardOverlap + 12.dp, onOpen = open)
            else -> FlymeGroupedResults(results, onOpen = open)
        }
    }
}

@Composable
private fun FlymeSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onClear: () -> Unit,
    focusRequester: FocusRequester,
) {
    val clearLabel = stringResource(R.string.browse_search_clear)
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(color = FlymeInk, fontSize = 17.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(FlymeLiveRed),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }),
        enabled = LocalFocusTargetsEnabled.current,
        modifier = Modifier.fillMaxWidth().height(46.dp)
            .automationId("search-field")
            .focusRequester(focusRequester),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxSize().clip(RoundedCornerShape(23.dp))
                    .background(FlymeInk.copy(alpha = 0.12f))
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = FlymeInk.copy(alpha = 0.6f), modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.browse_search_hint), fontSize = 17.sp,
                            color = FlymeInk.copy(alpha = 0.42f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    field()
                }
                if (query.isNotEmpty()) {
                    Box(
                        Modifier.size(36.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onClear)
                            .semantics { contentDescription = clearLabel }
                            .automationId("search-clear"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Close, null, tint = FlymeInk.copy(alpha = 0.7f), modifier = Modifier.size(19.dp))
                    }
                }
            }
        },
    )
}

/** Before a search: the user's recent searches, then recently played (hidden while the keyboard is up). */
@Composable
private fun FlymeSearchStart(
    history: List<String>,
    recentTracks: List<Track>,
    compact: Boolean,
    onTerm: (String) -> Unit,
    onRemove: (String) -> Unit,
    onClearHistory: () -> Unit,
    onOpen: (Track) -> Unit,
) {
    Column {
        if (history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                FlymeSearchHeading(stringResource(R.string.browse_search_history), Modifier.weight(1f))
                Text(stringResource(R.string.browse_search_history_clear), color = FlymeLiveRed, fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 10.dp).clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onClearHistory).padding(horizontal = 8.dp, vertical = 4.dp)
                        .automationId("search-history-clear"))
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(history, key = { _, term -> term }) { index, term ->
                    FocusSurface(
                        onClick = { onTerm(term) },
                        modifier = Modifier.height(36.dp).automationId("search-term-$index"),
                        cornerRadius = 18.dp,
                        padding = PaddingValues(start = 14.dp, end = 4.dp),
                        background = FlymeInk.copy(alpha = 0.10f),
                    ) {
                        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.History, null, tint = FlymeInk.copy(alpha = 0.5f),
                                modifier = Modifier.size(16.dp))
                            Text(term, color = FlymeInk.copy(alpha = 0.88f), fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp))
                            Box(Modifier.size(28.dp).clip(CircleShape).clickable { onRemove(term) },
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Close, null, tint = FlymeInk.copy(alpha = 0.45f),
                                    modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
        if (!compact && recentTracks.isNotEmpty()) {
            FlymeSearchHeading(stringResource(R.string.browse_recently_played),
                Modifier.padding(top = if (history.isEmpty()) 0.dp else 22.dp))
            // Scrolls up and down, like the rest of the car pages.
            LazyVerticalGrid(
                columns = GridCells.Adaptive(140.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                gridItems(recentTracks, key = { it.id }) { item -> FlymeSearchCard(item, onOpen) }
            }
        }
    }
}

/** While typing: one compact row per result, padded clear of the keyboard. */
@Composable
private fun FlymeCompactResults(results: List<Track>, bottomPadding: androidx.compose.ui.unit.Dp, onOpen: (Track) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
        itemsIndexed(results, key = { index, item -> "${item.id}#$index" }) { index, item ->
            FlymeResultRow(item, showKind = true, onOpen = onOpen,
                modifier = Modifier.fillMaxWidth().automationId("search-result-$index"))
        }
    }
}

/** Apple Music's result page: top result beside the songs, then a row per kind. */
@Composable
private fun FlymeGroupedResults(results: List<Track>, onOpen: (Track) -> Unit) {
    val top = results.first()
    val rest = results.drop(1)
    val songs = rest.filter { it.catalogKind == CatalogKind.Song || it.catalogKind == CatalogKind.MusicVideo }.take(8)
    val shelves = listOf(
        CatalogKind.Artist to R.string.browse_search_artists,
        CatalogKind.Album to R.string.browse_search_albums,
        CatalogKind.Playlist to R.string.browse_search_playlists,
        CatalogKind.Station to R.string.browse_search_stations,
    ).mapNotNull { (kind, title) -> rest.filter { it.catalogKind == kind }.takeIf { it.isNotEmpty() }?.let { title to it } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item(key = "top") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                Column(Modifier.width(330.dp)) {
                    FlymeSearchHeading(stringResource(R.string.browse_search_top))
                    FlymeTopResult(top, onOpen)
                }
                if (songs.isNotEmpty()) Column(Modifier.weight(1f)) {
                    FlymeSearchHeading(stringResource(R.string.browse_search_songs))
                    songs.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            pair.forEach { song ->
                                FlymeResultRow(song, showKind = false, onOpen = onOpen, modifier = Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
        shelves.forEach { (title, items) ->
            item(key = "shelf-$title") {
                FlymeSearchHeading(stringResource(title), Modifier.padding(top = 22.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    items(items, key = { it.id }) { item -> FlymeSearchCard(item, onOpen) }
                }
            }
        }
    }
}

@Composable
private fun FlymeSearchHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, color = FlymeInk, fontSize = 19.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.padding(bottom = 10.dp))
}

@Composable
private fun FlymeTopResult(item: Track, onOpen: (Track) -> Unit) {
    val circular = item.catalogKind == CatalogKind.Artist
    FocusSurface(
        onClick = { onOpen(item) },
        modifier = Modifier.fillMaxWidth().height(212.dp).automationId("search-top-result"),
        cornerRadius = 14.dp,
        focusedScale = 1.02f,
        padding = PaddingValues(18.dp),
        background = FlymeInk.copy(alpha = 0.08f),
    ) {
        Column {
            MotionArtwork(track = item, modifier = Modifier.size(104.dp), animate = false,
                cornerRadius = if (circular) 52.dp else 10.dp)
            Spacer(Modifier.weight(1f))
            ExplicitTitle(item.title, item.isExplicit,
                TextStyle(color = FlymeInk, fontSize = 21.sp, fontWeight = FontWeight.Bold), badgeSize = 17.dp)
            Text(item.kindLine(), color = FlymeInk.copy(alpha = 0.58f), fontSize = 14.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
    }
}

@Composable
private fun FlymeResultRow(item: Track, showKind: Boolean, onOpen: (Track) -> Unit, modifier: Modifier = Modifier) {
    val circular = item.catalogKind == CatalogKind.Artist
    FocusSurface(
        onClick = { onOpen(item) },
        modifier = modifier.height(52.dp),
        cornerRadius = 8.dp,
        focusedScale = 1.01f,
        padding = PaddingValues(horizontal = 6.dp),
        background = Color.Transparent,
        focusedBackground = FlymeInk.copy(alpha = 0.08f),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            MotionArtwork(track = item, modifier = Modifier.size(40.dp), animate = false,
                cornerRadius = if (circular) 20.dp else 5.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                ExplicitTitle(item.title, item.isExplicit,
                    TextStyle(color = FlymeInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold), badgeSize = 13.dp)
                val subtitle = if (showKind) item.kindLine() else item.artist
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = FlymeInk.copy(alpha = 0.55f), fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FlymeSearchCard(item: Track, onOpen: (Track) -> Unit) {
    val circular = item.catalogKind == CatalogKind.Artist
    FocusSurface(
        onClick = { onOpen(item) },
        modifier = Modifier.width(140.dp),
        cornerRadius = 10.dp,
        focusedScale = 1.04f,
        background = Color.Transparent,
        focusedBackground = Color.Transparent,
    ) {
        Column(horizontalAlignment = if (circular) Alignment.CenterHorizontally else Alignment.Start) {
            MotionArtwork(track = item, modifier = Modifier.size(140.dp), animate = false,
                cornerRadius = if (circular) 70.dp else 9.dp)
            Text(item.title, color = FlymeInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            if (!circular && item.artist.isNotBlank()) {
                Text(item.artist, color = FlymeInk.copy(alpha = 0.55f), fontSize = 11.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/** "Song · Artist", or just the kind where there is no artist (artists, stations). */
@Composable
private fun Track.kindLine(): String {
    val kind = stringResource(when (catalogKind) {
        CatalogKind.Song -> R.string.browse_kind_song
        CatalogKind.Artist -> R.string.browse_kind_artist
        CatalogKind.Album -> R.string.browse_kind_album
        CatalogKind.Playlist -> R.string.browse_kind_playlist
        CatalogKind.Station -> R.string.browse_kind_station
        CatalogKind.MusicVideo -> R.string.browse_kind_music_video
    })
    return listOf(kind, artist.takeIf { catalogKind != CatalogKind.Artist }.orEmpty())
        .filter { it.isNotBlank() }.joinToString(" · ")
}

/** Most recent first, case-insensitive duplicates collapse into the newest spelling. */
internal fun pushSearchHistory(history: List<String>, term: String, max: Int = 12): List<String> {
    val trimmed = term.trim()
    if (trimmed.isEmpty()) return history
    return (listOf(trimmed) + history.filterNot { it.equals(trimmed, ignoreCase = true) }).take(max)
}

/** Search history stays on the head unit; it is never sent anywhere. */
private class SearchHistoryStore(context: android.content.Context) {
    private val prefs = context.getSharedPreferences("am_atv_search_history", android.content.Context.MODE_PRIVATE)

    fun load(): List<String> = runCatching {
        val array = org.json.JSONArray(prefs.getString(KEY, "[]"))
        List(array.length()) { array.optString(it) }.filter { it.isNotBlank() }
    }.getOrDefault(emptyList())

    fun save(history: List<String>) {
        prefs.edit().putString(KEY, org.json.JSONArray(history).toString()).apply()
    }

    private companion object { const val KEY = "terms" }
}
