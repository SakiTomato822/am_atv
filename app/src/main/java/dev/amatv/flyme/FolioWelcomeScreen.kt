package dev.amatv.flyme

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.amatv.R
import dev.amatv.artwork.rememberArtworkPaletteResult
import dev.amatv.core.AccountState
import dev.amatv.core.CURRENT_WELCOME_VERSION
import dev.amatv.core.ContentState
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.core.PlayHistoryEntry
import dev.amatv.core.PlaybackState
import dev.amatv.core.SettingsState
import dev.amatv.core.SettingsStore
import dev.amatv.core.WelcomePreferences
import dev.amatv.integration.LoudnessMode
import dev.amatv.integration.LyricTextSize
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.MusicServices
import dev.amatv.model.Track
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private data class WelcomeStageTarget(
    val xU: Float,
    val yU: Float,
    val scale: Float,
    val alpha: Float,
)

private fun stageTargetFor(pageId: WelcomePageId, isPhonePortrait: Boolean): WelcomeStageTarget =
    if (isPhonePortrait) {
        when (pageId) {
            WelcomePageId.Welcome -> WelcomeStageTarget(24f, 80f, 0.40f, 0f)
            WelcomePageId.SignIn -> WelcomeStageTarget(7f, 58f, 1.66f, 0f)
            WelcomePageId.Lyrics -> WelcomeStageTarget(24f, 23f, 1f, 1f)
            WelcomePageId.Styles -> WelcomeStageTarget(24f, 23f, 1f, 1f)
            WelcomePageId.Gapless -> WelcomeStageTarget(24f, 23f, 1f, 1f)
            WelcomePageId.Preferences -> WelcomeStageTarget(29.7f, 20f, 0.78f, 1f)
        }
    } else {
        when (pageId) {
            WelcomePageId.Welcome -> WelcomeStageTarget(56f, 14f, 0.70f, 0f)
            WelcomePageId.SignIn -> WelcomeStageTarget(5f, 11f, 0.60f, 0f)
            WelcomePageId.Lyrics -> WelcomeStageTarget(42f, 11f, 1.00f, 1f)
            WelcomePageId.Styles -> WelcomeStageTarget(42f, 11f, 1.00f, 1f)
            WelcomePageId.Gapless -> WelcomeStageTarget(5f, 11f, 1.00f, 1f)
            WelcomePageId.Preferences -> WelcomeStageTarget(5f, 11.5f, 0.98f, 1f)
        }
    }

@Composable
internal fun FolioWelcomeOverlay(
    open: Boolean,
    settingsStore: SettingsStore,
    settingsState: SettingsState,
    accountState: AccountState,
    contentState: ContentState,
    playHistoryEntries: List<PlayHistoryEntry>,
    playbackState: PlaybackState,
    services: MusicServices,
    welcomePrefs: WelcomePreferences,
    statusTop: Dp,
    navBottom: Dp,
    onStartSignIn: () -> Unit,
    onNotice: (String) -> Unit,
    onRequestBrowseLoad: () -> Unit,
    onClose: () -> Unit,
) {
    val lowPerformance = settingsState.lowPerformance
    val animated = rememberWelcomeAnimationsEnabled()
    val canPlayIntro = animated && !LocalFolioReducedMotion.current &&
        !lowPerformance && !LocalFolioBrowseMotion.current.lowPerformance
    AnimatedVisibility(
        visible = open,
        enter = if (canPlayIntro) EnterTransition.None else fadeIn(tween(if (lowPerformance) 150 else 280)),
        exit = fadeOut(tween(if (lowPerformance) 150 else 360)),
        modifier = Modifier
            .fillMaxSize()
            .zIndex(50f),
    ) {
        FolioWelcomeScreenContent(
            open = open,
            animated = animated,
            canPlayIntro = canPlayIntro,
            settingsStore = settingsStore,
            settingsState = settingsState,
            accountState = accountState,
            contentState = contentState,
            playHistoryEntries = playHistoryEntries,
            playbackState = playbackState,
            services = services,
            welcomePrefs = welcomePrefs,
            statusTop = statusTop,
            navBottom = navBottom,
            onStartSignIn = onStartSignIn,
            onNotice = onNotice,
            onRequestBrowseLoad = onRequestBrowseLoad,
            onClose = onClose,
        )
    }
}

