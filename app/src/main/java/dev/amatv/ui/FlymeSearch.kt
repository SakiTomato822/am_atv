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
    recentTracks: List<Track>,
    loading: Boolean,
    setupMessage: String?,
    onSearch: (String) -> Unit,
    onOpenTrack: (Track, List<Track>) -> Unit,
    firstFocus: FocusRequester?,
) {
    val mandopopTerm = stringResource(R.string.browse_quick_mandopop)
    val quickTerms = remember(mandopopTerm) {
        listOf(mandopopTerm, "Doja Cat", "Ado", "YOASOBI", "周杰倫", "BLACKPINK", "Laufey", "Taylor Swift")
    }
    var query by rememberSaveable { mutableStateOf("") }
    var lastExecutedQuery by rememberSaveable { mutableStateOf("") }
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
                quickTerms = quickTerms,
                recentTracks = recentTracks,
                compact = typing,
                onTerm = { term -> query = term; submit(term, closeKeyboard = true) },
                onOpen = open,
            )
            results.isEmpty() -> Box(Modifier.fillMaxWidth().padding(top = 40.dp), Alignment.TopCenter) {
                if (loading) AppleLoadingIndicator()
                else Text(
                    stringResource(if (setupMessage.isNullOrBlank()) R.string.browse_search_empty else R.string.browse_search_error),
                    color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp,
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
        textStyle = TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium),
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
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Search, null, tint = Color.White.copy(alpha = 0.6f), modifier = Modifier.size(21.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.browse_search_hint), fontSize = 17.sp,
                            color = Color.White.copy(alpha = 0.42f), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
                        Icon(Icons.Rounded.Close, null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(19.dp))
                    }
                }
            }
        },
    )
}

/** Before a search: suggested terms, then recently played (hidden while the keyboard is up). */
@Composable
private fun FlymeSearchStart(
    quickTerms: List<String>,
    recentTracks: List<Track>,
    compact: Boolean,
    onTerm: (String) -> Unit,
    onOpen: (Track) -> Unit,
) {
    Column {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(quickTerms, key = { _, term -> term }) { index, term ->
                FocusSurface(
                    onClick = { onTerm(term) },
                    modifier = Modifier.height(36.dp).automationId("search-term-$index"),
                    cornerRadius = 18.dp,
                    padding = PaddingValues(horizontal = 16.dp),
                    background = Color.White.copy(alpha = 0.10f),
                ) {
                    Text(term, color = Color.White.copy(alpha = 0.88f), fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
        if (!compact && recentTracks.isNotEmpty()) {
            FlymeSearchHeading(stringResource(R.string.browse_recently_played), Modifier.padding(top = 22.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(recentTracks, key = { it.id }) { item -> FlymeSearchCard(item, onOpen) }
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
    Text(text, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
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
        background = Color.White.copy(alpha = 0.08f),
    ) {
        Column {
            MotionArtwork(track = item, modifier = Modifier.size(104.dp), animate = false,
                cornerRadius = if (circular) 52.dp else 10.dp)
            Spacer(Modifier.weight(1f))
            Text(item.title, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.kindLine(), color = Color.White.copy(alpha = 0.58f), fontSize = 14.sp,
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
        focusedBackground = Color.White.copy(alpha = 0.08f),
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            MotionArtwork(track = item, modifier = Modifier.size(40.dp), animate = false,
                cornerRadius = if (circular) 20.dp else 5.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = if (showKind) item.kindLine() else item.artist
                if (subtitle.isNotBlank()) {
                    Text(subtitle, color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp,
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
            Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            if (!circular && item.artist.isNotBlank()) {
                Text(item.artist, color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp,
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
