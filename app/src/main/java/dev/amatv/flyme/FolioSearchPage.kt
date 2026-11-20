package dev.amatv.flyme

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.CatalogSearchSuggestions
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry
import org.json.JSONArray

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolioSearchPage(
    currentTrack: Track,
    isPlaying: Boolean,
    bottomPadding: Dp,
    search: suspend (String) -> List<Track>,
    suggest: suspend (String) -> CatalogSearchSuggestions,
    onOpenItem: (Track, List<Track>) -> Unit,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current
    val session = folioSearchSession
    val query = session.query
    val submission = session.submission
    val suggestions = session.suggestions
    DisposableEffect(session) {
        onDispose { session.dismissSuggestions() }
    }
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val preferences = remember(context) {
        context.getSharedPreferences("am_atv_search_history", android.content.Context.MODE_PRIVATE)
    }
    fun readHistory(): List<String> {
        val terms = JSONArray(preferences.getString("terms", "[]"))
        return List(terms.length()) { terms.getString(it) }.take(12)
    }
    var history by remember(preferences) { mutableStateOf(readHistory()) }
    fun submit(term: String) {
        val trimmed = term.trim()
        if (trimmed.isEmpty()) return
        history = pushSearchHistory(readHistory(), trimmed)
        preferences.edit().putString("terms", JSONArray(history).toString()).apply()
        keyboard?.hide()
        session.submit(trimmed, search)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val innerWidth = (maxWidth - tierSpec.px - tierSpec.pr).coerceAtLeast(1.dp)
        val listCols = adaptiveColumnCount(innerWidth, tierSpec.liw, tierSpec.lstGap)
        val results = submission?.results
        val sections = remember(results) { results?.takeIf { it.isNotEmpty() }?.let(::folioSearchSections) }
        val cardRows = remember(sections, listCols) {
            sections?.let {
                mapOf(
                    R.string.browse_search_albums to it.albums.chunked(listCols),
                    R.string.browse_search_artists to it.artists.chunked(listCols),
                    R.string.browse_search_playlists to it.playlists.chunked(listCols),
                )
            }.orEmpty()
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = tierSpec.px,
                end = tierSpec.pr,
                top = 4.dp,
                bottom = bottomPadding,
            ),
        ) {
            item(key = "search-bar", contentType = "search-bar") {
                val shape = RoundedCornerShape(24.dp)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp, bottom = 16.dp)
                        .height(48.dp)
                        .clip(shape)
                        .background(palette.rb)
                        .border(1.dp, palette.glassb, shape)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = stringResource(R.string.nav_search),
                        tint = palette.fg2,
                        modifier = Modifier
                            .size(20.dp)
                            .clickable(enabled = query.isNotBlank(), role = Role.Button) { submit(query) },
                    )
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = stringResource(R.string.browse_search_hint),
                                color = palette.fg2,
                                fontFamily = fontFamily,
                                fontSize = 15.sp,
                                maxLines = 1,
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { session.edit(it, suggest) },
                            singleLine = true,
                            textStyle = TextStyle(
                                color = palette.fg,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 15.sp,
                            ),
                            cursorBrush = SolidColor(palette.accent),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(
                                onSearch = { submit(query) },
                            ),
                            modifier = Modifier.fillMaxWidth().onPreviewKeyEvent { event ->
                                val key = event.nativeKeyEvent
                                if (key.keyCode != KeyEvent.KEYCODE_ENTER &&
                                    key.keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER
                                ) return@onPreviewKeyEvent false
                                if (key.action == KeyEvent.ACTION_UP) submit(query)
                                true
                            },
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.browse_search_clear),
                            tint = palette.fg2,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { session.edit("", suggest) },
                        )
                    }
                }
            }

            if (suggestions != null && (suggestions.terms.isNotEmpty() || suggestions.topResults.isNotEmpty())) {
                item(key = "search-suggestions", contentType = "search-suggestions") {
                    FolioSearchSuggestionsPanel(
                        suggestions = suggestions,
                        currentTrack = currentTrack,
                        isPlaying = isPlaying,
                        onSubmit = ::submit,
                        onOpen = { item ->
                            session.dismissSuggestions()
                            keyboard?.hide()
                            onOpenItem(item, listOf(item))
                        },
                    )
                }
            }

            when {
                submission == null -> {
                    if (query.isBlank() && history.isNotEmpty()) {
                        item(key = "search-history", contentType = "search-history") {
                            FolioSectionTitle(stringResource(R.string.browse_search_history))
                            FlowRow(
                                modifier = Modifier.padding(bottom = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                history.forEach { term ->
                                    FolioChip(label = term, selected = false, onClick = { submit(term) })
                                }
                            }
                        }
                    }
                }
                submission.results == null -> {
                    item(key = "search-loading", contentType = "loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = palette.fg2,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                sections == null -> {
                    item(key = "search-empty", contentType = "empty") {
                        Text(
                            text = if (submission.failed) stringResource(R.string.browse_search_error)
                            else stringResource(R.string.app_search_no_results_format, submission.term),
                            color = palette.fg2,
                            fontFamily = fontFamily,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(vertical = 24.dp),
                        )
                    }
                }
                else -> {
                    item(key = "search-best", contentType = "search-best") {
                        FolioSectionTitle(stringResource(R.string.browse_search_top))
                        val best = sections.best.single()
                        FolioListRowCard(best, onClick = { onOpenItem(best, sections.best) }, titleMaxLines = 2)
                    }
                    if (sections.songs.isNotEmpty()) {
                        item(key = "search-songs-title", contentType = "section-title") {
                            FolioSectionTitle(stringResource(R.string.browse_search_songs))
                        }
                        itemsIndexed(
                            sections.songs,
                            key = { index, item -> "search-song-${item.id}#$index" },
                            contentType = { _, _ -> "song-row" },
                        ) { index, track ->
                            FolioSongRow(
                                track = track,
                                index = index,
                                showCover = true,
                                showArtist = true,
                                isCurrent = track.matchesPlaybackQueueEntry(currentTrack),
                                isPlaying = isPlaying,
                                onClick = { onOpenItem(track, sections.songs) },
                            )
                        }
                    }
                    searchCards(
                        R.string.browse_search_albums, sections.albums, cardRows.getValue(R.string.browse_search_albums),
                        listCols, tierSpec.lstGap, onOpenItem,
                    )
                    searchCards(
                        R.string.browse_search_artists, sections.artists, cardRows.getValue(R.string.browse_search_artists),
                        listCols, tierSpec.lstGap, onOpenItem,
                    )
                    searchCards(
                        R.string.browse_search_playlists, sections.playlists, cardRows.getValue(R.string.browse_search_playlists),
                        listCols, tierSpec.lstGap, onOpenItem,
                    )
                }
            }
        }
    }
}