@Composable
private fun FolioWelcomeScreenContent(
    open: Boolean,
    animated: Boolean,
    canPlayIntro: Boolean,
    settingsStore: SettingsStore,
    settingsState: SettingsState,
    accountState: AccountState,
    contentState: ContentState,
    playHistoryEntries: List<PlayHistoryEntry>,
    playbackState: PlaybackState,
    services: MusicServices,
    welcomePrefs: WelcomePreferences,
    statusTop: Dp,
    navBottom: Dp,
    onStartSignIn: () -> Unit,
    onNotice: (String) -> Unit,
    onRequestBrowseLoad: () -> Unit,
    onClose: () -> Unit,
) {
    val motion = LocalFolioBrowseMotion.current
    val lowPerformance = motion.lowPerformance || settingsState.lowPerformance
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    val isAppPlaying = playbackState.isPlaying || playbackState.playWhenReady
    val bgm = rememberFolioWelcomeBgmController(welcomePrefs)
    LaunchedEffect(Unit) {
        onRequestBrowseLoad()
        bgm.startIfAllowed(isAppPlaying)
    }

    // Resume BGM when returning from the in-app web sign-in flow without signing out cleanly
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestIsAppPlaying by rememberUpdatedState(isAppPlaying)
    DisposableEffect(lifecycleOwner, bgm) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                bgm.resumeAfterExternalFlow(latestIsAppPlaying)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pages = remember(accountState.connectionState) {
        welcomePageSequence(accountState.connectionState)
    }
    var currentPageId by remember { mutableStateOf(WelcomePageId.Welcome) }
    var isClosing by remember { mutableStateOf(false) }
    var introFinished by remember { mutableStateOf(!canPlayIntro) }
    var introSkipped by remember { mutableStateOf(false) }
    val introActive = open && canPlayIntro && !introFinished
    fun finishIntro(skipped: Boolean) {
        introSkipped = skipped
        introFinished = true
    }
    LaunchedEffect(canPlayIntro) {
        if (!canPlayIntro) finishIntro(true)
    }
    val auraGatherScale = remember { Animatable(1f) }
    val ridgeGatherAlpha = remember { Animatable(FolioGatherMotion.WELCOME_RIDGE_BASE_ALPHA) }
    val closingStageProgress = remember { Animatable(0f) }
    val overlayFadeAlpha = remember { Animatable(1f) }

    val demoContent = rememberWelcomeDemoContent(
        connectionState = accountState.connectionState,
        contentState = contentState,
        playHistoryEntries = playHistoryEntries,
        services = services,
        lyricTranslationEnabled = settingsState.lyricTranslationEnabled,
        lyricPronunciationEnabled = settingsState.lyricPronunciationEnabled,
    )

    // Interactive state across demo pages
    var gaplessTrackIdx by remember { mutableIntStateOf(0) }
    var autoStyleActive by remember(currentPageId) { mutableStateOf(true) }
    var demoPosterMode by remember { mutableStateOf(settingsState.lyricStyle == LyricBackgroundStyle.Soak) }
    var gaplessProgress by remember { mutableFloatStateOf(0.72f) }
    var showGaplessTag by remember { mutableStateOf(false) }
    var showLoudnessBars by remember { mutableStateOf(false) }

    LaunchedEffect(currentPageId, autoStyleActive) {
        when (currentPageId) {
            WelcomePageId.Styles -> {
                if (!autoStyleActive) return@LaunchedEffect
                delay(1_400L)
                while (autoStyleActive) {
                    demoPosterMode = !demoPosterMode
                    delay(3_000L)
                }
            }
            WelcomePageId.Preferences -> {
                demoPosterMode = settingsState.lyricStyle == LyricBackgroundStyle.Soak
            }
            else -> {
                demoPosterMode = false
            }
        }
    }

    LaunchedEffect(currentPageId, demoContent.showcaseTracks.size) {
        if (currentPageId != WelcomePageId.Gapless) {
            gaplessTrackIdx = 0
            showGaplessTag = false
            gaplessProgress = 0.72f
            return@LaunchedEffect
        }
        val trackCount = demoContent.showcaseTracks.size.coerceAtLeast(1)
        val progAnim = Animatable(0.72f)
        while (true) {
            progAnim.snapTo(0.72f)
            progAnim.animateTo(1f, tween(2_200, easing = LinearEasing)) {
                gaplessProgress = value
            }
            delay(100L)
            gaplessTrackIdx = (gaplessTrackIdx + 1) % trackCount
            gaplessProgress = 0f
            showGaplessTag = true
            delay(1_500L)
            showGaplessTag = false
            delay(1_700L)
        }
    }

    var loudnessPulse by remember { mutableIntStateOf(0) }
    LaunchedEffect(loudnessPulse) {
        if (loudnessPulse > 0) {
            showLoudnessBars = true
            delay(2_200L)
            showLoudnessBars = false
        }
    }

    fun navigateTo(targetPageId: WelcomePageId) {
        currentPageId = targetPageId
    }

    fun startClosingSequence() {
        if (isClosing) return
        welcomePrefs.welcomeVersion = CURRENT_WELCOME_VERSION
        isClosing = true
        bgm.fadeOutAndRelease()
        scope.launch {
            if (isWelcomeAuraMotionEnabled(animated, lowPerformance)) {
                coroutineScope {
                    launch {
                        closingStageProgress.animateTo(
                            1f,
                            tween(550, easing = FolioGatherMotion.ReleaseEasing),
                        )
                    }
                    launch {
                        auraGatherScale.animateTo(
                            FolioGatherMotion.WELCOME_AURA_GATHER_SCALE,
                            tween(FolioGatherMotion.WELCOME_AURA_GATHER_MS, easing = FolioGatherMotion.GatherEasing),
                        )
                        auraGatherScale.animateTo(
                            1f,
                            tween(FolioGatherMotion.WELCOME_AURA_RELEASE_MS, easing = FolioGatherMotion.ReleaseEasing),
                        )
                    }
                    launch {
                        ridgeGatherAlpha.animateTo(
                            FolioGatherMotion.WELCOME_RIDGE_GATHER_ALPHA,
                            tween(FolioGatherMotion.WELCOME_AURA_GATHER_MS, easing = FolioGatherMotion.GatherEasing),
                        )
                        ridgeGatherAlpha.animateTo(
                            FolioGatherMotion.WELCOME_RIDGE_BASE_ALPHA,
                            tween(FolioGatherMotion.WELCOME_AURA_RELEASE_MS, easing = FolioGatherMotion.ReleaseEasing),
                        )
                    }
                    launch {
                        delay(380L)
                        overlayFadeAlpha.animateTo(
                            0f,
                            tween(550, easing = FolioGatherMotion.ReleaseEasing),
                        )
                    }
                }
            } else {
                overlayFadeAlpha.animateTo(0f, tween(FolioGatherMotion.LOW_PERF_FADE_MS))
            }
            onClose()
        }
    }

    LaunchedEffect(accountState.connectionState) {
        if (currentPageId == WelcomePageId.SignIn && accountState.connectionState == MusicConnectionState.SignedIn) {
            if (isWelcomeAuraMotionEnabled(animated, lowPerformance)) {
                coroutineScope {
                    launch {
                        auraGatherScale.animateTo(
                            FolioGatherMotion.WELCOME_AURA_GATHER_SCALE,
                            tween(FolioGatherMotion.WELCOME_AURA_GATHER_MS, easing = FolioGatherMotion.GatherEasing),
                        )
                    }
                    launch {
                        ridgeGatherAlpha.animateTo(
                            FolioGatherMotion.WELCOME_RIDGE_GATHER_ALPHA,
                            tween(FolioGatherMotion.WELCOME_AURA_GATHER_MS, easing = FolioGatherMotion.GatherEasing),
                        )
                    }
                }
                navigateTo(WelcomePageId.Lyrics)
                launch {
                    auraGatherScale.animateTo(
                        1f,
                        tween(FolioGatherMotion.WELCOME_AURA_RELEASE_MS, easing = FolioGatherMotion.ReleaseEasing),
                    )
                }
                launch {
                    ridgeGatherAlpha.animateTo(
                        FolioGatherMotion.WELCOME_RIDGE_BASE_ALPHA,
                        tween(FolioGatherMotion.WELCOME_AURA_RELEASE_MS, easing = FolioGatherMotion.ReleaseEasing),
                    )
                }
            } else {
                navigateTo(WelcomePageId.Lyrics)
            }
        } else if (currentPageId !in pages) {
            currentPageId = WelcomePageId.Welcome
        }
    }
    val currentIndex = pages.indexOf(currentPageId).coerceAtLeast(0)

    fun finishWelcome() = startClosingSequence()

    fun handleSkip() {
        welcomePrefs.welcomeVersion = CURRENT_WELCOME_VERSION
        if (currentPageId == WelcomePageId.Preferences) {
            startClosingSequence()
        } else {
            navigateTo(WelcomePageId.Preferences)
        }
    }

    BackHandler(enabled = !isClosing) {
        if (introActive) {
            finishIntro(true)
        } else if (currentIndex > 0) {
            navigateTo(pages[currentIndex - 1])
        } else {
            handleSkip()
        }
    }

    // Active showcase track palette colors for album-derived Aura on demo pages
    val showcaseTracks = demoContent.showcaseTracks
    val gaplessActive = currentPageId == WelcomePageId.Gapless
    val showcaseTrackIdx = if (gaplessActive && showcaseTracks.isNotEmpty()) gaplessTrackIdx % showcaseTracks.size else 0
    val currentShowcaseTrack = showcaseTracks.getOrNull(showcaseTrackIdx)
    val paletteResult = rememberArtworkPaletteResult(
        url = currentShowcaseTrack?.artworkUrl,
        fallback = currentShowcaseTrack?.palette.orEmpty(),
    )
    val auraTarget = remember(currentPageId, currentShowcaseTrack, paletteResult.colors) {
        resolveWelcomeAuraTarget(currentPageId, if (currentShowcaseTrack == null) emptyList() else paletteResult.colors)
    }

    // Drag gesture state
    var rawDragX by remember { mutableFloatStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    val snapBackDrag = remember { Animatable(0f) }
    val effectiveDragPx = if (isDragging) {
        computeWelcomeDragOffsetPx(rawDragX, currentIndex, pages.size)
    } else {
        snapBackDrag.value
    }

    val latestPages by rememberUpdatedState(pages)
    val latestIndex by rememberUpdatedState(currentIndex)

    val chromeFadeAlpha by animateFloatAsState(
        targetValue = if (isClosing) 0f else 1f,
        animationSpec = tween(if (lowPerformance) FolioGatherMotion.LOW_PERF_FADE_MS else 300),
        label = "welcome-chrome-alpha",
    )
    val pageFadeAlpha by animateFloatAsState(
        targetValue = if (introActive) 0f else 1f,
        animationSpec = tween(if (introSkipped || !canPlayIntro) 0 else 300),
        label = "welcome-ink-handoff",
    )

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayFadeAlpha.value }
            .background(WelcomeWallBackground)
            .pointerInput(isClosing, introActive) {
                if (isClosing || introActive) return@pointerInput
                var velocityTracker = VelocityTracker()
                detectHorizontalDragGestures(
                    onDragStart = {
                        velocityTracker = VelocityTracker()
                        rawDragX = 0f
                        isDragging = true
                        scope.launch { snapBackDrag.snapTo(0f) }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        rawDragX += dragAmount
                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                    },
                    onDragEnd = {
                        val vx = velocityTracker.calculateVelocity().x
                        val offsetNow = computeWelcomeDragOffsetPx(rawDragX, latestIndex, latestPages.size)
                        val delta = resolveWelcomeDragPageDelta(
                            dragX = rawDragX,
                            velocityX = vx,
                            containerWidthPx = size.width.toFloat(),
                            currentIndex = latestIndex,
                            pageCount = latestPages.size,
                        )
                        isDragging = false
                        rawDragX = 0f
                        if (delta != 0) {
                            navigateTo(latestPages[latestIndex + delta])
                        } else {
                            scope.launch {
                                snapBackDrag.snapTo(offsetNow)
                                snapBackDrag.animateTo(
                                    0f,
                                    if (lowPerformance) tween(160) else spring(0.84f, 400f),
                                )
                            }
                        }
                    },
                    onDragCancel = {
                        isDragging = false
                        rawDragX = 0f
                        scope.launch { snapBackDrag.snapTo(0f) }
                    },
                )
            },
    ) {
        val isPhonePortrait = LocalFolioTierSpec.current.tier == FolioTier.Narrow || maxHeight > maxWidth
        val u = maxWidth / 100f
        val uPx = with(density) { u.toPx() }

        val frostCapture = if (introActive) rememberWelcomeFrostCapture() else null
        WelcomeCoverWall(
            rows = demoContent.wallRows,
            isPhonePortrait = isPhonePortrait,
            pageId = currentPageId,
            dragOffsetPx = effectiveDragPx,
            animated = animated,
            lowPerformance = lowPerformance,
            frostCapture = frostCapture,
        )
        if (frostCapture != null) {
            WelcomeAcrylic(capture = frostCapture, onFinished = ::finishIntro)
            return@BoxWithConstraints
        }

        // Dark radial veil matching H5 `.veil` / `.phone .veil`
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = pageFadeAlpha }
                .background(
                    if (isPhonePortrait) {
                        Brush.verticalGradient(
                            0f to Color(0xFF08070A).copy(alpha = 0.25f),
                            0.55f to Color(0xFF08070A).copy(alpha = 0.86f),
                            1f to Color(0xFF08070A).copy(alpha = 0.97f),
                        )
                    } else {
                        Brush.verticalGradient(
                            0f to Color(0xFF08070A).copy(alpha = 0.92f),
                            0.22f to Color(0xFF08070A).copy(alpha = 0.50f),
                            1f to Color(0xFF08070A).copy(alpha = 0.88f),
                        )
                    },
                ),
        )

        WelcomeRibbonLayer(
            target = auraTarget,
            motionEnabled = isWelcomeAuraMotionEnabled(animated, lowPerformance),
            open = open,
            dragOffsetPx = effectiveDragPx,
            gatherScale = auraGatherScale.value,
            ridgeAlpha = ridgeGatherAlpha.value,
            modifier = Modifier.zIndex(2f).graphicsLayer { alpha = pageFadeAlpha },
        )

        // 2. Top chrome: Brand mark + Sound button + Skip button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = u * (if (isPhonePortrait) 5f else 3.6f),
                    end = u * (if (isPhonePortrait) 5f else 3.6f),
                    top = statusTop + u * (if (isPhonePortrait) 4.2f else 2.2f),
                )
                .graphicsLayer { alpha = chromeFadeAlpha * pageFadeAlpha }
                .zIndex(6f),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.welcome_brand),
                color = Color.White.copy(alpha = 0.82f),
                fontFamily = LocalFolioFontFamily.current,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (isPhonePortrait) (u.value * 3.2f).sp else (u.value * 1.35f).sp,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(u * (if (isPhonePortrait) 2.5f else 1.8f)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val icoSize = if (isPhonePortrait) (u * 9f).coerceAtLeast(36.dp) else (u * 3.8f).coerceAtLeast(38.dp)
                val soundCd = stringResource(R.string.welcome_sound_cd)
                Box(
                    modifier = Modifier
                        .size(icoSize)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.11f))
                        .clickable(role = Role.Button) { bgm.toggleMute(isAppPlaying) }
                        .semantics { contentDescription = soundCd },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = if (bgm.isMutedUi) {
                            Icons.AutoMirrored.Rounded.VolumeOff
                        } else {
                            Icons.AutoMirrored.Rounded.VolumeUp
                        },
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(icoSize * 0.48f),
                    )
                }
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable(role = Role.Button, onClick = ::handleSkip)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.welcome_skip),
                        color = Color.White.copy(alpha = 0.72f),
                        fontFamily = LocalFolioFontFamily.current,
                        fontSize = if (isPhonePortrait) (u.value * 3.5f).sp else (u.value * 1.4f).sp,
                    )
                }
            }
        }

        // 3. Single Now Playing preview, measured at the full player size.
        val screenSize = IntSize(constraints.maxWidth, constraints.maxHeight)
        val stageTarget = stageTargetFor(currentPageId, isPhonePortrait)
        val stageMoveSpec = if (lowPerformance || !animated) {
            tween<Float>(180)
        } else {
            spring(dampingRatio = 0.84f, stiffness = 280f)
        }
        val stageX by animateFloatAsState(stageTarget.xU * uPx, stageMoveSpec, label = "scr-x")
        val stageY by animateFloatAsState(stageTarget.yU * uPx, stageMoveSpec, label = "scr-y")
        val stageScale by animateFloatAsState(stageTarget.scale, stageMoveSpec, label = "scr-s")
        val stageAlpha by animateFloatAsState(
            targetValue = stageTarget.alpha,
            animationSpec = tween(if (lowPerformance) 150 else 420),
            label = "scr-a",
        )
        val stageBaseWidthDp = u * 52f

        val closeT = closingStageProgress.value
        val baseX = if (isDragging) stageTarget.xU * uPx else stageX
        val fullScreenScale = 100f / 52f
        val finalStageX = baseX + (0f - baseX) * closeT + effectiveDragPx * (if (lowPerformance) 0f else 0.45f)
        val finalStageY = stageY * (1f - closeT)
        val finalStageScale = stageScale + (fullScreenScale - stageScale) * closeT
        val finalStageAlpha = stageAlpha * (1f - closeT)

        if (currentShowcaseTrack != null && (finalStageAlpha > 0.005f || (!isClosing && stageTarget.alpha > 0f))) {
            SharedDemoPlayerStage(
                tracks = showcaseTracks,
                activeTrackIdx = showcaseTrackIdx,
                lyricLines = demoContent.lyricLines,
                showLyricsDemo = demoContent.showLyricsDemo,
                style = if (currentPageId == WelcomePageId.Styles) {
                    if (demoPosterMode) LyricBackgroundStyle.Soak else LyricBackgroundStyle.Flow
                } else {
                    settingsState.lyricStyle
                },
                lyricTextSize = settingsState.lyricTextSize,
                gaplessActive = gaplessActive,
                gaplessProgress = gaplessProgress,
                showGaplessTag = gaplessActive && showGaplessTag,
                showLoudnessBars = currentPageId == WelcomePageId.Preferences && showLoudnessBars,
                loudnessMode = settingsState.loudnessMode,
                lowPerformance = lowPerformance,
                reducedMotion = !animated,
                screenSize = screenSize,
                statusTop = statusTop,
                navBottom = navBottom,
                stageWidthDp = stageBaseWidthDp,
                u = u,
                modifier = Modifier
                    .zIndex(3f)
                    .graphicsLayer {
                        transformOrigin = TransformOrigin(0f, 0f)
                        translationX = finalStageX
                        translationY = finalStageY
                        scaleX = finalStageScale
                        scaleY = finalStageScale
                        alpha = finalStageAlpha
                    },
            )
        }

        // 4. Page texts & controls (follow finger on drag, stagger on enter)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = chromeFadeAlpha * pageFadeAlpha }
                .zIndex(4f),
        ) {
            WelcomePageTextsLayer(
                pages = pages,
                currentPageId = currentPageId,
                isPhonePortrait = isPhonePortrait,
                u = u,
                uPx = uPx,
                navBottom = navBottom,
                dragOffsetPx = effectiveDragPx,
                isDragging = isDragging,
                lowPerformance = lowPerformance,
                connectionState = accountState.connectionState,
                demoSetupMessage = services.setupMessage,
                demoPosterMode = demoPosterMode,
                settingsState = settingsState,
                onNext = {
                    val nextIdx = (currentIndex + 1).coerceAtMost(pages.lastIndex)
                    navigateTo(pages[nextIdx])
                },
                onSignInClick = {
                    if (accountState.connectionState == MusicConnectionState.Demo) {
                        services.setupMessage?.let(onNotice)
                    } else {
                        bgm.fadeOutForExternalFlow()
                        onStartSignIn()
                    }
                },
                onSelectDemoStyle = { isPoster ->
                    autoStyleActive = false
                    demoPosterMode = isPoster
                },
                onSelectSettingStyle = settingsStore::setLyricStyle,
                onSelectSettingSize = settingsStore::setLyricTextSize,
                onSelectSettingLoudness = { mode ->
                    settingsStore.setLoudnessMode(mode)
                    loudnessPulse++
                },
                onDone = ::finishWelcome,
            )
        }

        // 5. Bottom page indicator pill + dots
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navBottom + u * (if (isPhonePortrait) 4.2f else 2.0f))
                .graphicsLayer { alpha = chromeFadeAlpha * pageFadeAlpha }
                .zIndex(6f),
            horizontalArrangement = Arrangement.spacedBy(u * (if (isPhonePortrait) 1.5f else 0.6f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val dotHeight = if (isPhonePortrait) (u * 1.4f).coerceAtLeast(5.dp) else (u * 0.55f).coerceAtLeast(5.dp)
            val activeWidth = if (isPhonePortrait) (u * 6f).coerceAtLeast(20.dp) else (u * 2.4f).coerceAtLeast(22.dp)
            pages.forEachIndexed { idx, dotPageId ->
                key(dotPageId) {
                    val active = idx == currentIndex
                    val pillWidth by animateDpAsState(
                        targetValue = if (active) activeWidth else dotHeight,
                        animationSpec = if (lowPerformance) tween(150) else spring(0.8f, 450f),
                        label = "ind-w-$idx",
                    )
                    val dotAlpha by animateFloatAsState(
                        targetValue = if (active) 0.95f else 0.28f,
                        animationSpec = tween(220),
                        label = "ind-a-$idx",
                    )
                    Box(
                        modifier = Modifier
                            .size(width = pillWidth, height = dotHeight)
                            .clip(CircleShape)
                            .background(Color.White.copy(alpha = dotAlpha)),
                    )
                }
            }
        }
    }
}

