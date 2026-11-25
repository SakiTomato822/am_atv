package dev.amatv.flyme

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.amatv.artwork.ArtworkImages
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.backdrop.shadow.Shadow
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.model.Track
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val FLYME_PLAYBACK_PREFS: String = "am_atv_flyme_playback"
const val KEY_GLASS_TIER_OVERRIDE: String = "glass_tier_override"

enum class FolioGlassTier {
    FullA,
    BlurB,
    FallbackC,
}

/** Highest tier supported by the running Android API level. */
fun maxSupportedGlassTier(sdkInt: Int): FolioGlassTier = when {
    sdkInt >= Build.VERSION_CODES.TIRAMISU -> FolioGlassTier.FullA
    sdkInt >= Build.VERSION_CODES.S -> FolioGlassTier.BlurB
    else -> FolioGlassTier.FallbackC
}

/**
 * Resolves the active glass rendering tier from Android API level and debug override (`0=auto, 1=A, 2=B, 3=C`).
 * If the forced tier exceeds what the OS supports, falls back to the highest supported tier.
 */
fun resolveFolioGlassTier(
    sdkInt: Int,
    overrideCode: Int = 0,
): FolioGlassTier {
    val maxSupported = maxSupportedGlassTier(sdkInt)
    val requested = when (overrideCode) {
        1 -> FolioGlassTier.FullA
        2 -> FolioGlassTier.BlurB
        3 -> FolioGlassTier.FallbackC
        else -> return maxSupported
    }
    return if (requested.ordinal >= maxSupported.ordinal) requested else maxSupported
}

@Immutable
data class FolioGlassColors(
    val fill: Color,
    val border: Color,
    val highlightTop: Color,
)

/** Shared material surface appearance across tiers A, B, and C. */
fun resolveFolioGlassColors(
    tier: FolioGlassTier,
    isLight: Boolean,
): FolioGlassColors {
    val fill = when (tier) {
        FolioGlassTier.FullA,
        FolioGlassTier.BlurB -> if (isLight) {
            Color.White.copy(alpha = 0.24f)
        } else {
            Color(0xFF1A1621).copy(alpha = 0.35f)
        }

        FolioGlassTier.FallbackC -> if (isLight) {
            Color.White.copy(alpha = 0.55f)
        } else {
            Color(0xFF1A1621).copy(alpha = 0.45f)
        }
    }
    val border = when {
        isLight && tier != FolioGlassTier.FallbackC -> Color(0xFF17151C).copy(alpha = 0.12f)
        isLight -> Color(0xFF17151C).copy(alpha = 0.14f)
        else -> Color.White.copy(alpha = 0.22f)
    }
    val highlightTop = when {
        isLight && tier != FolioGlassTier.FallbackC -> Color.White.copy(alpha = 0.62f)
        isLight -> Color.White.copy(alpha = 0.45f)
        else -> Color.White.copy(alpha = 0.18f)
    }
    return FolioGlassColors(
        fill = fill,
        border = border,
        highlightTop = highlightTop,
    )
}

@Immutable
internal data class FolioGlassEdgeSpec(
    val strokeStart: Color,
    val strokeMid1: Color,
    val strokeMid2: Color,
    val strokeEnd: Color,
    val innerSheenTop: Color,
    val bottomLine: Color,
)

internal fun folioGlassAlbumTint(artworkPalette: List<Color>): Color? {
    val base = artworkPalette.firstOrNull() ?: return null
    return lerp(base.copy(alpha = 1f), Color.White, 0.42f)
}