@Composable
private fun FolioSearchSuggestionsPanel(
    suggestions: CatalogSearchSuggestions,
    currentTrack: Track,
    isPlaying: Boolean,
    onSubmit: (String) -> Unit,
    onOpen: (Track) -> Unit,
) {
    val palette = LocalFolioPalette.current
    val fontFamily = LocalFolioFontFamily.current
    Column(
        Modifier.fillMaxWidth()
            .padding(bottom = 12.dp)
            .folioPlayerGlass(isLight = palette.isLight, shape = RoundedCornerShape(20.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        suggestions.terms.forEach { term ->
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(FolioRadii.Row))
                    .folioBrowsePressable { onSubmit(term.searchTerm) }
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = palette.fg2, modifier = Modifier.size(20.dp))
                Text(
                    text = term.displayTerm,
                    color = palette.fg,
                    fontFamily = fontFamily,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        suggestions.topResults.forEachIndexed { index, track ->
            if (track.catalogKind == CatalogKind.Song) {
                FolioSongRow(
                    track = track,
                    index = index,
                    showCover = true,
                    showArtist = true,
                    isCurrent = track.matchesPlaybackQueueEntry(currentTrack),
                    isPlaying = isPlaying,
                    onClick = { onOpen(track) },
                )
            } else {
                FolioListRowCard(track, onClick = { onOpen(track) }, modifier = Modifier.padding(horizontal = 8.dp))
            }
        }
    }
}

private fun LazyListScope.searchCards(
    title: Int,
    cards: List<Track>,
    rows: List<List<Track>>,
    columns: Int,
    gap: Dp,
    onOpenItem: (Track, List<Track>) -> Unit,
) {
    if (rows.isEmpty()) return
    item(key = "search-title-$title", contentType = "section-title") {
        FolioSectionTitle(stringResource(title))
    }
    itemsIndexed(
        rows,
        key = { index, _ -> "search-cards-$title-$index" },
        contentType = { _, _ -> "list-card-row" },
    ) { _, row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            row.forEach { card ->
                FolioListRowCard(card, onClick = { onOpenItem(card, cards) }, modifier = Modifier.weight(1f))
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
