package dev.amatv.ui

import android.view.KeyEvent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.tween
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import dev.amatv.R
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.GoogleTvPairing
import dev.amatv.model.CatalogKind
import dev.amatv.model.ContentShelf
import dev.amatv.model.Destination
import dev.amatv.model.Track
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    destination: Destination,
    currentTrack: Track,
    tracks: List<Track> = emptyList(),
    searchLoading: Boolean = false,
    recentTracks: List<Track> = emptyList(),
    homeShelves: List<ContentShelf> = emptyList(),
    homeLoading: Boolean = false,
    connectionState: MusicConnectionState = MusicConnectionState.Demo,
    setupMessage: String? = null,
    onSignIn: () -> Unit = {},
    ytmSignedIn: Boolean = false,
    ytmPairing: GoogleTvPairing? = null,
    ytmPairingConfigured: Boolean = false,
    onYtmSignIn: () -> Unit = {},
    onYtmSignOut: () -> Unit = {},
    onSignOut: () -> Unit = {},
    onRetry: () -> Unit = {},
    onSearch: (String) -> Unit = {},
    onOpenTrack: (Track, List<Track>) -> Unit = { _, _ -> },
    lyricOffsetMs: Long = 0L,
    onLyricOffsetChange: (Long) -> Unit = {},
    autoplaySource: dev.amatv.integration.AutoplaySource = dev.amatv.integration.AutoplaySource.AppleMusic,
    onAutoplaySourceChange: (dev.amatv.integration.AutoplaySource) -> Unit = {},
    lowPerformanceMode: Boolean = false,
    onLowPerformanceModeChange: (Boolean) -> Unit = {},
    firstContentFocusRequester: FocusRequester? = null,
    navigationFocusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    cacheSettingsContent: @Composable () -> Unit = {},
) {
    Column(modifier.fillMaxSize().automationId("browse-${destination.name.lowercase()}")) {
        when (destination) {
            Destination.Account -> {
                AccountScreen(
                    connectionState = connectionState,
                    setupMessage = setupMessage,
                    onSignIn = onSignIn,
                    ytmSignedIn = ytmSignedIn,
                    ytmPairing = ytmPairing,
                    ytmPairingConfigured = ytmPairingConfigured,
                    onYtmSignIn = onYtmSignIn,
                    onYtmSignOut = onYtmSignOut,
                    onSignOut = onSignOut,
                    onRetry = onRetry,
                    lyricOffsetMs = lyricOffsetMs,
                    onLyricOffsetChange = onLyricOffsetChange,
                    autoplaySource = autoplaySource,
                    onAutoplaySourceChange = onAutoplaySourceChange,
                    lowPerformanceMode = lowPerformanceMode,
                    onLowPerformanceModeChange = onLowPerformanceModeChange,
                    cacheSettingsContent = cacheSettingsContent,
                    firstContentFocusRequester = firstContentFocusRequester,
                    navigationFocusRequester = navigationFocusRequester,
                )
            }

            Destination.Library -> {
                if (connectionState != MusicConnectionState.SignedIn) {
                    AccountScreen(
                        connectionState = connectionState,
                        setupMessage = setupMessage,
                        onSignIn = onSignIn,
                        onSignOut = onSignOut,
                        onRetry = onRetry,
                        firstContentFocusRequester = firstContentFocusRequester,
                        navigationFocusRequester = navigationFocusRequester,
                        headingOverride = stringResource(R.string.browse_library_sign_in),
                    )
                } else {
                    HomeShelves(
                        shelves = homeShelves,
                        loading = homeLoading,
                        connectionState = connectionState,
                        setupMessage = setupMessage,
                        onSignIn = onSignIn,
                        onRetry = onRetry,
                        onOpenTrack = onOpenTrack,
                        firstContentFocusRequester = firstContentFocusRequester,
                        navigationFocusRequester = navigationFocusRequester,
                    )
                }
            }

            Destination.Search -> {
                SearchScreen(
                    tracks = tracks,
                    recentTracks = recentTracks,
                    loading = searchLoading,
                    setupMessage = setupMessage,
                    onSearch = onSearch,
                    onOpenTrack = onOpenTrack,
                    firstContentFocusRequester = firstContentFocusRequester,
                    navigationFocusRequester = navigationFocusRequester,
                )
            }

            else -> {
                HomeShelves(
                    shelves = homeShelves,
                    loading = homeLoading,
                    connectionState = connectionState,
                    setupMessage = setupMessage,
                    onSignIn = onSignIn,
                    onRetry = onRetry,
                    onOpenTrack = onOpenTrack,
                    firstContentFocusRequester = firstContentFocusRequester,
                    navigationFocusRequester = navigationFocusRequester,
                )
            }
        }
    }
}

