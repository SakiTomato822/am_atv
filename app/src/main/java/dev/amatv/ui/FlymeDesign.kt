package dev.amatv.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.i18n.destinationLabel
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track
import java.text.Collator
import java.util.Locale

/*
 * Flyme Auto surfaces in the Apple TV / Cider direction. Sizes are in the car
 * design space set up by CarLayout (1280×645 dp at the 1920×968 head unit).
 */

internal val FlymeLiveRed = Color(0xFFFA233B)

/**
 * The car UI follows the system day/night setting. [ink] is the foreground (text, icons, the
 * translucent fills made from it); [page] and [pageTop] are the page background, [scrim] the veil
 * over the artwork-colored backdrop. The TV build never provides a palette and stays dark.
 */
internal data class FlymePalette(
    val light: Boolean,
    val ink: Color,
    val page: Color,
    val pageTop: Color,
    val scrim: Color,
)

internal val DarkFlymePalette = FlymePalette(
    light = false, ink = Color.White, page = Color(0xFF0B0A0E), pageTop = Color(0xFF15111D), scrim = Color(0xFF0B0A0E),
)
internal val LightFlymePalette = FlymePalette(
    light = true, ink = Color(0xFF1C1C1E), page = Color(0xFFF2F2F5), pageTop = Color(0xFFFFFFFF), scrim = Color(0xFFF7F7F9),
)
internal val LocalFlymePalette = staticCompositionLocalOf { DarkFlymePalette }

/** Foreground color of the current palette: white in the dark theme, near-black in light mode. */
internal val FlymeInk: Color
    @Composable @ReadOnlyComposable get() = LocalFlymePalette.current.ink

/** Content on a surface filled with [FlymeInk] (a selected pill, the play button): its opposite. */
internal val FlymeOnInk: Color
    @Composable @ReadOnlyComposable get() = if (LocalFlymePalette.current.light) Color.White else Color(0xFF17151C)

/** Text without font padding, its line box trimmed to the glyphs: for tight two-line stacks. */
private val CompactLine = TextStyle(
    platformStyle = PlatformTextStyle(includeFontPadding = false),
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
)


/** The one back control on every Flyme page: a translucent circle with an arrow. */
@Composable
internal fun FlymeBackButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val keyFocused = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f,
        spring(dampingRatio = 0.7f, stiffness = 600f), label = "flyme-back-scale")
    Box(
        modifier
            .size(44.dp)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(FlymeInk.copy(alpha = if (keyFocused || pressed) 0.24f else 0.12f))
            .then(if (keyFocused) Modifier.border(2.dp, FlymeInk, CircleShape) else Modifier)
            .onFocusChanged { focused = it.isFocused }
            .clickable(enabled = LocalFocusTargetsEnabled.current, interactionSource = interaction,
                indication = null, role = Role.Button, onClick = onClick)
            .automationId("flyme-back"),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.browse_back), tint = FlymeInk,
            modifier = Modifier.size(24.dp))
    }
}

/** Apple Music's E badge for explicit tracks, sized to the text beside it. */
@Composable
internal fun ExplicitBadge(size: Dp = 14.dp, color: Color = FlymeInk.copy(alpha = 0.55f)) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.2f)).background(color)
            .semantics { contentDescription = "Explicit" },
        contentAlignment = Alignment.Center,
    ) {
        Text("E", fontSize = (size.value * 0.72f).sp, lineHeight = (size.value * 0.72f).sp,
            fontWeight = FontWeight.Bold, color = FlymeOnInk, style = CompactLine)
    }
}

/** Apple-style marquee: waits, scrolls once through, pauses, repeats. Text that fits stays still. */
internal fun Modifier.flymeMarquee(): Modifier =
    basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2_500, repeatDelayMillis = 3_000)