@Composable
private fun WelcomePageTextsLayer(
    pages: List<WelcomePageId>,
    currentPageId: WelcomePageId,
    isPhonePortrait: Boolean,
    u: Dp,
    uPx: Float,
    navBottom: Dp,
    dragOffsetPx: Float,
    isDragging: Boolean,
    lowPerformance: Boolean,
    connectionState: MusicConnectionState,
    demoSetupMessage: String?,
    demoPosterMode: Boolean,
    settingsState: SettingsState,
    onNext: () -> Unit,
    onSignInClick: () -> Unit,
    onSelectDemoStyle: (Boolean) -> Unit,
    onSelectSettingStyle: (LyricBackgroundStyle) -> Unit,
    onSelectSettingSize: (LyricTextSize) -> Unit,
    onSelectSettingLoudness: (LoudnessMode) -> Unit,
    onDone: () -> Unit,
) {
    val curIdx = pages.indexOf(currentPageId).coerceAtLeast(0)
    val fontFamily = LocalFolioFontFamily.current
    val h1Size = if (isPhonePortrait) (u.value * 13.5f).sp else (u.value * 6.4f).sp
    val h2Size = if (isPhonePortrait) (u.value * 7.2f).sp else (u.value * 3.2f).sp
    val subSize = if (isPhonePortrait) (u.value * 3.8f).sp else (u.value * 1.55f).sp
    val fineSize = if (isPhonePortrait) (u.value * 2.8f).sp else (u.value * 1.1f).sp
    val btnSize = if (isPhonePortrait) (u.value * 3.9f).sp else (u.value * 1.55f).sp

    pages.forEachIndexed { idx, pageId ->
        key(pageId) {
            val isCurrent = pageId == currentPageId
            val baseShiftPx = (if (idx < curIdx) -1f else 1f) * 4f * uPx
            val alpha by animateFloatAsState(
                targetValue = if (isCurrent) 1f else 0f,
                animationSpec = tween(if (lowPerformance) 150 else 320),
                label = "tx-a-${pageId.name}",
            )
            val slideX by animateFloatAsState(
                targetValue = if (isCurrent) 0f else baseShiftPx,
                animationSpec = if (lowPerformance) tween(160) else spring(0.84f, 340f),
                label = "tx-x-${pageId.name}",
            )
            if (isCurrent || alpha >= 0.01f) {

        val alignment: Alignment
        val blockModifier: Modifier
        if (isPhonePortrait) {
            alignment = Alignment.BottomStart
            blockModifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = u * 7f,
                    end = u * 7f,
                    bottom = navBottom + u * (if (pageId == WelcomePageId.Preferences) 12f else 15f),
                )
        } else {
            when (pageId) {
                WelcomePageId.Welcome -> {
                    alignment = Alignment.BottomStart
                    blockModifier = Modifier
                        .width(u * 48f)
                        .padding(start = u * 5.5f, bottom = navBottom + u * 6.5f)
                }
                WelcomePageId.SignIn -> {
                    alignment = Alignment.TopStart
                    blockModifier = Modifier
                        .width(u * 42f)
                        .padding(start = u * 5.5f, top = u * 14f)
                }
                WelcomePageId.Lyrics, WelcomePageId.Styles -> {
                    alignment = Alignment.TopStart
                    blockModifier = Modifier
                        .width(u * 35f)
                        .padding(start = u * 5.5f, top = u * 15f)
                }
                WelcomePageId.Gapless -> {
                    alignment = Alignment.TopEnd
                    blockModifier = Modifier
                        .width(u * 36f)
                        .padding(end = u * 5f, top = u * 15f)
                }
                WelcomePageId.Preferences -> {
                    alignment = Alignment.TopEnd
                    blockModifier = Modifier
                        .width(u * 38f)
                        .padding(end = u * 5f, top = u * 8.6f)
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    this.alpha = alpha
                    translationX = if (isCurrent && isDragging) dragOffsetPx else if (isCurrent) dragOffsetPx else slideX
                },
        ) {
            Column(
                modifier = Modifier
                    .align(alignment)
                    .then(blockModifier),
            ) {
                when (pageId) {
                    WelcomePageId.Welcome -> {
                        WelcomeStaggerItem(isCurrent, 0, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_brand),
                                color = Color.White,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = h1Size,
                                lineHeight = h1Size * 1.04f,
                            )
                        }
                        WelcomeStaggerItem(isCurrent, 1, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p1_sub),
                                color = Color.White.copy(alpha = 0.68f),
                                fontFamily = fontFamily,
                                fontSize = subSize,
                                lineHeight = subSize * 1.55f,
                                modifier = Modifier.padding(top = u * (if (isPhonePortrait) 3f else 1.4f)),
                            )
                        }
                        WelcomeStaggerItem(isCurrent, 2, uPx, lowPerformance) {
                            Row(Modifier.padding(top = u * (if (isPhonePortrait) 6f else 2.8f))) {
                                WelcomePrimaryButton(
                                    label = stringResource(R.string.welcome_p1_cta),
                                    isPhonePortrait = isPhonePortrait,
                                    u = u,
                                    fontSize = btnSize,
                                    enabled = isCurrent,
                                    onClick = onNext,
                                )
                            }
                        }
                        WelcomeStaggerItem(isCurrent, 3, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p1_fine),
                                color = Color.White.copy(alpha = 0.42f),
                                fontFamily = fontFamily,
                                fontSize = fineSize,
                                lineHeight = fineSize * 1.5f,
                                modifier = Modifier.padding(top = u * (if (isPhonePortrait) 4.5f else 2.0f)),
                            )
                        }
                    }

                    WelcomePageId.SignIn -> {
                        WelcomeStaggerItem(isCurrent, 0, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p2_title),
                                color = Color.White,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = h2Size,
                                lineHeight = h2Size * 1.22f,
                            )
                        }
                        WelcomeStaggerItem(isCurrent, 1, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p2_sub),
                                color = Color.White.copy(alpha = 0.68f),
                                fontFamily = fontFamily,
                                fontSize = subSize,
                                lineHeight = subSize * 1.65f,
                                modifier = Modifier.padding(top = u * (if (isPhonePortrait) 3f else 1.4f)),
                            )
                        }
                        if (connectionState == MusicConnectionState.Demo && !demoSetupMessage.isNullOrBlank()) {
                            key("signin-demo-msg") {
                                WelcomeStaggerItem(isCurrent, 2, uPx, lowPerformance) {
                                    Text(
                                        text = demoSetupMessage,
                                        color = Color(0xFFFF8A9B),
                                        fontFamily = fontFamily,
                                        fontSize = fineSize,
                                        lineHeight = fineSize * 1.5f,
                                        modifier = Modifier.padding(top = u * (if (isPhonePortrait) 2.5f else 1.1f)),
                                    )
                                }
                            }
                        }
                        key("signin-acts") {
                            WelcomeStaggerItem(isCurrent, 3, uPx, lowPerformance) {
                            Row(
                                modifier = Modifier.padding(top = u * (if (isPhonePortrait) 6f else 2.8f)),
                                horizontalArrangement = Arrangement.spacedBy(u * (if (isPhonePortrait) 5f else 2.4f)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                WelcomePrimaryButton(
                                    label = stringResource(R.string.welcome_p2_signin),
                                    isPhonePortrait = isPhonePortrait,
                                    u = u,
                                    fontSize = btnSize,
                                    enabled = isCurrent,
                                    onClick = onSignInClick,
                                )
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(999.dp))
                                        .clickable(enabled = isCurrent, role = Role.Button, onClick = onNext)
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                ) {
                                    Text(
                                        text = stringResource(R.string.welcome_p2_later),
                                        color = Color.White.copy(alpha = 0.72f),
                                        fontFamily = fontFamily,
                                        fontSize = btnSize,
                                    )
                                }
                            }
                        }
                        }
                    }

                    WelcomePageId.Lyrics -> {
                        WelcomeStaggerItem(isCurrent, 0, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p3_title),
                                color = Color.White,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = h2Size,
                                lineHeight = h2Size * 1.22f,
                            )
                        }
                        WelcomeStaggerItem(isCurrent, 1, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p3_sub),
                                color = Color.White.copy(alpha = 0.68f),
                                fontFamily = fontFamily,
                                fontSize = subSize,
                                lineHeight = subSize * 1.65f,
                                modifier = Modifier.padding(top = u * (if (isPhonePortrait) 2.8f else 1.4f)),
                            )
                        }
                        WelcomeStaggerItem(isCurrent, 2, uPx, lowPerformance) {
                            Row(Modifier.padding(top = u * (if (isPhonePortrait) 5.5f else 2.8f))) {
                                WelcomePrimaryButton(
                                    label = stringResource(R.string.welcome_next),
                                    isPhonePortrait = isPhonePortrait,
                                    u = u,
                                    fontSize = btnSize,
                                    enabled = isCurrent,
                                    onClick = onNext,
                                )
                            }
                        }
                    }

                    WelcomePageId.Styles -> {
                        WelcomeStaggerItem(isCurrent, 0, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p4_title),
                                color = Color.White,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = h2Size,
                                lineHeight = h2Size * 1.22f,
                            )
                        }
                        WelcomeStaggerItem(isCurrent, 1, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p4_sub),
                                color = Color.White.copy(alpha = 0.68f),
                                fontFamily = fontFamily,
                                fontSize = subSize,
                                lineHeight = subSize * 1.65f,
                                modifier = Modifier.padding(top = u * (if (isPhonePortrait) 2.5f else 1.3f)),
                            )
                        }
                        WelcomeStaggerItem(isCurrent, 2, uPx, lowPerformance) {
                            Box(Modifier.padding(top = u * (if (isPhonePortrait) 4.5f else 2.2f))) {
                                WelcomeSlidingSegment(
                                    options = listOf(
                                        false to stringResource(R.string.welcome_style_flow),
                                        true to stringResource(R.string.welcome_style_poster),
                                    ),
                                    selected = demoPosterMode,
                                    shape = RoundedCornerShape(999.dp),
                                    thumbShape = RoundedCornerShape(999.dp),
                                    equalWidth = false,
                                    lowPerformance = lowPerformance,
                                    u = u,
                                    isPhonePortrait = isPhonePortrait,
                                    enabled = isCurrent,
                                    onSelect = onSelectDemoStyle,
                                )
                            }
                        }
                        WelcomeStaggerItem(isCurrent, 3, uPx, lowPerformance) {
                            Row(Modifier.padding(top = u * (if (isPhonePortrait) 5f else 2.4f))) {
                                WelcomePrimaryButton(
                                    label = stringResource(R.string.welcome_next),
                                    isPhonePortrait = isPhonePortrait,
                                    u = u,
                                    fontSize = btnSize,
                                    enabled = isCurrent,
                                    onClick = onNext,
                                )
                            }
                        }
                    }

                    WelcomePageId.Gapless -> {
                        WelcomeStaggerItem(isCurrent, 0, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p5_title),
                                color = Color.White,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = h2Size,
                                lineHeight = h2Size * 1.22f,
                            )
                        }
                        WelcomeStaggerItem(isCurrent, 1, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p5_sub1),
                                color = Color.White.copy(alpha = 0.68f),
                                fontFamily = fontFamily,
                                fontSize = subSize,
                                lineHeight = subSize * 1.65f,
                                modifier = Modifier.padding(top = u * (if (isPhonePortrait) 2.6f else 1.4f)),
                            )
                        }
                        WelcomeStaggerItem(isCurrent, 2, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p5_sub2),
                                color = Color.White.copy(alpha = 0.68f),
                                fontFamily = fontFamily,
                                fontSize = subSize,
                                lineHeight = subSize * 1.65f,
                                modifier = Modifier.padding(top = u * (if (isPhonePortrait) 2.2f else 1.1f)),
                            )
                        }
                        WelcomeStaggerItem(isCurrent, 3, uPx, lowPerformance) {
                            Row(Modifier.padding(top = u * (if (isPhonePortrait) 5f else 2.6f))) {
                                WelcomePrimaryButton(
                                    label = stringResource(R.string.welcome_next),
                                    isPhonePortrait = isPhonePortrait,
                                    u = u,
                                    fontSize = btnSize,
                                    enabled = isCurrent,
                                    onClick = onNext,
                                )
                            }
                        }
                    }

                    WelcomePageId.Preferences -> {
                        val labelSize = if (isPhonePortrait) (u.value * 3f).sp else (u.value * 1.2f).sp
                        val segShape = RoundedCornerShape(u * (if (isPhonePortrait) 2.4f else 1.1f))
                        val thumbShape = RoundedCornerShape(u * (if (isPhonePortrait) 1.8f else 0.8f))

                        WelcomeStaggerItem(isCurrent, 0, uPx, lowPerformance) {
                            Text(
                                text = stringResource(R.string.welcome_p6_title),
                                color = Color.White,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = h2Size,
                                lineHeight = h2Size * 1.2f,
                            )
                        }
                        WelcomeStaggerItem(isCurrent, 1, uPx, lowPerformance) {
                            Column(Modifier.padding(top = u * (if (isPhonePortrait) 2.8f else 1.4f))) {
                                Text(
                                    text = stringResource(R.string.welcome_opt_style),
                                    color = Color.White.copy(alpha = 0.56f),
                                    fontFamily = fontFamily,
                                    fontSize = labelSize,
                                    modifier = Modifier.padding(bottom = u * (if (isPhonePortrait) 1.2f else 0.6f)),
                                )
                                WelcomeSlidingSegment(
                                    options = listOf(
                                        LyricBackgroundStyle.Flow to stringResource(R.string.welcome_style_flow),
                                        LyricBackgroundStyle.Soak to stringResource(R.string.welcome_style_poster),
                                    ),
                                    selected = settingsState.lyricStyle,
                                    shape = segShape,
                                    thumbShape = thumbShape,
                                    equalWidth = true,
                                    lowPerformance = lowPerformance,
                                    u = u,
                                    isPhonePortrait = isPhonePortrait,
                                    enabled = isCurrent,
                                    onSelect = onSelectSettingStyle,
                                )
                            }
                        }
                        WelcomeStaggerItem(isCurrent, 2, uPx, lowPerformance) {
                            Column(Modifier.padding(top = u * (if (isPhonePortrait) 2.6f else 1.4f))) {
                                Text(
                                    text = stringResource(R.string.welcome_opt_lyric_size),
                                    color = Color.White.copy(alpha = 0.56f),
                                    fontFamily = fontFamily,
                                    fontSize = labelSize,
                                    modifier = Modifier.padding(bottom = u * (if (isPhonePortrait) 1.2f else 0.6f)),
                                )
                                WelcomeSlidingSegment(
                                    options = listOf(
                                        LyricTextSize.Standard to stringResource(R.string.flyme_text_standard),
                                        LyricTextSize.Large to stringResource(R.string.flyme_text_large),
                                        LyricTextSize.ExtraLarge to stringResource(R.string.flyme_text_extra_large),
                                    ),
                                    selected = settingsState.lyricTextSize,
                                    shape = segShape,
                                    thumbShape = thumbShape,
                                    equalWidth = true,
                                    lowPerformance = lowPerformance,
                                    u = u,
                                    isPhonePortrait = isPhonePortrait,
                                    enabled = isCurrent,
                                    onSelect = onSelectSettingSize,
                                )
                            }
                        }
                        WelcomeStaggerItem(isCurrent, 3, uPx, lowPerformance) {
                            val loudnessHint = stringResource(
                                when (settingsState.loudnessMode) {
                                    LoudnessMode.Off -> R.string.welcome_loudness_hint_off
                                    LoudnessMode.Standard -> R.string.welcome_loudness_hint_std
                                    LoudnessMode.Loud -> R.string.welcome_loudness_hint_loud
                                    LoudnessMode.Loudest -> R.string.welcome_loudness_hint_max
                                },
                            )
                            Column(Modifier.padding(top = u * (if (isPhonePortrait) 2.6f else 1.4f))) {
                                Text(
                                    text = stringResource(R.string.welcome_opt_loudness),
                                    color = Color.White.copy(alpha = 0.56f),
                                    fontFamily = fontFamily,
                                    fontSize = labelSize,
                                    modifier = Modifier.padding(bottom = u * (if (isPhonePortrait) 1.2f else 0.6f)),
                                )
                                WelcomeSlidingSegment(
                                    options = listOf(
                                        LoudnessMode.Off to stringResource(R.string.playback_loudness_off),
                                        LoudnessMode.Standard to stringResource(R.string.playback_loudness_standard),
                                        LoudnessMode.Loud to stringResource(R.string.playback_loudness_loud),
                                        LoudnessMode.Loudest to stringResource(R.string.playback_loudness_loudest),
                                    ),
                                    selected = settingsState.loudnessMode,
                                    shape = segShape,
                                    thumbShape = thumbShape,
                                    equalWidth = true,
                                    lowPerformance = lowPerformance,
                                    u = u,
                                    isPhonePortrait = isPhonePortrait,
                                    enabled = isCurrent,
                                    onSelect = onSelectSettingLoudness,
                                )
                                Text(
                                    text = loudnessHint,
                                    color = Color.White.copy(alpha = 0.48f),
                                    fontFamily = fontFamily,
                                    fontSize = fineSize,
                                    modifier = Modifier.padding(top = u * (if (isPhonePortrait) 1.2f else 0.55f)),
                                )
                            }
                        }
                        WelcomeStaggerItem(isCurrent, 4, uPx, lowPerformance) {
                            Row(
                                modifier = Modifier.padding(top = u * (if (isPhonePortrait) 4.2f else 1.8f)),
                                horizontalArrangement = Arrangement.spacedBy(u * (if (isPhonePortrait) 4f else 1.8f)),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                WelcomePrimaryButton(
                                    label = stringResource(R.string.welcome_done),
                                    isPhonePortrait = isPhonePortrait,
                                    u = u,
                                    fontSize = btnSize,
                                    enabled = isCurrent,
                                    onClick = onDone,
                                )
                                Text(
                                    text = stringResource(R.string.welcome_p6_fine),
                                    color = Color.White.copy(alpha = 0.44f),
                                    fontFamily = fontFamily,
                                    fontSize = fineSize,
                                )
                            }
                        }
                    }
                }
            }
        }
            }
        }
    }
}

