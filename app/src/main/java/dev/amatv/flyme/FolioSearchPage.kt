package dev.amatv.flyme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.model.Track
import dev.amatv.model.matchesPlaybackQueueEntry
import dev.amatv.ui.pushSearchHistory
import org.json.JSONArray

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FolioSearchPage(
    currentTrack: Track,
    isPlaying: Boolean,
    bottomPadding: Dp,
    search: suspend (String) -> List<Track>,
    onOpenItem: (Track, List<Track>) -> Unit,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current
    val session = folioSearchSession
    val query = session.query
    val submission = session.submission
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

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = tierSpec.px,
                end = tierSpec.pr,
                top = 4.dp,
                bottom = bottomPadding,
            ),
        ) {
            item(key = "search-bar") {
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
                            onValueChange = session::edit,
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
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (query.isNotEmpty()) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.browse_search_clear),
                            tint = palette.fg2,
                            modifier = Modifier
                                .size(20.dp)
                                .clickable { session.edit("") },
                        )
                    }
                }
            }

            when {
                submission == null -> {
                    if (history.isNotEmpty()) {
                        item(key = "search-history") {
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
                    item(key = "search-loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 36.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = palette.fg2,
                                strokeWidth = 2.dp,
                            )
                        }
                    }
                }
                submission.results.isEmpty() -> {
                    item(key = "search-empty") {
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
                    val sections = folioSearchSections(submission.results)
                    searchCards(R.string.browse_search_top, sections.best, listCols, tierSpec.lstGap, onOpenItem)
                    if (sections.songs.isNotEmpty()) {
                        item(key = "search-songs-title") {
                            FolioSectionTitle(stringResource(R.string.browse_search_songs))
                        }
                        itemsIndexed(sections.songs, key = { index, item -> "search-song-${item.id}#$index" }) { index, track ->
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
                    searchCards(R.string.browse_search_albums, sections.albums, listCols, tierSpec.lstGap, onOpenItem)
                    searchCards(R.string.browse_search_artists, sections.artists, listCols, tierSpec.lstGap, onOpenItem)
                    searchCards(R.string.browse_search_playlists, sections.playlists, listCols, tierSpec.lstGap, onOpenItem)
                }
            }
        }
    }
}

private fun LazyListScope.searchCards(
    title: Int,
    cards: List<Track>,
    columns: Int,
    gap: Dp,
    onOpenItem: (Track, List<Track>) -> Unit,
) {
    if (cards.isEmpty()) return
    item(key = "search-title-$title") {
        FolioSectionTitle(stringResource(title))
    }
    itemsIndexed(cards.chunked(columns), key = { index, _ -> "search-cards-$title-$index" }) { _, row ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
            row.forEach { card ->
                FolioListRowCard(card, onClick = { onOpenItem(card, cards) }, modifier = Modifier.weight(1f))
            }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}