/** One-line title that keeps the E badge visible when the title is truncated. */
@Composable
internal fun ExplicitTitle(
    text: String,
    explicit: Boolean,
    style: TextStyle,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 14.dp,
    /** Scroll a title that does not fit, instead of cutting it off. */
    marquee: Boolean = false,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = style, maxLines = 1,
            overflow = if (marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
                .then(if (marquee) Modifier.flymeMarquee() else Modifier))
        if (explicit) {
            Spacer(Modifier.width(badgeSize * 0.4f))
            ExplicitBadge(badgeSize)
        }
    }
}

/** Shrinks a fixed car layout to fit smaller windows (phones); never enlarges it. */
@Composable
internal fun FitDesign(
    modifier: Modifier = Modifier,
    designWidth: Dp = 1280.dp,
    designHeight: Dp = 645.dp,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    BoxWithConstraints(modifier) {
        val scale = minOf(maxWidth / designWidth, maxHeight / designHeight, 1f)
        if (scale >= 0.999f) content() else CompositionLocalProvider(
            LocalDensity provides Density(density.density * scale, density.fontScale),
            content = content,
        )
    }
}

/** A plain white glyph, the way Apple TV draws transport controls: no container until pressed. */
@Composable
internal fun FlymeGlyphButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    iconSize: Dp = 28.dp,
    enabled: Boolean = true,
    selected: Boolean = false,
    focused: Boolean = false,
    tint: Color = FlymeInk,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val keyFocused = focused && LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.88f else 1f,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 600f),
        label = "flyme-glyph-scale",
    )
    val background by animateColorAsState(
        targetValue = when {
            selected -> FlymeInk.copy(alpha = 0.16f)
            keyFocused -> FlymeInk.copy(alpha = 0.22f)
            pressed && enabled -> FlymeInk.copy(alpha = 0.12f)
            else -> Color.Transparent
        },
        animationSpec = tween(160),
        label = "flyme-glyph-background",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(CircleShape)
            .background(background)
            .then(if (keyFocused) Modifier.border(2.dp, FlymeInk, CircleShape) else Modifier)
            .clickable(
                enabled = enabled && LocalFocusTargetsEnabled.current,
                interactionSource = interaction,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics {
                this.contentDescription = contentDescription
                if (selected) this.selected = true
            },
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = icon, animationSpec = tween(160), label = "flyme-glyph-icon") { shown ->
            Icon(
                imageVector = shown,
                contentDescription = null,
                tint = when {
                    !enabled -> tint.copy(alpha = 0.3f)
                    selected -> FlymeInk
                    else -> tint
                },
                modifier = Modifier.size(iconSize),
            )
        }
    }
}