@Composable
private fun WelcomeStaggerItem(
    visible: Boolean,
    index: Int,
    uPx: Float,
    lowPerformance: Boolean,
    content: @Composable () -> Unit,
) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(visible) {
        if (visible) {
            if (!lowPerformance && index > 0) delay(index * 50L)
            shown = true
        } else {
            shown = false
        }
    }
    val offsetSpec = if (lowPerformance) tween<Float>(160) else spring(0.84f, 360f)
    val dy by animateFloatAsState(if (shown) 0f else 1.6f * uPx, offsetSpec, label = "stagger-y-$index")
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(if (lowPerformance) 150 else 360), label = "stagger-a-$index")

    Box(
        Modifier.graphicsLayer {
            translationY = dy
            this.alpha = alpha
        },
    ) {
        content()
    }
}

@Composable
private fun WelcomePrimaryButton(
    label: String,
    isPhonePortrait: Boolean,
    u: Dp,
    fontSize: TextUnit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val minH = if (isPhonePortrait) (u * 11.5f).coerceAtLeast(44.dp) else (u * 4.5f).coerceAtLeast(42.dp)
    val padX = if (isPhonePortrait) (u * 6.5f).coerceAtLeast(24.dp) else (u * 3.0f).coerceAtLeast(26.dp)
    val shape = RoundedCornerShape(if (isPhonePortrait) u * 2.8f else u * 1.1f)
    Box(
        modifier = Modifier
            .height(minH)
            .clip(shape)
            .background(Color.White.copy(alpha = if (enabled) 1f else 0.45f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = padX),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = Color(0xFF0B0A0E),
            fontFamily = LocalFolioFontFamily.current,
            fontWeight = FontWeight.SemiBold,
            fontSize = fontSize,
        )
    }
}