internal fun resolveFolioGlassEdgeSpec(
    isLight: Boolean,
    albumTint: Color?,
    edgeBrightness: Float = 1f,
    lightBaseBorder: Color = resolveFolioGlassColors(FolioGlassTier.FallbackC, isLight = true).border,
): FolioGlassEdgeSpec {
    val edge = edgeBrightness.coerceAtLeast(0f)
    return if (!isLight) {
        FolioGlassEdgeSpec(
            strokeStart = Color.White.copy(alpha = (0.72f * edge).coerceIn(0f, 1f)),
            strokeMid1 = Color.White.copy(alpha = (0.24f * edge).coerceIn(0f, 1f)),
            strokeMid2 = Color.White.copy(alpha = 0.05f),
            strokeEnd = albumTint?.copy(alpha = (0.55f * edge).coerceIn(0f, 1f)) ?: Color.White.copy(alpha = 0.10f),
            innerSheenTop = Color.White.copy(alpha = (0.065f * edge).coerceIn(0f, 1f)),
            bottomLine = Color.Black.copy(alpha = 0.22f),
        )
    } else {
        FolioGlassEdgeSpec(
            strokeStart = Color.White.copy(alpha = (0.78f * edge).coerceIn(0f, 1f)),
            strokeMid1 = Color.White.copy(alpha = (0.36f * edge).coerceIn(0f, 1f)),
            strokeMid2 = lightBaseBorder.copy(alpha = 0.12f),
            strokeEnd = albumTint?.copy(alpha = (0.48f * edge).coerceIn(0f, 1f)) ?: Color.White.copy(alpha = 0.10f),
            innerSheenTop = Color.White.copy(alpha = (0.09f * edge).coerceIn(0f, 1f)),
            bottomLine = Color.Black.copy(alpha = 0.08f),
        )
    }
}

internal fun folioDirectionalRimBrush(spec: FolioGlassEdgeSpec, size: Size): Brush = Brush.linearGradient(
    0.00f to spec.strokeStart,
    0.24f to spec.strokeMid1,
    0.52f to spec.strokeMid2,
    1.00f to spec.strokeEnd,
    start = Offset.Zero,
    end = Offset(size.width, size.height),
)

internal fun folioDirectionalSheenBrush(spec: FolioGlassEdgeSpec, size: Size): Brush = Brush.linearGradient(
    0.00f to spec.innerSheenTop,
    0.38f to Color.Transparent,
    start = Offset.Zero,
    end = Offset(size.width * 0.34f, size.height * 0.94f),
)

internal fun DrawScope.drawDirectionalGlassEdge(
    outline: Outline,
    outlinePath: Path,
    sheenBrush: Brush,
    rimBrush: Brush,
    bottomLineColor: Color,
    strokePx: Float,
) {
    clipPath(outlinePath) {
        drawOutline(outline = outline, brush = sheenBrush)
        val lineY = (size.height - strokePx * 0.5f).coerceAtLeast(0f)
        drawLine(
            color = bottomLineColor,
            start = Offset(0f, lineY),
            end = Offset(size.width, lineY),
            strokeWidth = strokePx,
        )
    }
    drawOutline(outline = outline, brush = rimBrush, style = Stroke(width = strokePx))
}

internal fun Outline.asPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
}

/** Mutable non-state holder for Tier C layout alignment without triggering Compose recomposition. */
internal class FolioGlassCoordHolder {
    var rootCoords: LayoutCoordinates? = null
    var bleedCoords: LayoutCoordinates? = null
}

private val DefaultEdgeBrightnessState: State<Float> = mutableFloatStateOf(1f)

@Immutable
internal data class FolioGlassEnvironment(
    val tier: FolioGlassTier = FolioGlassTier.FallbackC,
    val layerBackdrop: LayerBackdrop? = null,
    val effectiveStyle: LyricBackgroundStyle = LyricBackgroundStyle.Flow,
    val baseColor: Color = Color(0xFF1F1D26),
    val albumTint: Color? = null,
    val coverLuminance: Float? = null,
    val edgeBrightness: State<Float> = DefaultEdgeBrightnessState,
    val blurredCover: ImageBitmap? = null,
    val isVerticalBleed: Boolean = false,
    val showSoakBleed: Boolean = false,
    val coordHolder: FolioGlassCoordHolder? = null,
)

internal val LocalFolioGlassEnv = compositionLocalOf { FolioGlassEnvironment() }

/**
 * Observes `glass_tier_override` in `am_atv_flyme_playback` (`0 = auto, 1/2/3 = force A/B/C`)
 * and returns the resolved [FolioGlassTier].
 */