/** Thin capsule scrubber; thickens while dragged. Tap or drag to seek. */
@Composable
internal fun FlymeSeekBar(
    positionState: State<Long>,
    durationMs: Long,
    enabled: Boolean,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier,
    focused: Boolean = false,
    scrubPositionMs: Long? = null,
) {
    val duration = durationMs.coerceAtLeast(1L)
    var dragFraction by remember(duration) { mutableStateOf<Float?>(null) }
    val latestSeek by rememberUpdatedState(onSeekTo)
    val thickness by animateDpAsState(
        targetValue = if (dragFraction != null || focused || scrubPositionMs != null) 7.dp else 5.dp,
        animationSpec = tween(180),
        label = "flyme-seek-thickness",
    )
    val shownSecond by remember(positionState, duration, scrubPositionMs) {
        derivedStateOf {
            val fraction = dragFraction
            (if (fraction != null) (fraction * duration).toLong() else scrubPositionMs ?: positionState.value) / 1_000L
        }
    }
    Column(modifier.fillMaxWidth().automationId("player-touch-progress")) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(enabled, duration) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        },
                        onDragEnd = {
                            dragFraction?.let { latestSeek((it * duration).toLong()) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    )
                }
                .pointerInput(enabled, duration) {
                    if (!enabled) return@pointerInput
                    detectTapGestures { offset ->
                        latestSeek(((offset.x / size.width).coerceIn(0f, 1f) * duration).toLong())
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val thumbRadius by animateDpAsState(
                targetValue = if (dragFraction != null) 9.dp else 6.dp, animationSpec = tween(160),
                label = "flyme-seek-thumb",
            )
            val ink = FlymeInk
            Canvas(Modifier.fillMaxWidth().height(thickness)) {
                val fraction = dragFraction
                    ?: ((scrubPositionMs ?: positionState.value).toFloat() / duration).coerceIn(0f, 1f)
                val corner = CornerRadius(size.height / 2f, size.height / 2f)
                drawRoundRect(ink.copy(alpha = 0.22f), cornerRadius = corner)
                if (fraction > 0f) {
                    drawRoundRect(
                        ink.copy(alpha = 0.9f),
                        size = Size(size.width * fraction, size.height),
                        cornerRadius = corner,
                    )
                }
                if (enabled) {
                    val r = thumbRadius.toPx()
                    drawCircle(ink, radius = r,
                        center = Offset((size.width * fraction).coerceIn(r, size.width - r), size.height / 2f))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shownMs = shownSecond * 1_000L
            Text(flymeTime(shownMs), fontSize = 11.sp, fontWeight = FontWeight.Medium,
                color = FlymeInk.copy(alpha = 0.55f), style = TabularNumbers())
            Text("−${flymeTime((durationMs - shownMs).coerceAtLeast(0L))}", fontSize = 11.sp,
                fontWeight = FontWeight.Medium, color = FlymeInk.copy(alpha = 0.55f), style = TabularNumbers())
        }
    }
}

@Composable
private fun TabularNumbers(): TextStyle = LocalTextStyle.current.merge(TextStyle(fontFeatureSettings = "tnum"))

internal fun flymeTime(ms: Long): String {
    val total = (ms / 1_000L).coerceAtLeast(0L)
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

/** One pill in a segmented group; the selected one is white with dark text. */
@Composable
private fun FlymePill(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    contentDescription: String? = null,
    content: @Composable (Color) -> Unit,
) {
    // Inside a group the capsule slides between segments; on its own the pill fills itself.
    val sliding = LocalSlidingPill.current != null
    val ink by animateColorAsState(if (selected) FlymeOnInk else FlymeInk.copy(alpha = 0.74f),
        spring(stiffness = 420f), label = "flyme-pill-ink")
    FocusSurface(
        onClick = onClick,
        modifier = modifier
            .height(if (small) 27.dp else 31.dp)
            .slidingPillSegment(selected)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .semantics { this.selected = selected },
        cornerRadius = 16.dp,
        focusedScale = 1.03f,
        padding = PaddingValues(horizontal = if (small) 11.dp else 15.dp),
        background = if (selected && !sliding) FlymeInk else Color.Transparent,
        focusedBackground = when {
            selected && sliding -> Color.Transparent
            selected -> FlymeInk
            else -> FlymeInk.copy(alpha = 0.16f)
        },
    ) {
        Box(Modifier.align(Alignment.Center)) {
            content(ink)
        }
    }
}

@Composable
private fun FlymePillGroup(content: @Composable RowScope.() -> Unit) {
    val pill = rememberSlidingPill()
    Row(
        Modifier.clip(RoundedCornerShape(19.dp)).background(FlymeInk.copy(alpha = 0.08f)).padding(3.dp)
            .slidingPillBackground(pill, FlymeInk),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProvideSlidingPill(pill) { content() }
    }
}

internal enum class LibrarySort(val label: Int, val descendingFirst: Boolean) {
    Default(R.string.library_sort_default, false),
    DateAdded(R.string.library_sort_date_added, true),
    Name(R.string.library_sort_name, false),
    Artist(R.string.library_sort_artist, false),
}

internal fun LibraryCategory.sorts(): List<LibrarySort> = when (this) {
    LibraryCategory.Albums, LibraryCategory.Songs ->
        listOf(LibrarySort.DateAdded, LibrarySort.Name, LibrarySort.Artist)
    else -> listOf(LibrarySort.Default, LibrarySort.Name)
}

/** The Apple Music API has no library sort parameter: any order but its own needs every page. */
internal fun sortLibrary(items: List<Track>, sort: LibrarySort, descending: Boolean, collator: Collator): List<Track> {
    val sorted = when (sort) {
        LibrarySort.Default -> items
        LibrarySort.DateAdded -> items.sortedBy { it.dateAddedMs ?: Long.MIN_VALUE }
        LibrarySort.Name -> items.sortedWith(compareBy(collator) { it.title })
        LibrarySort.Artist -> items.sortedWith(
            compareBy<Track, String>(collator) { it.artist }.thenBy(collator) { it.title },
        )
    }
    return if (descending) sorted.asReversed() else sorted
}

/**
 * Library as one page: category and sort controls above a vertically scrolling
 * grid (or list), instead of a single horizontal shelf per category.
 * Tapping the selected sort again reverses it.
 */
@Composable
internal fun FlymeLibrary(
    shelves: List<ContentShelf>,
    loading: Boolean,
    pagination: Map<String, LibraryShelfPagination>,
    onLoadMore: (String) -> Unit,
    onOpenTrack: (Track, List<Track>) -> Unit,
    firstFocus: FocusRequester?,
    navigationFocus: FocusRequester?,
    fallback: @Composable (LibraryCategory, FocusRequester) -> Unit,
) {
    var category by remember { mutableStateOf(LibraryCategory.Playlists) }
    // Each category keeps its own order; albums and songs open newest first, like Apple Music.
    val sortState = remember { mutableStateMapOf<LibraryCategory, Pair<LibrarySort, Boolean>>() }
    var gridView by remember { mutableStateOf(true) }
    val contentFocus = remember { FocusRequester() }
    val sorts = category.sorts()
    val (effectiveSort, descending) = sortState[category] ?: sorts.first().let { it to it.descendingFirst }
    val supportsViewToggle = category == LibraryCategory.Albums || category == LibraryCategory.Playlists
    val showList = category == LibraryCategory.Songs || (supportsViewToggle && !gridView)

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 13.dp, end = 13.dp, top = 6.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(destinationLabel(Destination.Library), fontSize = 31.sp, fontWeight = FontWeight.Bold,
                    color = FlymeInk, lineHeight = 34.sp)
                FlymePillGroup {
                    LibraryCategory.entries.forEach { entry ->
                        FlymePill(
                            selected = entry == category,
                            onClick = { category = entry },
                            modifier = Modifier
                                .automationId("library-category-${entry.name.lowercase()}")
                                .then(if (entry == LibraryCategory.Playlists && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
                                .focusProperties {
                                    down = contentFocus
                                    navigationFocus?.let { up = it }
                                },
                        ) { color ->
                            Text(stringResource(entry.flymeTitle()), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.library_sort), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    color = FlymeInk.copy(alpha = 0.5f))
                FlymePillGroup {
                    sorts.forEach { option ->
                        val selected = option == effectiveSort
                        FlymePill(
                            selected = selected,
                            onClick = {
                                sortState[category] = option to (if (selected) !descending else option.descendingFirst)
                            },
                            small = true,
                            modifier = Modifier.automationId("library-sort-${option.name.lowercase()}")
                                .focusProperties { down = contentFocus },
                        ) { color ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(stringResource(option.label), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = color)
                                if (selected) Icon(
                                    if (descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward,
                                    contentDescription = stringResource(
                                        if (descending) R.string.library_sort_descending else R.string.library_sort_ascending,
                                    ),
                                    tint = color, modifier = Modifier.padding(start = 3.dp).size(12.dp),
                                )
                            }
                        }
                    }
                }
                if (supportsViewToggle) {
                    FlymePillGroup {
                        FlymePill(selected = gridView, onClick = { gridView = true }, small = true,
                            contentDescription = stringResource(R.string.library_view_grid),
                            modifier = Modifier.focusProperties { down = contentFocus }) { color ->
                            Icon(Icons.Rounded.GridView, null, tint = color, modifier = Modifier.size(16.dp))
                        }
                        FlymePill(selected = !gridView, onClick = { gridView = false }, small = true,
                            contentDescription = stringResource(R.string.library_view_list),
                            modifier = Modifier.focusProperties { down = contentFocus }) { color ->
                            Icon(Icons.AutoMirrored.Rounded.ViewList, null, tint = color, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }

        val shelf = shelves.firstOrNull { it.id == category.shelfId }
        val page = pagination[category.shelfId]
        when {
            shelf == null && loading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                AppleLoadingIndicator()
            }
            shelf == null -> Box(Modifier.weight(1f).fillMaxWidth()) { fallback(category, contentFocus) }
            shelf.items.isEmpty() -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.library_empty), fontSize = 16.sp, color = FlymeInk.copy(alpha = 0.6f))
            }
            else -> {
                val collator = remember { Collator.getInstance(Locale.getDefault()) }
                val items = remember(shelf.items, effectiveSort, descending) {
                    sortLibrary(shelf.items, effectiveSort, descending, collator)
                }
                // Voice: "第三个" is the third item as shown, in this tab and order.
                val voiceSink = LocalVoiceListSink.current
                LaunchedEffect(items) { voiceSink(VoiceList(items) { onOpenTrack(it, items) }) }
                androidx.compose.runtime.DisposableEffect(voiceSink) { onDispose { voiceSink(null) } }
                val requestMore = {
                    if (page != null && page.hasMore && !page.loading && !page.failed) onLoadMore(category.shelfId)
                }
                // A sorted view is only right once every page is here; keep fetching in the background.
                val needsAllPages = effectiveSort != LibrarySort.Default || descending
                LaunchedEffect(category, needsAllPages, page?.hasMore, page?.loading, page?.failed, shelf.items.size) {
                    if (needsAllPages) requestMore()
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (showList) {
                        FlymeLibraryList(items, category == LibraryCategory.Songs, page, contentFocus,
                            onOpen = { onOpenTrack(it, items) }, onNearEnd = requestMore,
                            onRetry = { onLoadMore(category.shelfId) })
                    } else {
                        FlymeLibraryGrid(items, circular = category == LibraryCategory.Artists, page, contentFocus,
                            onOpen = { onOpenTrack(it, items) }, onNearEnd = requestMore,
                            onRetry = { onLoadMore(category.shelfId) })
                    }
                }
            }
        }
    }
}

private fun LibraryCategory.flymeTitle(): Int = when (this) {
    LibraryCategory.Playlists -> R.string.library_tab_playlists
    LibraryCategory.Artists -> R.string.app_library_artists
    LibraryCategory.Albums -> R.string.library_tab_albums
    LibraryCategory.Songs -> R.string.library_tab_songs
}

@Composable
private fun FlymeLibraryGrid(
    items: List<Track>,
    circular: Boolean,
    page: LibraryShelfPagination?,
    contentFocus: FocusRequester,
    onOpen: (Track) -> Unit,
    onNearEnd: () -> Unit,
    onRetry: () -> Unit,
) {
    val state = rememberLazyGridState()
    val latestNearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(state, items.size) {
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= items.size - 8 }
            .collect { if (it) latestNearEnd() }
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(132.dp),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 13.dp, end = 13.dp, bottom = 40.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        itemsIndexed(items, key = { index, item -> "${item.id}#$index" }) { index, item ->
            FocusSurface(
                onClick = { onOpen(item) },
                modifier = Modifier.then(if (index == 0) Modifier.focusRequester(contentFocus) else Modifier),
                cornerRadius = 10.dp,
                focusedScale = 1.04f,
                background = Color.Transparent,
                focusedBackground = Color.Transparent,
            ) {
                Column(horizontalAlignment = if (circular) Alignment.CenterHorizontally else Alignment.Start) {
                    MotionArtwork(
                        track = item,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                        animate = false,
                        cornerRadius = if (circular) 200.dp else 9.dp,
                    )
                    Text(item.title, fontSize = 12.7.sp, fontWeight = FontWeight.SemiBold, color = FlymeInk,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        textAlign = if (circular) TextAlign.Center else TextAlign.Start,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    if (!circular && item.artist.isNotBlank()) {
                        Text(item.artist, fontSize = 10.7.sp, fontWeight = FontWeight.Medium,
                            color = FlymeInk.copy(alpha = 0.55f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        if (page != null && (page.loading || page.failed)) {
            item(span = { GridItemSpan(maxLineSpan) }) { FlymeLoadMoreFooter(page, onRetry) }
        }
    }
}

@Composable
private fun FlymeLibraryList(
    items: List<Track>,
    songs: Boolean,
    page: LibraryShelfPagination?,
    contentFocus: FocusRequester,
    onOpen: (Track) -> Unit,
    onNearEnd: () -> Unit,
    onRetry: () -> Unit,
) {
    val state = rememberLazyListState()
    val latestNearEnd by rememberUpdatedState(onNearEnd)
    LaunchedEffect(state, items.size) {
        snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= items.size - 8 }
            .collect { if (it) latestNearEnd() }
    }
    val dim = FlymeInk.copy(alpha = 0.6f)
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 13.dp, end = 13.dp, bottom = 40.dp),
    ) {
        item(key = "header") {
            Row(
                Modifier.fillMaxWidth().height(30.dp).padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val header = FlymeInk.copy(alpha = 0.42f)
                Spacer(Modifier.width(37.dp))
                Text(stringResource(R.string.library_column_title), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = header, modifier = Modifier.weight(1.4f))
                Text(stringResource(R.string.library_column_artist), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                    color = header, modifier = Modifier.weight(1f))
                if (songs) {
                    Text(stringResource(R.string.library_column_album), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = header, modifier = Modifier.weight(1f))
                    Text(stringResource(R.string.library_column_time), fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        color = header, textAlign = TextAlign.End, modifier = Modifier.width(44.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(FlymeInk.copy(alpha = 0.08f)))
        }
        itemsIndexed(items, key = { index, item -> "${item.id}#$index" }) { index, item ->
            FocusSurface(
                onClick = { onOpen(item) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
                    .then(if (index == 0) Modifier.focusRequester(contentFocus) else Modifier),
                cornerRadius = 8.dp,
                focusedScale = 1.01f,
                padding = PaddingValues(horizontal = 11.dp),
                background = Color.Transparent,
                focusedBackground = FlymeInk.copy(alpha = 0.08f),
            ) {
                Row(
                    Modifier.fillMaxSize(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    MotionArtwork(track = item, modifier = Modifier.size(37.dp), animate = false, cornerRadius = 5.dp)
                    ExplicitTitle(item.title, item.isExplicit,
                        TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = FlymeInk),
                        modifier = Modifier.weight(1.4f), badgeSize = 13.dp)
                    Text(item.artist, fontSize = 12.7.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f))
                    if (songs) {
                        Text(item.album, fontSize = 12.7.sp, color = dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f))
                        Text(if (item.durationMs > 1_000L) flymeTime(item.durationMs) else "", fontSize = 12.sp,
                            color = FlymeInk.copy(alpha = 0.5f), textAlign = TextAlign.End,
                            style = TabularNumbers(), modifier = Modifier.width(44.dp))
                    }
                }
            }
        }
        if (page != null && (page.loading || page.failed)) {
            item(key = "footer") { FlymeLoadMoreFooter(page, onRetry) }
        }
    }
}

@Composable
private fun FlymeLoadMoreFooter(page: LibraryShelfPagination, onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 18.dp), contentAlignment = Alignment.Center) {
        if (page.loading) AppleLoadingIndicator(Modifier.size(22.dp))
        else FocusSurface(onClick = onRetry, cornerRadius = 16.dp, padding = PaddingValues(horizontal = 18.dp),
            modifier = Modifier.height(36.dp)) {
            Text(stringResource(R.string.browse_load_more_retry), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = FlymeInk, modifier = Modifier.align(Alignment.Center))
        }
    }
}

/**
 * Browse header: account on the left, centred section tabs, and a mini player
 * on the right that opens Now Playing and toggles playback in place.
 */
@Composable
internal fun FlymeTopBar(
    selected: Destination,
    hasCurrentTrack: Boolean,
    currentTrack: Track,
    nowPlayingSubtitle: String?,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onDestination: (Destination) -> Unit,
    focusRequesters: Map<Destination, FocusRequester>,
    downFocus: () -> FocusRequester,
    modifier: Modifier = Modifier,
    /** The mini player's centre in window pixels, so Now Playing can grow out of it. */
    onMiniPlayerPlaced: (Offset) -> Unit = {},
    /** Only the mini player, for pages that cover the top bar. */
    capsuleOnly: Boolean = false,
    /** Shows a mic button after the section tabs when set. */
    onVoice: (() -> Unit)? = null,
    voiceActive: Boolean = false,
) {
    val sideWidth = 267.dp
    val accountLabel = destinationLabel(Destination.Account)
    Row(
        modifier.fillMaxWidth().padding(start = 40.dp, end = 40.dp, top = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (capsuleOnly) Spacer(Modifier.weight(1f)) else Box(Modifier.width(sideWidth)) {
            FocusSurface(
                onClick = { onDestination(Destination.Account) },
                modifier = Modifier.size(37.dp)
                    .automationId("nav-account")
                    .focusRequester(focusRequesters.getValue(Destination.Account))
                    .focusProperties { down = downFocus() }
                    .semantics { contentDescription = accountLabel },
                cornerRadius = 19.dp,
                focusedScale = 1.06f,
                background = if (selected == Destination.Account) FlymeInk else FlymeInk.copy(alpha = 0.12f),
                focusedBackground = FlymeInk.copy(alpha = 0.24f),
            ) {
                Icon(Icons.Rounded.Person, null, modifier = Modifier.align(Alignment.Center).size(18.dp),
                    tint = if (selected == Destination.Account) FlymeOnInk else FlymeInk)
            }
        }
        if (!capsuleOnly) Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val tabPill = rememberSlidingPill()
            Row(
                Modifier.clip(RoundedCornerShape(22.dp)).background(FlymeInk.copy(alpha = 0.09f)).padding(4.dp)
                    .slidingPillBackground(tabPill, FlymeInk),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { ProvideSlidingPill(tabPill) {
                listOf(Destination.Home, Destination.Browse, Destination.Library, Destination.Search).forEach { destination ->
                    val isSelected = destination == selected
                    val tint by animateColorAsState(if (isSelected) FlymeOnInk else FlymeInk.copy(alpha = 0.78f),
                        spring(stiffness = 420f), label = "flyme-tab-ink")
                    FocusSurface(
                        onClick = { onDestination(destination) },
                        modifier = Modifier.height(35.dp)
                            .slidingPillSegment(isSelected)
                            .automationId("nav-${destination.name.lowercase()}")
                            .focusRequester(focusRequesters.getValue(destination))
                            .focusProperties { down = downFocus() }
                            .semantics { this.selected = isSelected },
                        cornerRadius = 18.dp,
                        focusedScale = 1.03f,
                        padding = PaddingValues(horizontal = if (destination == Destination.Search) 9.dp else 17.dp),
                        background = Color.Transparent,
                        focusedBackground = if (isSelected) Color.Transparent else FlymeInk.copy(alpha = 0.14f),
                    ) {
                        Box(Modifier.align(Alignment.Center)) {
                            if (destination == Destination.Search) {
                                Icon(Icons.Rounded.Search, destinationLabel(destination), tint = tint, modifier = Modifier.size(18.dp))
                            } else {
                                Text(destinationLabel(destination), fontSize = 14.sp,
                                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium, color = tint)
                            }
                        }
                    }
                }
            } }
            if (onVoice != null) FlymeVoiceButton(active = voiceActive, onClick = onVoice)
          }
        }
        Box(Modifier.width(sideWidth), contentAlignment = Alignment.CenterEnd) {
            if (hasCurrentTrack) {
                // A floating surface of its own: pages scroll under it (artist and album pages), so a
                // see-through tint let the rows beneath show through the title. Nearly opaque, with a
                // soft shadow and hairline edge, it reads on any page without a costly live blur.
                val palette = LocalFlymePalette.current
                val capsuleShape = RoundedCornerShape(23.dp)
                Row(
                    Modifier.fillMaxWidth().height(46.dp)
                        .onGloballyPositioned { onMiniPlayerPlaced(it.boundsInWindow().center) }
                        .graphicsLayer { shadowElevation = 14f; shape = capsuleShape; clip = true }
                        .background(palette.pageTop.copy(alpha = 0.94f))
                        .background(FlymeInk.copy(alpha = if (selected == Destination.NowPlaying) 0.14f else 0.06f))
                        .border(1.dp, FlymeInk.copy(alpha = 0.1f), capsuleShape)
                        .padding(start = 6.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FocusSurface(
                        onClick = { onDestination(Destination.NowPlaying) },
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(vertical = 4.dp)
                            .automationId("nav-nowplaying")
                            .focusRequester(focusRequesters.getValue(Destination.NowPlaying))
                            .focusProperties { down = downFocus() },
                        cornerRadius = 18.dp,
                        focusedScale = 1.02f,
                        background = Color.Transparent,
                        focusedBackground = FlymeInk.copy(alpha = 0.1f),
                    ) {
                        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            // A new song pops its cover in with a small spring (layer scale only).
                            val pop = remember { androidx.compose.animation.core.Animatable(1f) }
                            LaunchedEffect(currentTrack.id) {
                                if (pop.value == 1f) pop.snapTo(0.82f)
                                pop.animateTo(1f, spring(dampingRatio = 0.5f, stiffness = 420f))
                            }
                            MotionArtwork(track = currentTrack, modifier = Modifier.size(34.dp)
                                .graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                                animate = false, cornerRadius = 7.dp)
                            // CJK fonts carry tall ascents: trim line boxes so two lines fit the capsule, centered.
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(currentTrack.title, style = CompactLine, fontSize = 12.sp, lineHeight = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = FlymeInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(nowPlayingSubtitle ?: currentTrack.artist, style = CompactLine, fontSize = 10.sp,
                                    lineHeight = 12.sp,
                                    color = FlymeInk.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    FlymeGlyphButton(
                        icon = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                        onClick = onTogglePlayback,
                        size = 34.dp,
                        iconSize = 20.dp,
                    )
                }
            }
        }
    }
}

/**
 * Lyric alpha for the current appearance. Apple's white-on-dark alphas leave dark ink on a light
 * page too faint (unsung lines at 18% read as blank in daylight), so the light page lifts each
 * level part of the way towards opaque.
 */
@Composable
@ReadOnlyComposable
internal fun flymeLyricAlpha(alpha: Float): Float =
    if (LocalFlymePalette.current.light) alpha + (1f - alpha) * LIGHT_LYRIC_LIFT else alpha

private const val LIGHT_LYRIC_LIFT = 0.18f