@Composable
private fun AccountScreen(
    connectionState: MusicConnectionState,
    setupMessage: String?,
    onSignIn: () -> Unit,
    ytmSignedIn: Boolean = false,
    ytmPairing: GoogleTvPairing? = null,
    ytmPairingConfigured: Boolean = false,
    onYtmSignIn: () -> Unit = {},
    onYtmSignOut: () -> Unit = {},
    onSignOut: () -> Unit,
    onRetry: () -> Unit,
    lyricOffsetMs: Long = 0L,
    onLyricOffsetChange: (Long) -> Unit = {},
    autoplaySource: dev.amatv.integration.AutoplaySource = dev.amatv.integration.AutoplaySource.AppleMusic,
    onAutoplaySourceChange: (dev.amatv.integration.AutoplaySource) -> Unit = {},
    lowPerformanceMode: Boolean = false,
    onLowPerformanceModeChange: (Boolean) -> Unit = {},
    firstContentFocusRequester: FocusRequester?,
    navigationFocusRequester: FocusRequester?,
    headingOverride: String? = null,
    cacheSettingsContent: @Composable () -> Unit = {},
) {
    val connected = connectionState == MusicConnectionState.SignedIn
    val primaryFocus = firstContentFocusRequester ?: remember { FocusRequester() }
    val secondaryFocus = remember { FocusRequester() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = headingOverride
                ?: if (connected) stringResource(R.string.browse_apple_connected) else stringResource(R.string.browse_apple_sign_in),
            color = Color.White,
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
        )
        if (!setupMessage.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(
                    text = setupMessage,
                    color = Color.White.copy(alpha = 0.82f),
                    fontSize = 15.sp,
                    lineHeight = 22.sp,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (connectionState != MusicConnectionState.Demo) {
                FocusSurface(
                    onClick = if (connected) onRetry else onSignIn,
                    modifier = Modifier
                        .width(280.dp)
                        .height(54.dp)
                        .focusRequester(primaryFocus)
                        .focusProperties {
                            if (navigationFocusRequester != null) up = navigationFocusRequester
                            right = secondaryFocus
                        },
                    cornerRadius = 27.dp,
                    focusedScale = 1.03f,
                    background = Color.White.copy(alpha = 0.9f),
                    focusedBackground = Color.White,
                    padding = PaddingValues(horizontal = 20.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (connected) stringResource(R.string.browse_refresh_apple) else stringResource(R.string.browse_sign_in_tv),
                            color = Color(0xFF111713),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                FocusSurface(
                    onClick = if (connected) onSignOut else onRetry,
                    modifier = Modifier
                        .width(240.dp)
                        .height(54.dp)
                        .focusRequester(secondaryFocus)
                        .focusProperties {
                            if (navigationFocusRequester != null) up = navigationFocusRequester
                            left = primaryFocus
                        },
                    cornerRadius = 27.dp,
                    focusedScale = 1.03f,
                    background = Color.White.copy(alpha = 0.14f),
                    focusedBackground = Color.White.copy(alpha = 0.26f),
                    padding = PaddingValues(horizontal = 20.dp),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (connected) stringResource(R.string.browse_sign_out_tv) else stringResource(R.string.browse_retry_connection),
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        if (headingOverride == null) {
            if (ytmPairingConfigured) {
            Text(
                text = if (ytmSignedIn) stringResource(R.string.browse_ytm_connected) else stringResource(R.string.browse_ytm_recommendations),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    ytmPairing != null ->
                        stringResource(R.string.browse_pairing_instructions, ytmPairing.verificationUrl, ytmPairing.userCode)
                    ytmSignedIn -> stringResource(R.string.browse_personalized_enabled)
                    else -> stringResource(R.string.browse_pairing_hint)
                },
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
            )
            if (ytmPairingConfigured) {
                FocusSurface(
                    onClick = if (ytmSignedIn) onYtmSignOut else onYtmSignIn,
                    modifier = Modifier.width(280.dp).height(50.dp),
                    cornerRadius = 25.dp,
                    background = Color.White.copy(alpha = 0.14f),
                    focusedBackground = Color.White.copy(alpha = 0.27f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (ytmSignedIn) stringResource(R.string.browse_ytm_sign_out) else stringResource(R.string.browse_ytm_pair),
                            color = Color.White,
                            fontSize = 16.sp,
                        )
                    }
                }
            }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.browse_lyrics_sync),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.browse_lyrics_sync_hint),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FocusSurface(
                    onClick = { onLyricOffsetChange((lyricOffsetMs - 100L).coerceAtLeast(-3000L)) },
                    modifier = Modifier.width(96.dp).height(46.dp),
                    cornerRadius = 23.dp,
                    background = Color.White.copy(alpha = 0.14f),
                    focusedBackground = Color.White.copy(alpha = 0.28f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.browse_offset_value, -100), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                FocusSurface(
                    onClick = { onLyricOffsetChange((lyricOffsetMs - 50L).coerceAtLeast(-3000L)) },
                    modifier = Modifier.width(90.dp).height(46.dp),
                    cornerRadius = 23.dp,
                    background = Color.White.copy(alpha = 0.14f),
                    focusedBackground = Color.White.copy(alpha = 0.28f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.browse_offset_value, -50), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                Box(
                    modifier = Modifier
                        .height(46.dp)
                        .clip(RoundedCornerShape(23.dp))
                        .background(Color.White.copy(alpha = 0.22f))
                        .padding(horizontal = 20.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            lyricOffsetMs > 0L -> stringResource(R.string.browse_offset_value, lyricOffsetMs)
                            lyricOffsetMs < 0L -> stringResource(R.string.browse_offset_value, lyricOffsetMs)
                            else -> stringResource(R.string.browse_offset_default)
                        },
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                FocusSurface(
                    onClick = { onLyricOffsetChange((lyricOffsetMs + 50L).coerceAtMost(3000L)) },
                    modifier = Modifier.width(90.dp).height(46.dp),
                    cornerRadius = 23.dp,
                    background = Color.White.copy(alpha = 0.14f),
                    focusedBackground = Color.White.copy(alpha = 0.28f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.browse_offset_value, 50), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                FocusSurface(
                    onClick = { onLyricOffsetChange((lyricOffsetMs + 100L).coerceAtMost(3000L)) },
                    modifier = Modifier.width(96.dp).height(46.dp),
                    cornerRadius = 23.dp,
                    background = Color.White.copy(alpha = 0.14f),
                    focusedBackground = Color.White.copy(alpha = 0.28f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.browse_offset_value, 100), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    }
                }
                if (lyricOffsetMs != 0L) {
                    FocusSurface(
                        onClick = { onLyricOffsetChange(0L) },
                        modifier = Modifier.height(46.dp),
                        cornerRadius = 23.dp,
                        background = Color.White.copy(alpha = 0.10f),
                        focusedBackground = Color.White.copy(alpha = 0.25f),
                        padding = PaddingValues(horizontal = 16.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.browse_reset), color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.browse_autoplay_source),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isYtm = autoplaySource == dev.amatv.integration.AutoplaySource.YouTubeMusic
                FocusSurface(
                    onClick = { onAutoplaySourceChange(dev.amatv.integration.AutoplaySource.YouTubeMusic) },
                    modifier = Modifier.width(220.dp).height(46.dp),
                    cornerRadius = 23.dp,
                    background = if (isYtm) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.12f),
                    focusedBackground = Color.White.copy(alpha = 0.38f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isYtm) stringResource(R.string.browse_selected_source, stringResource(R.string.browse_ytm_name)) else stringResource(R.string.browse_ytm_name),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = if (isYtm) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
                val isAm = autoplaySource == dev.amatv.integration.AutoplaySource.AppleMusic
                FocusSurface(
                    onClick = { onAutoplaySourceChange(dev.amatv.integration.AutoplaySource.AppleMusic) },
                    modifier = Modifier.width(220.dp).height(46.dp),
                    cornerRadius = 23.dp,
                    background = if (isAm) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.12f),
                    focusedBackground = Color.White.copy(alpha = 0.38f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (isAm) stringResource(R.string.browse_selected_source, stringResource(R.string.browse_apple_name)) else stringResource(R.string.browse_apple_name),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = if (isAm) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            Text(
                text = stringResource(R.string.browse_performance_mode),
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(R.string.browse_performance_mode_hint),
                color = Color.White.copy(alpha = 0.72f),
                fontSize = 16.sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FocusSurface(
                    onClick = { onLowPerformanceModeChange(false) },
                    modifier = Modifier.width(220.dp).height(46.dp),
                    cornerRadius = 23.dp,
                    background = if (!lowPerformanceMode) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.12f),
                    focusedBackground = Color.White.copy(alpha = 0.38f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (!lowPerformanceMode) stringResource(R.string.browse_selected_source, stringResource(R.string.browse_performance_standard)) else stringResource(R.string.browse_performance_standard),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = if (!lowPerformanceMode) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
                FocusSurface(
                    onClick = { onLowPerformanceModeChange(true) },
                    modifier = Modifier.width(220.dp).height(46.dp),
                    cornerRadius = 23.dp,
                    background = if (lowPerformanceMode) Color.White.copy(alpha = 0.26f) else Color.White.copy(alpha = 0.12f),
                    focusedBackground = Color.White.copy(alpha = 0.38f),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (lowPerformanceMode) stringResource(R.string.browse_selected_source, stringResource(R.string.browse_performance_low)) else stringResource(R.string.browse_performance_low),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = if (lowPerformanceMode) FontWeight.Bold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
        cacheSettingsContent()
    }
}

@Composable
private fun SearchScreen(
    tracks: List<Track>,
    recentTracks: List<Track>,
    loading: Boolean,
    setupMessage: String?,
    onSearch: (String) -> Unit,
    onOpenTrack: (Track, List<Track>) -> Unit,
    firstContentFocusRequester: FocusRequester?,
    navigationFocusRequester: FocusRequester?,
) {
    val mandopopTerm = stringResource(R.string.browse_quick_mandopop)
    val quickTerms = remember(mandopopTerm) {
        listOf(mandopopTerm, "Doja Cat", "Ado", "YOASOBI", "周杰倫", "BLACKPINK", "Laufey", "Taylor Swift")
    }
    var query by remember { mutableStateOf("") }
    var lastExecutedQuery by remember { mutableStateOf("") }
    var isInputActive by remember { mutableStateOf(false) }
    var textFieldFocused by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current

    val textFieldFocus = firstContentFocusRequester ?: remember { FocusRequester() }
    val chipFocus = remember { List(quickTerms.size) { FocusRequester() } }
    var lastChip by remember { mutableIntStateOf(0) }
    val chipRowState = rememberLazyListState()
    val resultRowState = rememberLazyListState()
    val isSearching = lastExecutedQuery.isNotBlank()
    val shownTracks = if (isSearching) tracks else recentTracks
    val resultFocus = remember(shownTracks) { List(shownTracks.size) { FocusRequester() } }
    var lastResult by remember(shownTracks) { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    fun focusResult(): Boolean {
        if (shownTracks.isEmpty()) return false
        val index = lastResult.coerceIn(shownTracks.indices)
        scope.launch {
            if (resultRowState.layoutInfo.visibleItemsInfo.none { it.index == index }) {
                resultRowState.scrollToItem(index)
            }
            withFrameNanos { }
            runCatching { resultFocus[index].requestFocus() }
        }
        return true
    }

    LaunchedEffect(query) {
        val trimmed = query.trim()
        if (trimmed.isNotEmpty() && trimmed != lastExecutedQuery) {
            delay(400L)
            lastExecutedQuery = trimmed
            onSearch(trimmed)
        }
    }

    Column(Modifier.fillMaxSize()) {
        FocusTrackingRow(
            state = chipRowState,
            modifier = Modifier.fillMaxWidth().height(46.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(quickTerms, key = { _, term -> term }) { index, term ->
                FocusSurface(
                    onClick = {
                        query = term
                        lastExecutedQuery = term
                        onSearch(term)
                    },
                    modifier = Modifier
                        .automationId("search-term-$index")
                        .focusRequester(chipFocus[index])
                        .onFocusChanged { if (it.isFocused) lastChip = index }
                        .focusProperties {
                            if (index == 0) left = FocusRequester.Cancel
                            if (index == quickTerms.lastIndex) right = FocusRequester.Cancel
                            if (navigationFocusRequester != null) up = navigationFocusRequester
                            down = textFieldFocus
                        }
                        .height(38.dp),
                    cornerRadius = 19.dp,
                    focusedScale = 1.04f,
                    padding = PaddingValues(horizontal = 16.dp),
                    background = Color.White.copy(alpha = 0.10f),
                    focusedBackground = Color.White.copy(alpha = 0.92f),
                ) { focused ->
                    Box(Modifier.height(38.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = term,
                            color = if (focused) Color(0xFF111713) else Color.White.copy(alpha = 0.88f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            enabled = LocalFocusTargetsEnabled.current,
            value = query,
            onValueChange = { query = it },
            readOnly = !isInputActive,
            modifier = Modifier
                .automationId("search-field")
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
                .focusRequester(textFieldFocus)
                .focusFrameTarget(textFieldFocused, "search-field", 5.dp)
                .onFocusChanged { textFieldFocused = it.isFocused }
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER -> {
                            if (!isInputActive) {
                                isInputActive = true
                                keyboardController?.show()
                                true
                            } else {
                                isInputActive = false
                                keyboardController?.hide()
                                val trimmed = query.trim()
                                if (trimmed.isNotEmpty() && trimmed != lastExecutedQuery) {
                                    lastExecutedQuery = trimmed
                                    onSearch(trimmed)
                                }
                                true
                            }
                        }
                        KeyEvent.KEYCODE_BACK -> {
                            if (isInputActive) {
                                isInputActive = false
                                keyboardController?.hide()
                                true
                            } else {
                                false
                            }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            if (isInputActive) {
                                isInputActive = false
                                keyboardController?.hide()
                            }
                            focusResult()
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (isInputActive) {
                                isInputActive = false
                                keyboardController?.hide()
                            }
                            runCatching { chipFocus[lastChip].requestFocus() }
                            true
                        }
                        else -> false
                    }
                }
                .focusProperties {
                    up = chipFocus[lastChip]
                    down = resultFocus.getOrNull(lastResult) ?: FocusRequester.Cancel
                    if (!isInputActive) {
                        left = FocusRequester.Cancel
                        right = FocusRequester.Cancel
                    }
                },
            placeholder = {
                Text(
                    if (isInputActive) stringResource(R.string.browse_search_hint)
                    else stringResource(R.string.browse_search_hint_remote)
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    isInputActive = false
                    keyboardController?.hide()
                    val trimmed = query.trim()
                    if (trimmed.isNotEmpty() && trimmed != lastExecutedQuery) {
                        lastExecutedQuery = trimmed
                        onSearch(trimmed)
                    }
                }
            ),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                focusedBorderColor = if (isInputActive) Color.White else Color.White.copy(alpha = 0.82f),
                unfocusedBorderColor = Color.White.copy(alpha = 0.22f),
                focusedContainerColor = if (isInputActive) Color.White.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.08f),
                unfocusedContainerColor = Color.White.copy(alpha = 0.05f),
                focusedPlaceholderColor = Color.White.copy(alpha = 0.52f),
                unfocusedPlaceholderColor = Color.White.copy(alpha = 0.38f),
            ),
        )
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionHeading(if (isSearching) stringResource(R.string.browse_search_results) else stringResource(R.string.browse_recently_played))
            if (loading && shownTracks.isNotEmpty()) {
                Spacer(Modifier.width(14.dp))
                AppleLoadingIndicator(Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        if (shownTracks.isEmpty()) {
            if (loading) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    AppleLoadingIndicator()
                }
            } else {
                Text(
                    stringResource(if (setupMessage.isNullOrBlank()) R.string.browse_search_empty else R.string.browse_search_error),
                    color = Color.White.copy(alpha = 0.64f), fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 16.dp),
                )
            }
        } else {
            FocusTrackingRow(
                state = resultRowState,
                modifier = Modifier.fillMaxWidth().weight(1f).focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp),
            ) {
                itemsIndexed(shownTracks, key = { index, item -> "$index:${item.id}" }) { index, item ->
                    RecentCard(
                        track = item,
                        onClick = { onOpenTrack(item, shownTracks) },
                        focusModifier = Modifier.automationId("search-result-$index").focusRequester(resultFocus[index])
                            .onFocusChanged { if (it.isFocused) lastResult = index }
                            .focusProperties {
                                up = textFieldFocus
                                down = FocusRequester.Cancel
                                if (index == 0) left = FocusRequester.Cancel
                                if (index == shownTracks.lastIndex) right = FocusRequester.Cancel
                            },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private object StationaryShelfViewport : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float = 0f
}

internal fun shelfRevealScroll(offset: Int, size: Int, viewportStart: Int, viewportEnd: Int): Float = when {
    offset < viewportStart -> (offset - viewportStart).toFloat()
    offset + size > viewportEnd -> minOf(offset - viewportStart, offset + size - viewportEnd).toFloat()
    else -> 0f
}

internal data class ShelfFocusScroll(val shelfIndex: Int, val destinationOffset: Float) {
    fun remaining(shelf: Int, currentOffset: Int): Float =
        if (shelf == shelfIndex) currentOffset - destinationOffset else 0f
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeShelves(
    shelves: List<ContentShelf>,
    loading: Boolean,
    connectionState: MusicConnectionState,
    setupMessage: String?,
    onSignIn: () -> Unit,
    onRetry: () -> Unit,
    onOpenTrack: (Track, List<Track>) -> Unit,
    firstContentFocusRequester: FocusRequester?,
    navigationFocusRequester: FocusRequester?,
) {
    if (shelves.isEmpty()) {
        if (loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                AppleLoadingIndicator()
            }
            return
        }
        val retryRequester = firstContentFocusRequester ?: remember { FocusRequester() }
        val signInRequester = remember { FocusRequester() }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .width(680.dp)
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .padding(horizontal = 36.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text(
                    text = stringResource(R.string.browse_content_unavailable),
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
                val detailText = when {
                    connectionState != MusicConnectionState.SignedIn ->
                        stringResource(R.string.browse_sign_in_personalized)
                    else -> stringResource(R.string.browse_check_connection)
                }
                Text(
                    text = detailText,
                    color = Color.White.copy(alpha = 0.72f),
                    fontSize = 16.sp,
                    lineHeight = 24.sp,
                    textAlign = TextAlign.Center,
                )
                if (!loading) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FocusSurface(
                            onClick = onRetry,
                            modifier = Modifier
                                .width(200.dp)
                                .height(48.dp)
                                .focusRequester(retryRequester)
                                .focusProperties {
                                    if (navigationFocusRequester != null) up = navigationFocusRequester
                                    right = signInRequester
                                },
                            cornerRadius = 24.dp,
                            focusedScale = 1.04f,
                            background = Color.White.copy(alpha = 0.9f),
                            focusedBackground = Color.White,
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    text = stringResource(R.string.browse_reload),
                                    color = Color(0xFF111713),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                        if (connectionState != MusicConnectionState.Demo) {
                            FocusSurface(
                                onClick = onSignIn,
                                modifier = Modifier
                                    .width(230.dp)
                                    .height(48.dp)
                                    .focusRequester(signInRequester)
                                    .focusProperties {
                                        if (navigationFocusRequester != null) up = navigationFocusRequester
                                        left = retryRequester
                                    },
                                cornerRadius = 24.dp,
                                focusedScale = 1.04f,
                                background = Color.White.copy(alpha = 0.14f),
                                focusedBackground = Color.White.copy(alpha = 0.28f),
                            ) {
                                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(
                                        text = if (connectionState == MusicConnectionState.SignedIn) {
                                            stringResource(R.string.browse_sign_in_again)
                                        } else {
                                            stringResource(R.string.browse_apple_sign_in)
                                        },
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        return
    }

    val shelfKey = remember(shelves) {
        shelves.map { it.id to it.items.size }
    }
    val columnState = rememberLazyListState()
    val rowStates = remember(shelfKey) {
        shelves.map { androidx.compose.foundation.lazy.LazyListState() }
    }
    val itemFocusRequesters = remember(shelfKey, firstContentFocusRequester) {
        shelves.mapIndexed { shelfIndex, shelf ->
            shelf.items.mapIndexed { itemIndex, _ ->
                if (shelfIndex == 0 && itemIndex == 0 && firstContentFocusRequester != null) {
                    firstContentFocusRequester
                } else FocusRequester()
            }
        }
    }
    // The key handler reads this holder without invalidating the whole LazyColumn
    // whenever focus moves one card horizontally.
    val navigationCursor = remember(shelfKey) { ShelfNavigationCursor(shelves.map { it.items.size }) }
    val scope = rememberCoroutineScope()
    val focusJob = remember { arrayOfNulls<kotlinx.coroutines.Job>(1) }
    val verticalPlan = remember { mutableStateOf<ShelfFocusScroll?>(null) }
    val density = LocalDensity.current
    val focusTargetsEnabled = LocalFocusTargetsEnabled.current
    val cardWarmup = remember(shelfKey) { HomeCardWarmup() }
    DisposableEffect(cardWarmup) {
        onDispose { cardWarmup.close() }
    }
    LaunchedEffect(cardWarmup, focusTargetsEnabled) {
        if (!focusTargetsEnabled) return@LaunchedEffect
        while (cardWarmup.awaitPending()) {
            withFrameNanos { cardWarmup.mountNext(it) }
        }
    }
    LaunchedEffect(cardWarmup, columnState, rowStates, focusTargetsEnabled) {
        if (!focusTargetsEnabled) return@LaunchedEffect
        // Prefetched slots cannot consume a content permit until they are in
        // the real viewport. Observe index sets, rather than every scroll pixel.
        snapshotFlow {
            val column = columnState.layoutInfo
            column.visibleItemsInfo.filter {
                it.offset < column.viewportEndOffset && it.offset + it.size > column.viewportStartOffset
            }.mapNotNull { shelf ->
                val row = rowStates.getOrNull(shelf.index)?.layoutInfo ?: return@mapNotNull null
                shelf.index to row.visibleItemsInfo.filter {
                    it.offset < row.viewportEndOffset && it.offset + it.size > row.viewportStartOffset
                }.map { it.index }
            }
        }.collect { cardWarmup.notifyPrioritiesChanged() }
    }
    DisposableEffect(shelfKey, focusTargetsEnabled) {
        onDispose {
            focusJob[0]?.cancel()
            navigationCursor.cancel()
            verticalPlan.value = null
        }
    }

    fun cancelVerticalFocus() {
        focusJob[0]?.cancel()
        navigationCursor.cancel()
        verticalPlan.value = null
        cardWarmup.notifyPrioritiesChanged()
    }

    fun shelfBounds(shelfIndex: Int): ShelfRowBounds {
        val row = rowStates[shelfIndex]
        val layout = row.layoutInfo
        val measuredWidth = (layout.viewportEndOffset - layout.viewportStartOffset).toFloat()
        if (measuredWidth > 0f && layout.visibleItemsInfo.isNotEmpty()) {
            return ShelfRowBounds(measuredWidth, layout.visibleItemsInfo.map { item ->
                ShelfCardBounds(item.index, (item.offset - layout.viewportStartOffset).toFloat(), item.size.toFloat())
            })
        }
        val shelf = shelves[shelfIndex]
        val viewportWidth = columnState.layoutInfo.viewportSize.width.toFloat()
        return estimateShelfRowBounds(
            shelf.items.size, row.firstVisibleItemIndex, row.firstVisibleItemScrollOffset,
            with(density) { (if (shelf.featured) 210.dp else 168.dp).toPx() },
            with(density) { (if (shelf.featured) 18.dp else 20.dp).toPx() },
            with(density) { 14.dp.toPx() }, viewportWidth,
        )
    }

    fun moveVerticalFocus(direction: Int): Boolean {
        val from = navigationCursor.requestedPosition
        val sourceCenter = shelfBounds(from.shelf).cards.firstOrNull { it.index == from.item }?.centerX
        val request = navigationCursor.request(direction, sourceCenter) { target, anchor ->
            nearestVisibleShelfCard(anchor, shelfBounds(target))
        } ?: return false
        cardWarmup.notifyPrioritiesChanged()
        val targetShelf = request.position.shelf
        focusJob[0]?.cancel()
        verticalPlan.value = null
        focusJob[0] = scope.launch {
            val layout = columnState.layoutInfo
            val visible = layout.visibleItemsInfo.firstOrNull { it.index == targetShelf }
            val distance = visible?.let {
                shelfRevealScroll(it.offset, it.size, layout.viewportStartOffset, layout.viewportEndOffset)
            } ?: 0f
            // animateScrollToItem aligns to the top unless the remaining content
            // reaches the end first. Predict that reachable endpoint before the
            // incoming target can report its focus-frame bounds.
            val destinationOffset = if (visible != null) visible.offset - distance else {
                val rowGap = with(density) { 18.dp.toPx() }
                val trailingHeight = (targetShelf until shelves.size).sumOf { index ->
                    val measured = layout.visibleItemsInfo.firstOrNull { it.index == index }?.size?.toFloat()
                    (measured ?: with(density) {
                        26.sp.toPx() + (if (shelves[index].featured) 12.dp else 8.dp).toPx() +
                            (if (shelves[index].featured) 320.dp else 260.dp).toPx()
                    }).toDouble()
                }.toFloat() + rowGap * (shelves.lastIndex - targetShelf)
                maxOf(layout.viewportStartOffset.toFloat(),
                    layout.viewportEndOffset - layout.afterContentPadding - trailingHeight)
            }
            val plan = ShelfFocusScroll(targetShelf, destinationOffset)
            verticalPlan.value = plan
            fun tryFocusItem(): Boolean {
                if (!navigationCursor.isCurrent(request)) return false
                val column = columnState.layoutInfo
                val attachedRow = column.visibleItemsInfo.firstOrNull { it.index == targetShelf }
                    ?: return false
                if (attachedRow.offset >= column.viewportEndOffset ||
                    attachedRow.offset + attachedRow.size <= column.viewportStartOffset) return false
                val row = rowStates[targetShelf].layoutInfo
                if (row.visibleItemsInfo.isEmpty() || row.viewportEndOffset <= row.viewportStartOffset) return false
                val item = nearestVisibleShelfCard(request.anchorX, shelfBounds(targetShelf))
                if (!navigationCursor.refine(request, item)) return false
                cardWarmup.notifyPrioritiesChanged()
                if (navigationCursor.focused == request.position) return true
                // A retained LazyListState can still report its old children
                // while their FocusRequesters are detached. Retry on placement.
                return runCatching {
                    itemFocusRequesters[targetShelf][item].requestFocus() &&
                        navigationCursor.focused == request.position
                }.getOrDefault(false)
            }
            try {
                // Visible incoming cards receive focus in the input transaction,
                // before the viewport moves. Offscreen cards receive it on their
                // first attached frame while reveal is still running.
                var focused = tryFocusItem()
                val reveal = launch(start = CoroutineStart.UNDISPATCHED) {
                    if (visible == null) columnState.animateScrollToItem(targetShelf)
                    else if (kotlin.math.abs(distance) > 1f) {
                        columnState.animateScrollBy(distance, tween(260, easing = MotionTokens.KaraokeLineEasing))
                    }
                }
                var placementFramesAfterReveal = 0
                while (!focused && navigationCursor.isCurrent(request) &&
                    (!reveal.isCompleted || placementFramesAfterReveal < 2)) {
                    withFrameNanos { }
                    focused = tryFocusItem()
                    if (reveal.isCompleted) placementFramesAfterReveal++
                }
                reveal.join()
            } finally {
                if (verticalPlan.value === plan) verticalPlan.value = null
                navigationCursor.complete(request)
                cardWarmup.notifyPrioritiesChanged()
            }
        }
        return true
    }

    // Only explicit Up/Down navigation may move the vertical viewport. Focused
    // artwork scales in drawing and must not continuously re-center the column.
    CompositionLocalProvider(LocalBringIntoViewSpec provides StationaryShelfViewport) {
        LazyColumn(
            state = columnState,
            modifier = Modifier
                .automationId("home-shelves-count-${shelves.size}")
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                        return@onPreviewKeyEvent false
                    }
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP -> {
                            if (!moveVerticalFocus(-1)) {
                                cancelVerticalFocus()
                                navigationFocusRequester?.let { requester ->
                                    runCatching { requester.requestFocus() }
                                }
                            }
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> {
                            moveVerticalFocus(1)
                            true
                        }
                        KeyEvent.KEYCODE_DPAD_LEFT,
                        KeyEvent.KEYCODE_DPAD_RIGHT,
                        KeyEvent.KEYCODE_DPAD_CENTER,
                        KeyEvent.KEYCODE_ENTER,
                        KeyEvent.KEYCODE_BACK -> {
                            cancelVerticalFocus()
                            false
                        }
                        else -> false
                    }
                },
            contentPadding = PaddingValues(bottom = 64.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            itemsIndexed(shelves, key = { _, shelf -> shelf.id }) { shelfIndex, shelf ->
                Column(Modifier.automationId("home-row-$shelfIndex-count-${shelf.items.size}")) {
                    SectionHeading(shelf.title)
                    Spacer(Modifier.height(if (shelf.featured) 12.dp else 8.dp))
                    val remainingVerticalScroll = remember(shelfIndex, columnState) {
                        derivedStateOf {
                            val offset = columnState.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.index == shelfIndex }?.offset
                            if (offset == null) 0f else verticalPlan.value?.remaining(shelfIndex, offset) ?: 0f
                        }
                    }
                    FocusTrackingRow(
                        state = rowStates[shelfIndex],
                        verticalScrollOffset = remainingVerticalScroll,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(if (shelf.featured) 320.dp else 260.dp)
                            .focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(
                            if (shelf.featured) 18.dp else 20.dp,
                        ),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 20.dp),
                    ) {
                        itemsIndexed(
                            shelf.items,
                            key = { _, track -> "${shelf.id}:${track.id}" },
                        ) { itemIndex, track ->
                            val focusModifier = Modifier
                                .automationId("shelf-$shelfIndex-item-$itemIndex-${track.catalogKind.name.lowercase()}")
                                .focusRequester(itemFocusRequesters[shelfIndex][itemIndex])
                                .focusProperties {
                                    if (itemIndex == 0) left = FocusRequester.Cancel
                                    if (itemIndex == shelf.items.lastIndex) right = FocusRequester.Cancel
                                }
                                .onFocusChanged { state ->
                                    if (state.isFocused) {
                                        navigationCursor.onFocused(shelfIndex, itemIndex)
                                        cardWarmup.notifyPrioritiesChanged()
                                    }
                                }
                            val mountPriority = {
                                val requested = navigationCursor.requestedPosition
                                val columnLayout = columnState.layoutInfo
                                val visibleRow = columnLayout.visibleItemsInfo.any {
                                    it.index == shelfIndex && it.offset < columnLayout.viewportEndOffset &&
                                        it.offset + it.size > columnLayout.viewportStartOffset
                                }
                                val rowLayout = rowStates[shelfIndex].layoutInfo
                                val visibleCard = rowLayout.visibleItemsInfo.any {
                                    it.index == itemIndex && it.offset < rowLayout.viewportEndOffset &&
                                        it.offset + it.size > rowLayout.viewportStartOffset
                                }
                                when {
                                    visibleRow && visibleCard &&
                                        requested.shelf == shelfIndex && requested.item == itemIndex -> 0
                                    visibleRow && visibleCard -> 1
                                    else -> 2
                                }
                            }
                            if (shelf.featured) {
                                TopPick(
                                    label = track.eyebrow(),
                                    track = track,
                                    onClick = { onOpenTrack(track, shelf.items) },
                                    focusModifier = focusModifier,
                                    warmup = cardWarmup,
                                    mountPriority = mountPriority,
                                )
                            } else {
                                RecentCard(
                                    track = track,
                                    onClick = { onOpenTrack(track, shelf.items) },
                                    focusModifier = focusModifier,
                                    warmup = cardWarmup,
                                    mountPriority = mountPriority,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = 0.66f),
        fontSize = 22.sp,
        lineHeight = 26.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 8.dp),
    )
}


@Composable
private fun rememberHomeCardMount(
    warmup: HomeCardWarmup?,
    key: Any,
    priority: () -> Int,
): State<Boolean> {
    val ready = remember(warmup, key) { mutableStateOf(warmup == null) }
    val currentPriority = rememberUpdatedState(priority)
    if (warmup != null) {
        DisposableEffect(warmup, key) {
            val ticket = warmup.request({ currentPriority.value() }) { ready.value = true }
            onDispose {
                warmup.cancel(ticket)
                // Reusable lazy slots must rejoin the budget when attached again.
                ready.value = false
            }
        }
    }
    return ready
}

@Composable
private fun Modifier.homeCardSemantics(track: Track, ready: State<Boolean>, staged: Boolean): Modifier {
    val loadedLabel = stringResource(R.string.browse_loaded)
    val loadingLabel = stringResource(R.string.browse_loading)
    return if (!staged) this else semantics {
        contentDescription = listOf(track.title, track.artist).filter { it.isNotBlank() }.joinToString(", ")
        stateDescription = if (ready.value) loadedLabel else loadingLabel
    }
}

/** Only this small restart scope reads the permit, never the focus shell or row. */
@Composable
private fun HomeCardContent(
    ready: State<Boolean>,
    track: Track,
    featured: Boolean,
    staged: Boolean,
    content: @Composable () -> Unit,
) {
    if (!staged) {
        content()
        return
    }
    Box(Modifier.fillMaxSize().clearAndSetSemantics { }) {
        if (ready.value) content() else HomeCardPlaceholder(track, featured)
    }
}

@Composable
private fun HomeCardEyebrow(ready: State<Boolean>, label: String) {
    if (ready.value) {
        Text(
            text = label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 14.sp,
        )
    }
}

/** No font shaping, image request or changing geometry before the card's grant. */
@Composable
private fun HomeCardPlaceholder(track: Track, featured: Boolean) {
    Canvas(Modifier.fillMaxSize().then(if (featured) Modifier.clip(RoundedCornerShape(14.dp)) else Modifier)) {
        val coverHeight = if (featured) (size.height - 72.dp.toPx()).coerceAtLeast(0f) else 168.dp.toPx()
        val corner = CornerRadius(14.dp.toPx())
        if (featured) drawRoundRect(track.palette.last().copy(alpha = 0.58f), cornerRadius = corner)
        drawRoundRect(
            color = track.palette.first(),
            size = Size(size.width, coverHeight),
            cornerRadius = if (featured) CornerRadius.Zero else corner,
        )
        val titleWidth = size.width * 0.64f
        val artistWidth = size.width * 0.42f
        val titleY = coverHeight + (if (featured) 20.dp else 13.dp).toPx()
        val titleX = if (featured) (size.width - titleWidth) / 2f else 0f
        val artistX = if (featured) (size.width - artistWidth) / 2f else 0f
        drawRoundRect(
            Color.White.copy(alpha = 0.12f), Offset(titleX, titleY),
            Size(titleWidth, 8.dp.toPx()), CornerRadius(4.dp.toPx()),
        )
        drawRoundRect(
            Color.White.copy(alpha = 0.07f), Offset(artistX, titleY + 16.dp.toPx()),
            Size(artistWidth, 6.dp.toPx()), CornerRadius(3.dp.toPx()),
        )
    }
}

@Composable
private fun TopPick(
    label: String,
    track: Track,
    onClick: () -> Unit,
    focusModifier: Modifier = Modifier,
    warmup: HomeCardWarmup? = null,
    mountPriority: () -> Int = { 1 },
) {
    val contentReady = rememberHomeCardMount(warmup, track.id, mountPriority)
    val cardShape = RoundedCornerShape(14.dp)
    val genericEyebrows = listOf(
        stringResource(R.string.browse_kind_song), stringResource(R.string.browse_kind_album),
        stringResource(R.string.browse_kind_playlist),
    )
    val distinctEyebrow = label.takeIf {
        it.isNotBlank() && !it.equals(track.artist, ignoreCase = true) &&
            !it.equals("Playlist", ignoreCase = true) &&
            !it.equals("Album", ignoreCase = true) &&
            !it.equals("Song", ignoreCase = true) &&
            genericEyebrows.none { placeholder -> it.equals(placeholder, ignoreCase = true) }
    }
    Column(Modifier.width(210.dp).height(280.dp)) {
        Box(Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.TopStart) {
            HomeCardEyebrow(contentReady, distinctEyebrow.orEmpty())
        }
        FocusSurface(
            onClick = onClick,
            deferIdleVisuals = warmup != null,
            modifier = focusModifier
                .homeCardSemantics(track, contentReady, warmup != null)
                .fillMaxWidth()
                .height(256.dp),
            cornerRadius = 14.dp,
            focusedScale = 1.03f,
            focusedLift = 2.dp,
            restingElevation = 0.dp,
            focusedElevation = 10.dp,
            showBorder = false,
            background = Color.Transparent,
            focusedBackground = Color.Transparent,
        ) { focused ->
            HomeCardContent(contentReady, track, featured = true, staged = warmup != null) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .clip(cardShape)
                        .background(track.palette.last().copy(alpha = 0.58f)),
                ) {
                    Box(Modifier.weight(1f)) {
                        MotionArtwork(
                            track = track,
                            modifier = Modifier.fillMaxSize(),
                            animate = focused,
                            cornerRadius = 0.dp,
                        )
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(72.dp)
                            .background(
                                Brush.verticalGradient(
                                    listOf(
                                        Color.Black.copy(alpha = 0.08f),
                                        Color.Black.copy(alpha = 0.32f),
                                    ),
                                ),
                            )
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            text = track.title,
                            color = Color.White.copy(alpha = 0.96f),
                            fontSize = 14.sp,
                            lineHeight = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (track.artist.isNotBlank()) {
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = track.artist,
                                color = Color.White.copy(alpha = 0.72f),
                                fontSize = 12.sp,
                                lineHeight = 16.sp,
                                fontWeight = FontWeight.Medium,
                                textAlign = TextAlign.Center,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentCard(
    track: Track,
    onClick: () -> Unit,
    focusModifier: Modifier = Modifier,
    warmup: HomeCardWarmup? = null,
    mountPriority: () -> Int = { 1 },
) {
    val contentReady = rememberHomeCardMount(warmup, track.id, mountPriority)
    FocusSurface(
        onClick = onClick,
        deferIdleVisuals = warmup != null,
        modifier = focusModifier.homeCardSemantics(track, contentReady, warmup != null)
            .width(168.dp).height(220.dp),
        cornerRadius = 14.dp,
        focusedScale = 1.035f,
        focusedLift = 2.dp,
        restingElevation = 0.dp,
        focusedElevation = 0.dp,
        showBorder = false,
        background = Color.Transparent,
        focusedBackground = Color.Transparent,
    ) { focused ->
        HomeCardContent(contentReady, track, featured = false, staged = warmup != null) {
            Column {
                MotionArtwork(
                    track = track,
                    modifier = Modifier.size(168.dp),
                    animate = focused,
                    cornerRadius = 14.dp,
                )
                Text(
                    text = track.title,
                    color = Color.White.copy(alpha = 0.92f),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 9.dp),
                )
                Text(
                    text = track.artist,
                    color = Color.White.copy(alpha = 0.56f),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
        }
    }
}

private fun firstRowFocusModifier(
    index: Int,
    firstContentFocusRequester: FocusRequester?,
    navigationFocusRequester: FocusRequester?,
): Modifier {
    var result: Modifier = Modifier
    if (index == 0 && firstContentFocusRequester != null) {
        result = result.focusRequester(firstContentFocusRequester)
    }
    if (navigationFocusRequester != null) {
        result = result.focusProperties { up = navigationFocusRequester }
    }
    return result
}

@Composable
private fun Track.eyebrow(): String = when (catalogKind) {
    CatalogKind.Song -> album.takeIf { it.isNotBlank() && it != "Song" } ?: stringResource(R.string.browse_kind_song)
    CatalogKind.Album -> stringResource(R.string.browse_kind_album)
    CatalogKind.Playlist -> artist.takeIf { it.isNotBlank() } ?: stringResource(R.string.browse_kind_playlist)
    CatalogKind.Station -> stringResource(R.string.browse_kind_station)
    CatalogKind.MusicVideo -> stringResource(R.string.browse_kind_music_video)
}