@Composable
internal fun rememberFolioGlassTier(): FolioGlassTier {
    val context = LocalContext.current
    val prefs = remember(context) {
        context.applicationContext.getSharedPreferences(FLYME_PLAYBACK_PREFS, Context.MODE_PRIVATE)
    }
    var overrideCode by remember(prefs) {
        mutableIntStateOf(prefs.getInt(KEY_GLASS_TIER_OVERRIDE, 0))
    }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { shared, key ->
            if (key == KEY_GLASS_TIER_OVERRIDE || key == null) {
                overrideCode = shared.getInt(KEY_GLASS_TIER_OVERRIDE, 0)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return remember(overrideCode) {
        resolveFolioGlassTier(Build.VERSION.SDK_INT, overrideCode)
    }
}

/**
 * Provides player-scoped glass rendering context:
 * - Tiers A/B allocate a [LayerBackdrop] capturing ONLY [FolioPlayerBackdrop].
 * - All non-low-performance modes compute a single shared ~64px blurred cover [ImageBitmap] off the
 *   main thread once per song for Tier C glass, Flow backdrop, and frosted track-switch transitions.
 */
@Composable
internal fun ProvideFolioGlassEnvironment(
    track: Track,
    tierSpec: FolioPlayerTierSpec,
    effectiveStyle: LyricBackgroundStyle,
    displayState: FolioPlayerDisplayState,
    baseColor: Color,
    artworkPalette: List<Color> = emptyList(),
    edgeBrightness: State<Float> = DefaultEdgeBrightnessState,
    lowPerformance: Boolean,
    content: @Composable (FolioGlassEnvironment) -> Unit,
) {
    val albumTint = remember(artworkPalette) { folioGlassAlbumTint(artworkPalette) }
    val coverLuminance = remember(artworkPalette) { folioCoverLuminance(artworkPalette) }
    val env = if (lowPerformance) {
        remember(albumTint, coverLuminance, edgeBrightness) {
            FolioGlassEnvironment(
                albumTint = albumTint,
                coverLuminance = coverLuminance,
                edgeBrightness = edgeBrightness,
            )
        }
    } else {
        val glassTier = rememberFolioGlassTier()
        val layerBackdrop = if (glassTier != FolioGlassTier.FallbackC) {
            rememberLayerBackdrop()
        } else {
            null
        }
        val blurredCover = rememberFolioBlurredCoverBitmap(
            artworkUrl = track.artworkUrl,
            active = true,
        )
        val coordHolder = remember(glassTier) {
            if (glassTier == FolioGlassTier.FallbackC) FolioGlassCoordHolder() else null
        }
        val isVerticalBleed = tierSpec.tier == FolioTier.Narrow || tierSpec.tier == FolioTier.Medium
        val showSoakBleed = showSoakArtworkStage(tierSpec.tier, effectiveStyle, displayState)
        remember(
            glassTier,
            layerBackdrop,
            effectiveStyle,
            baseColor,
            albumTint,
            coverLuminance,
            edgeBrightness,
            blurredCover,
            isVerticalBleed,
            showSoakBleed,
            coordHolder,
        ) {
            FolioGlassEnvironment(
                tier = glassTier,
                layerBackdrop = layerBackdrop,
                effectiveStyle = effectiveStyle,
                baseColor = baseColor,
                albumTint = albumTint,
                coverLuminance = coverLuminance,
                edgeBrightness = edgeBrightness,
                blurredCover = blurredCover,
                isVerticalBleed = isVerticalBleed,
                showSoakBleed = showSoakBleed,
                coordHolder = coordHolder,
            )
        }
    }
    CompositionLocalProvider(LocalFolioGlassEnv provides env) {
        content(env)
    }
}

private object FolioBackdropPresets {
    val TierAEffects: BackdropEffectScope.() -> Unit = {
        vibrancy()
        blur(16.dp.toPx())
        val rim = (size.minDimension * 0.22f).coerceAtMost(14.dp.toPx())
        val refraction = (size.minDimension * 0.30f).coerceAtMost(20.dp.toPx())
        lens(
            refractionHeight = rim,
            refractionAmount = refraction,
            depthEffect = false,
            chromaticAberration = false,
        )
    }

    val TierAEffectsLight: BackdropEffectScope.() -> Unit = {
        vibrancy()
        blur(16.dp.toPx())
        val rim = (size.minDimension * 0.28f).coerceAtMost(18.dp.toPx())
        val refraction = (size.minDimension * 0.42f).coerceAtMost(28.dp.toPx())
        lens(
            refractionHeight = rim,
            refractionAmount = refraction,
            depthEffect = false,
            chromaticAberration = false,
        )
    }

    val TierBEffects: BackdropEffectScope.() -> Unit = {
        vibrancy()
        blur(16.dp.toPx())
    }

    val TierAHighlightDark: () -> Highlight = {
        Highlight(
            width = 0.75.dp,
            blurRadius = 0.5.dp,
            alpha = 0.72f,
            style = HighlightStyle.Default(angle = 90f, falloff = 1.2f),
        )
    }

    val TierAHighlightLight: () -> Highlight = {
        Highlight(
            width = 1.dp,
            blurRadius = 0.75.dp,
            alpha = 0.78f,
            style = HighlightStyle.Ambient(intensity = 0.48f),
        )
    }

    val TierBHighlight: () -> Highlight = {
        Highlight(
            width = 0.75.dp,
            blurRadius = 0.5.dp,
            alpha = 0.55f,
            style = HighlightStyle.Plain,
        )
    }

    val TierBHighlightLight: () -> Highlight = {
        Highlight(
            width = 1.dp,
            blurRadius = 0.75.dp,
            alpha = 0.70f,
            style = HighlightStyle.Plain,
        )
    }

    val TierLightShadow: () -> Shadow = {
        Shadow(
            radius = 12.dp,
            offset = DpOffset(0.dp, 3.dp),
            color = Color(0xFF17151C),
            alpha = 0.08f,
        )
    }
}

/**
 * Liquid Glass modifier for Folio 15 player surfaces across 3 OS capability tiers (`FullA` / `BlurB` / `FallbackC`).
 *
 * When [enabled] is false (e.g., hidden controls or inactive button states), no glass drawing or backdrop sampling is attached.
 */
internal fun Modifier.folioPlayerGlass(
    isLight: Boolean,
    shape: Shape = RoundedCornerShape(FolioRadii.Card),
    enabled: Boolean = true,
): Modifier {
    if (!enabled) return this
    return composed {
        if (LocalFolioLowPerformance.current) {
            val fill = if (isLight) Color(0xFFF6F4F9) else Color(0xFF1A1621)
            val border = resolveFolioGlassColors(FolioGlassTier.FallbackC, isLight).border
            return@composed this.clip(shape).background(fill).border(1.dp, border, shape)
        }
        val env = LocalFolioGlassEnv.current
        val colors = remember(env.tier, isLight) { resolveFolioGlassColors(env.tier, isLight) }
        val layerBackdrop = env.layerBackdrop

        if (env.tier != FolioGlassTier.FallbackC && layerBackdrop != null) {
            val shapeProvider = remember(shape) { { shape } }
            val effects = when {
                env.tier == FolioGlassTier.FullA && isLight -> FolioBackdropPresets.TierAEffectsLight
                env.tier == FolioGlassTier.FullA -> FolioBackdropPresets.TierAEffects
                else -> FolioBackdropPresets.TierBEffects
            }
            if (env.tier == FolioGlassTier.FullA) {
                val highlightProvider = remember(isLight) {
                    if (isLight) FolioBackdropPresets.TierAHighlightLight else FolioBackdropPresets.TierAHighlightDark
                }
                val shadowProvider = if (isLight) FolioBackdropPresets.TierLightShadow else null
                val drawSurface = remember(colors, shape, isLight) {
                    { scope: androidx.compose.ui.graphics.drawscope.DrawScope ->
                        with(scope) {
                            val outline = shape.createOutline(size, layoutDirection, this)
                            drawOutline(outline = outline, color = colors.fill)
                            drawOutline(
                                outline = outline,
                                brush = Brush.verticalGradient(
                                    0f to colors.highlightTop,
                                    0.34f to Color.Transparent,
                                ),
                            )
                            if (isLight) {
                                inset(1.dp.toPx()) {
                                    val innerOutline = shape.createOutline(size, layoutDirection, this@with)
                                    drawOutline(
                                        outline = innerOutline,
                                        color = Color.White.copy(alpha = 0.62f),
                                        style = Stroke(width = 1.dp.toPx()),
                                    )
                                }
                            }
                            drawOutline(
                                outline = outline,
                                color = colors.border,
                                style = Stroke(width = 1.dp.toPx()),
                            )
                        }
                    }
                }
                this.drawBackdrop(
                    backdrop = layerBackdrop,
                    shape = shapeProvider,
                    effects = effects,
                    highlight = highlightProvider,
                    shadow = shadowProvider,
                    onDrawSurface = drawSurface,
                )
            } else {
                // BlurB: blur + directional rim light cached per (size, palette/edge) via drawWithCache
                val shadowProvider = if (isLight) FolioBackdropPresets.TierLightShadow else null
                val drawSurface = remember(colors, shape) {
                    { scope: DrawScope ->
                        with(scope) {
                            val outline = shape.createOutline(size, layoutDirection, this)
                            drawOutline(outline = outline, color = colors.fill)
                        }
                    }
                }
                this
                    .drawBackdrop(
                        backdrop = layerBackdrop,
                        shape = shapeProvider,
                        effects = effects,
                        highlight = { null },
                        shadow = shadowProvider,
                        onDrawSurface = drawSurface,
                    )
                    .drawWithCache {
                        val outline = shape.createOutline(size, layoutDirection, this)
                        val outlinePath = outline.asPath()
                        val strokePx = 1.dp.toPx()
                        val albumTint = env.albumTint
                        val borderColor = colors.border
                        var lastBrightness = Float.NaN
                        var cachedSpec: FolioGlassEdgeSpec? = null
                        var cachedSheenBrush: Brush? = null
                        var cachedRimBrush: Brush? = null
                        onDrawWithContent {
                            drawContent()
                            val brightness = env.edgeBrightness.value
                            val spec = if (brightness == lastBrightness && cachedSpec != null) {
                                cachedSpec!!
                            } else {
                                resolveFolioGlassEdgeSpec(
                                    isLight = isLight,
                                    albumTint = albumTint,
                                    edgeBrightness = brightness,
                                    lightBaseBorder = borderColor,
                                ).also { resolved ->
                                    cachedSpec = resolved
                                    cachedSheenBrush = folioDirectionalSheenBrush(resolved, size)
                                    cachedRimBrush = folioDirectionalRimBrush(resolved, size)
                                    lastBrightness = brightness
                                }
                            }
                            drawDirectionalGlassEdge(
                                outline = outline,
                                outlinePath = outlinePath,
                                sheenBrush = cachedSheenBrush!!,
                                rimBrush = cachedRimBrush!!,
                                bottomLineColor = spec.bottomLine,
                                strokePx = strokePx,
                            )
                        }
                    }
            }
        } else {
            val coordHolder = env.coordHolder
            var selfCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
            val placeModifier = if (coordHolder != null) {
                Modifier.onPlaced { coords -> selfCoords = coords }
            } else {
                Modifier
            }
            this
                .clip(shape)
                .then(placeModifier)
                .drawWithCache {
                    val outline = shape.createOutline(size, layoutDirection, this)
                    val outlinePath = outline.asPath()
                    val strokePx = 1.dp.toPx()
                    val albumTint = env.albumTint
                    val borderColor = colors.border
                    val clipOutlinePath = if (outline is Outline.Generic) outline.path else null
                    var lastBrightness = Float.NaN
                    var cachedSpec: FolioGlassEdgeSpec? = null
                    var cachedSheenBrush: Brush? = null
                    var cachedRimBrush: Brush? = null
                    onDrawBehind {
                        val blurred = env.blurredCover
                        val drawBitmapCrop = env.effectiveStyle == LyricBackgroundStyle.Soak &&
                            env.showSoakBleed &&
                            blurred != null &&
                            coordHolder != null

                        if (drawBitmapCrop) {
                            drawOutline(outline = outline, color = env.baseColor)
                            val rootCoords = coordHolder.rootCoords
                            val bleedCoords = coordHolder.bleedCoords
                            val myCoords = selfCoords
                            if (
                                rootCoords != null && rootCoords.isAttached &&
                                bleedCoords != null && bleedCoords.isAttached &&
                                myCoords != null && myCoords.isAttached
                            ) {
                                val glassInRoot = myCoords.positionInWindow() - rootCoords.positionInWindow()
                                val bleedInRoot = bleedCoords.positionInWindow() - rootCoords.positionInWindow()
                                val offsetX = bleedInRoot.x - glassInRoot.x
                                val offsetY = bleedInRoot.y - glassInRoot.y
                                val bleedW = bleedCoords.size.width.toFloat()
                                val bleedH = bleedCoords.size.height.toFloat()
                                val crop = computeCenteredCropRect(
                                    srcWidth = blurred.width.toFloat(),
                                    srcHeight = blurred.height.toFloat(),
                                    dstWidth = bleedW,
                                    dstHeight = bleedH,
                                )

                                val drawAligned = {
                                    clipRect(0f, 0f, size.width, size.height) {
                                        translate(offsetX, offsetY) {
                                            clipRect(0f, 0f, bleedW, bleedH) {
                                                drawImage(
                                                    image = blurred,
                                                    dstOffset = IntOffset(crop[0].roundToInt(), crop[1].roundToInt()),
                                                    dstSize = IntSize(crop[2].roundToInt(), crop[3].roundToInt()),
                                                )
                                                if (env.isVerticalBleed) {
                                                    drawRect(
                                                        brush = Brush.verticalGradient(
                                                            0f to Color.Transparent,
                                                            0.34f to Color.Transparent,
                                                            0.66f to env.baseColor.copy(alpha = 0.56f),
                                                            0.88f to env.baseColor.copy(alpha = 0.92f),
                                                            1f to env.baseColor,
                                                        ),
                                                        size = Size(bleedW, bleedH),
                                                    )
                                                } else {
                                                    drawRect(
                                                        brush = Brush.horizontalGradient(
                                                            0f to Color.Transparent,
                                                            0.40f to Color.Transparent,
                                                            0.64f to env.baseColor.copy(alpha = 0.38f),
                                                            0.84f to env.baseColor.copy(alpha = 0.78f),
                                                            0.95f to env.baseColor.copy(alpha = 0.96f),
                                                            1f to env.baseColor,
                                                        ),
                                                        size = Size(bleedW, bleedH),
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (clipOutlinePath != null) {
                                    clipPath(clipOutlinePath) { drawAligned() }
                                } else {
                                    drawAligned()
                                }
                            }
                        }
                        drawOutline(outline = outline, color = colors.fill)
                        val brightness = env.edgeBrightness.value
                        val spec = if (brightness == lastBrightness && cachedSpec != null) {
                            cachedSpec!!
                        } else {
                            resolveFolioGlassEdgeSpec(
                                isLight = isLight,
                                albumTint = albumTint,
                                edgeBrightness = brightness,
                                lightBaseBorder = borderColor,
                            ).also { resolved ->
                                cachedSpec = resolved
                                cachedSheenBrush = folioDirectionalSheenBrush(resolved, size)
                                cachedRimBrush = folioDirectionalRimBrush(resolved, size)
                                lastBrightness = brightness
                            }
                        }
                        drawDirectionalGlassEdge(
                            outline = outline,
                            outlinePath = outlinePath,
                            sheenBrush = cachedSheenBrush!!,
                            rimBrush = cachedRimBrush!!,
                            bottomLineColor = spec.bottomLine,
                            strokePx = strokePx,
                        )
                    }
                }
        }
    }
}

/**
 * Loads artwork once per track and runs a lightweight offscreen blur on a ~64px bitmap on background threads.
 */
@Composable
internal fun rememberFolioBlurredCoverBitmap(
    artworkUrl: String?,
    active: Boolean,
): ImageBitmap? {
    if (!active || artworkUrl.isNullOrBlank()) return null
    val context = LocalContext.current
    var cachedUrl by remember { mutableStateOf<String?>(null) }
    var cachedBitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(artworkUrl) {
        if (cachedUrl == artworkUrl && cachedBitmap != null) return@LaunchedEffect
        val blurred = withContext(Dispatchers.IO) {
            val loader = ArtworkImages.loader(context)
            val request = ImageRequest.Builder(context)
                .data(artworkUrl)
                .size(64)
                .allowHardware(false)
                .build()
            val drawable = loader.execute(request).image ?: return@withContext null
            val raw = drawable.toBitmap()
            val targetW = 64.coerceAtLeast(1)
            val targetH = ((raw.height.toFloat() / raw.width.coerceAtLeast(1).toFloat()) * targetW)
                .roundToInt()
                .coerceIn(1, 128)
            val scaled = if (raw.width != targetW || raw.height != targetH) {
                Bitmap.createScaledBitmap(raw, targetW, targetH, true)
            } else {
                raw
            }
            val argb = IntArray(scaled.width * scaled.height)
            scaled.getPixels(argb, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            withContext(Dispatchers.Default) {
                stackBlurArgb(argb, scaled.width, scaled.height, radius = 4)
            }
            val out = Bitmap.createBitmap(scaled.width, scaled.height, Bitmap.Config.ARGB_8888)
            out.setPixels(argb, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            out.asImageBitmap()
        }
        cachedUrl = artworkUrl
        cachedBitmap = blurred
    }
    return if (cachedUrl == artworkUrl) cachedBitmap else null
}

/**
 * Computes `[left, top, width, height]` matching `ContentScale.Crop` to center-fill `[dstWidth]x[dstHeight]`.
 */
internal fun computeCenteredCropRect(
    srcWidth: Float,
    srcHeight: Float,
    dstWidth: Float,
    dstHeight: Float,
): FloatArray {
    if (srcWidth <= 0f || srcHeight <= 0f || dstWidth <= 0f || dstHeight <= 0f) {
        return floatArrayOf(0f, 0f, dstWidth.coerceAtLeast(0f), dstHeight.coerceAtLeast(0f))
    }
    val scale = max(dstWidth / srcWidth, dstHeight / srcHeight)
    val scaledW = srcWidth * scale
    val scaledH = srcHeight * scale
    val left = (dstWidth - scaledW) * 0.5f
    val top = (dstHeight - scaledH) * 0.5f
    return floatArrayOf(left, top, scaledW, scaledH)
}

/**
 * Pure Kotlin 2-pass separable box blur for small ~64px cover thumbnails (used on Tier C / API 28–30).
 */
internal fun stackBlurArgb(
    pixels: IntArray,
    width: Int,
    height: Int,
    radius: Int,
): IntArray {
    if (width <= 0 || height <= 0 || radius <= 0 || pixels.size < width * height) return pixels
    val scratch = IntArray(pixels.size)
    // Two box-blur passes yield a smooth bell-shaped approximation of Gaussian blur.
    repeat(2) {
        boxBlurHorizontal(pixels, scratch, width, height, radius)
        boxBlurVertical(scratch, pixels, width, height, radius)
    }
    return pixels
}

private fun boxBlurHorizontal(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val window = radius * 2 + 1
    for (y in 0 until height) {
        val rowOffset = y * width
        var sumA = 0
        var sumR = 0
        var sumG = 0
        var sumB = 0
        for (i in -radius..radius) {
            val c = src[rowOffset + i.coerceIn(0, width - 1)]
            sumA += (c ushr 24) and 0xFF
            sumR += (c ushr 16) and 0xFF
            sumG += (c ushr 8) and 0xFF
            sumB += c and 0xFF
        }
        for (x in 0 until width) {
            dst[rowOffset + x] =
                ((sumA / window) shl 24) or
                    ((sumR / window) shl 16) or
                    ((sumG / window) shl 8) or
                    (sumB / window)
            val removeIdx = (x - radius).coerceIn(0, width - 1)
            val addIdx = (x + radius + 1).coerceIn(0, width - 1)
            val outC = src[rowOffset + removeIdx]
            val inC = src[rowOffset + addIdx]
            sumA += ((inC ushr 24) and 0xFF) - ((outC ushr 24) and 0xFF)
            sumR += ((inC ushr 16) and 0xFF) - ((outC ushr 16) and 0xFF)
            sumG += ((inC ushr 8) and 0xFF) - ((outC ushr 8) and 0xFF)
            sumB += (inC and 0xFF) - (outC and 0xFF)
        }
    }
}

private fun boxBlurVertical(
    src: IntArray,
    dst: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val window = radius * 2 + 1
    for (x in 0 until width) {
        var sumA = 0
        var sumR = 0
        var sumG = 0
        var sumB = 0
        for (i in -radius..radius) {
            val c = src[i.coerceIn(0, height - 1) * width + x]
            sumA += (c ushr 24) and 0xFF
            sumR += (c ushr 16) and 0xFF
            sumG += (c ushr 8) and 0xFF
            sumB += c and 0xFF
        }
        for (y in 0 until height) {
            dst[y * width + x] =
                ((sumA / window) shl 24) or
                    ((sumR / window) shl 16) or
                    ((sumG / window) shl 8) or
                    (sumB / window)
            val removeIdx = (y - radius).coerceIn(0, height - 1) * width + x
            val addIdx = (y + radius + 1).coerceIn(0, height - 1) * width + x
            val outC = src[removeIdx]
            val inC = src[addIdx]
            sumA += ((inC ushr 24) and 0xFF) - ((outC ushr 24) and 0xFF)
            sumR += ((inC ushr 16) and 0xFF) - ((outC ushr 16) and 0xFF)
            sumG += ((inC ushr 8) and 0xFF) - ((outC ushr 8) and 0xFF)
            sumB += (inC and 0xFF) - (outC and 0xFF)
        }
    }
}