@Composable
private fun <T> WelcomeSlidingSegment(
    options: List<Pair<T, String>>,
    selected: T,
    shape: Shape,
    thumbShape: Shape,
    equalWidth: Boolean,
    lowPerformance: Boolean,
    u: Dp,
    isPhonePortrait: Boolean,
    enabled: Boolean,
    onSelect: (T) -> Unit,
) {
    val density = LocalDensity.current
    val boundsByIndex = remember { mutableStateMapOf<Int, Pair<Float, Float>>() }
    val selectedIdx = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val targetBounds = boundsByIndex[selectedIdx]
    val thumbSpec = if (lowPerformance) tween<Float>(160) else spring(0.84f, 420f)
    val thumbX by animateFloatAsState(targetBounds?.first ?: 0f, thumbSpec, label = "seg-x")
    val thumbW by animateFloatAsState(targetBounds?.second ?: 0f, thumbSpec, label = "seg-w")

    val pad = if (isPhonePortrait) (u * 0.9f).coerceAtLeast(3.dp) else (u * 0.4f).coerceAtLeast(3.dp)
    val textSize = if (isPhonePortrait) (u.value * 3.3f).sp else (u.value * 1.38f).sp
    val itemPadX = if (isPhonePortrait) (u * 4.5f).coerceAtLeast(14.dp) else (u * 2.0f).coerceAtLeast(14.dp)
    val itemHeight = if (isPhonePortrait) (u * 8.6f).coerceAtLeast(34.dp) else (u * 3.7f).coerceAtLeast(34.dp)

    Box(
        modifier = Modifier
            .then(if (equalWidth) Modifier.fillMaxWidth() else Modifier)
            .clip(shape)
            .background(Color.White.copy(alpha = 0.09f))
            .padding(pad),
    ) {
        if (thumbW > 1f) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(thumbX.roundToInt(), 0) }
                    .width(with(density) { thumbW.toDp() })
                    .height(itemHeight)
                    .clip(thumbShape)
                    .background(Color.White),
            )
        }
        Row(
            modifier = if (equalWidth) Modifier.fillMaxWidth() else Modifier,
            horizontalArrangement = Arrangement.spacedBy(pad),
        ) {
            options.forEachIndexed { idx, (value, label) ->
                key(idx) {
                    val isSelected = idx == selectedIdx
                    Box(
                        modifier = Modifier
                            .then(if (equalWidth) Modifier.weight(1f) else Modifier)
                            .height(itemHeight)
                            .onGloballyPositioned { coords ->
                                boundsByIndex[idx] = coords.positionInParent().x to coords.size.width.toFloat()
                            }
                            .clip(thumbShape)
                            .clickable(
                                enabled = enabled,
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                role = Role.RadioButton,
                                onClick = { onSelect(value) },
                            )
                            .then(if (equalWidth) Modifier else Modifier.padding(horizontal = itemPadX)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = label,
                            color = if (isSelected) Color(0xFF0B0A0E) else Color.White.copy(alpha = 0.68f),
                            fontFamily = LocalFolioFontFamily.current,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = textSize,
                        )
                    }
                }
            }
        }
    }
}
