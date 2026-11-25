package dev.amatv.flyme

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.amatv.R
import dev.amatv.core.AppearanceMode
import dev.amatv.core.SettingsState
import kotlinx.coroutines.delay
import java.util.Calendar

/** Motion durations (0.2s fast interactions, 0.6s slow transitions) matching H5. */
object FolioMotion {
    const val FAST_MS: Int = 200
    const val SLOW_MS: Int = 600
    val PageEasing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
}

/** Shared "gather & release" choreography curves/timings across Welcome (W1 v4) and Player track switch (G2). */
internal object FolioGatherMotion {
    val GatherEasing = CubicBezierEasing(0.5f, 0.0f, 0.75f, 0.0f)
    val ReleaseEasing = CubicBezierEasing(0.2f, 0.7f, 0.2f, 1.0f)

    const val TRACK_GATHER_MS: Int = 240
    const val TRACK_RELEASE_MS: Int = 460
    const val TRACK_COVER_GATHER_SCALE: Float = 0.955f
    const val TRACK_COVER_GATHER_ALPHA: Float = 0.82f
    const val TRACK_HALO_GATHER_SCALE: Float = 0.86f
    const val TRACK_HALO_GATHER_ALPHA: Float = 0.30f
    const val TRACK_EDGE_GATHER_MULTIPLIER: Float = 1.45f

    const val WELCOME_AURA_GATHER_MS: Int = 500
    const val WELCOME_AURA_RELEASE_MS: Int = 700
    const val WELCOME_AURA_GATHER_SCALE: Float = 0.80f
    const val WELCOME_RIDGE_BASE_ALPHA: Float = 0.075f
    const val WELCOME_RIDGE_GATHER_ALPHA: Float = 0.20f

    const val LOW_PERF_FADE_MS: Int = 180
}

@Immutable
data class FolioPalette(
    val isLight: Boolean,
    val bg: Color,
    val bg2: Color,
    val fg: Color,
    val fg2: Color,
    val fg3: Color,
    val rb: Color,
    val rb2: Color,
    val glass: Color,
    val glassb: Color,
    val capsuleBg: Color,
    val accent: Color,
    val glowTopRight: Color,
    val glowBottomLeft: Color,
    val danger: Color,
    val glassHighlightTop: Color,
    val glassHighlightBottom: Color,
)

val DarkFolioPalette = FolioPalette(
    isLight = false,
    bg = Color(0xFF131016),
    bg2 = Color(0xFF1D1921),
    fg = Color.White,
    fg2 = Color.White.copy(alpha = 0.64f),
    fg3 = Color.White.copy(alpha = 0.40f),
    rb = Color.White.copy(alpha = 0.09f),
    rb2 = Color.White.copy(alpha = 0.17f),
    glass = Color.White.copy(alpha = 0.10f),
    glassb = Color.White.copy(alpha = 0.20f),
    // Android 11 lacks backdrop blur; mix bg (#131016) + ~10% white at ~90% alpha (0xE6) to veil scrolling text.
    capsuleBg = Color(0xFA28252D),
    accent = Color(0xFFFF5C72),
    glowTopRight = Color(0xFF3A1824),
    glowBottomLeft = Color(0xFF10242A),
    danger = Color(0xFFE5484D),
    glassHighlightTop = Color.White.copy(alpha = 0.28f),
    glassHighlightBottom = Color.White.copy(alpha = 0.04f),
)

val LightFolioPalette = FolioPalette(
    isLight = true,
    bg = Color(0xFFF5F3F6),
    bg2 = Color.White,
    fg = Color(0xFF17151C),
    fg2 = Color(0xFF17151C).copy(alpha = 0.62f),
    fg3 = Color(0xFF17151C).copy(alpha = 0.38f),
    rb = Color(0xFF17151C).copy(alpha = 0.06f),
    rb2 = Color(0xFF17151C).copy(alpha = 0.12f),
    glass = Color.White.copy(alpha = 0.66f),
    glassb = Color(0xFF17151C).copy(alpha = 0.10f),
    // Light frosted fallback at ~92% alpha (0xEB) prevents dark list text bleeding through while preserving subtle tint.
    capsuleBg = Color(0xFAFBF9FC),
    accent = Color(0xFFC8243F),
    glowTopRight = Color(0xFFF7DDE3),
    glowBottomLeft = Color(0xFFDDEEF0),
    danger = Color(0xFFE5484D),
    glassHighlightTop = Color.White.copy(alpha = 0.85f),
    glassHighlightBottom = Color.White.copy(alpha = 0.20f),
)

object FolioRadii {
    val Card = 10.dp
    val ListCover = 8.dp
    val Row = 10.dp
    val SongCover = 6.dp
    val CapsuleCover = 12.dp
    val Capsule = 999.dp
    val Chip = 999.dp
    val PillContainer = 10.dp
    val PillThumb = 7.dp
    val Tag = 4.dp
    val NavButton = 12.dp
}

internal val LocalFolioPalette = staticCompositionLocalOf { DarkFolioPalette }
internal val LocalFolioFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.SansSerif }

@Composable
fun rememberFolioIsLight(settings: SettingsState): Boolean {
    val systemDark = isSystemInDarkTheme()
    var minuteOfDay by remember { mutableIntStateOf(currentMinuteOfDay()) }
    LaunchedEffect(settings.appearance) {
        while (settings.appearance == AppearanceMode.Time) {
            minuteOfDay = currentMinuteOfDay()
            delay(30_000L)
        }
    }
    return settings.isLight(systemDark, minuteOfDay)
}

private fun currentMinuteOfDay(): Int = Calendar.getInstance().let {
    it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE)
}

@Composable
fun FolioTheme(
    isLight: Boolean,
    tierSpec: FolioTierSpec,
    lowPerformanceMode: Boolean,
    content: @Composable () -> Unit,
) {
    val target = if (isLight) LightFolioPalette else DarkFolioPalette
    val duration = if (lowPerformanceMode) 10 else FolioMotion.SLOW_MS
    val bg by animateColorAsState(target.bg, tween(duration), label = "folio-bg")
    val bg2 by animateColorAsState(target.bg2, tween(duration), label = "folio-bg2")
    val fg by animateColorAsState(target.fg, tween(duration), label = "folio-fg")
    val fg2 by animateColorAsState(target.fg2, tween(duration), label = "folio-fg2")
    val fg3 by animateColorAsState(target.fg3, tween(duration), label = "folio-fg3")
    val rb by animateColorAsState(target.rb, tween(duration), label = "folio-rb")
    val rb2 by animateColorAsState(target.rb2, tween(duration), label = "folio-rb2")
    val glass by animateColorAsState(target.glass, tween(duration), label = "folio-glass")
    val glassb by animateColorAsState(target.glassb, tween(duration), label = "folio-glassb")
    val capsuleBg by animateColorAsState(target.capsuleBg, tween(duration), label = "folio-capsule-bg")
    val accent by animateColorAsState(target.accent, tween(duration), label = "folio-accent")
    val glowTopRight by animateColorAsState(target.glowTopRight, tween(duration), label = "folio-glow-tr")
    val glowBottomLeft by animateColorAsState(target.glowBottomLeft, tween(duration), label = "folio-glow-bl")

    val palette = target.copy(
        bg = bg,
        bg2 = bg2,
        fg = fg,
        fg2 = fg2,
        fg3 = fg3,
        rb = rb,
        rb2 = rb2,
        glass = glass,
        glassb = glassb,
        capsuleBg = capsuleBg,
        accent = accent,
        glowTopRight = glowTopRight,
        glowBottomLeft = glowBottomLeft,
    )

    val resources = LocalContext.current.resources
    val prewarmed = FolioFonts.prewarmed.collectAsState().value
    val fontFamily = remember(resources, prewarmed) { FolioFonts.family(resources, prewarmed) }
    val baseTypography = MaterialTheme.typography
    val typography = remember(baseTypography, fontFamily) {
        Typography(
            displayLarge = baseTypography.displayLarge.copy(fontFamily = fontFamily),
            displayMedium = baseTypography.displayMedium.copy(fontFamily = fontFamily),
            displaySmall = baseTypography.displaySmall.copy(fontFamily = fontFamily),
            headlineLarge = baseTypography.headlineLarge.copy(fontFamily = fontFamily),
            headlineMedium = baseTypography.headlineMedium.copy(fontFamily = fontFamily),
            headlineSmall = baseTypography.headlineSmall.copy(fontFamily = fontFamily),
            titleLarge = baseTypography.titleLarge.copy(fontFamily = fontFamily),
            titleMedium = baseTypography.titleMedium.copy(fontFamily = fontFamily),
            titleSmall = baseTypography.titleSmall.copy(fontFamily = fontFamily),
            bodyLarge = baseTypography.bodyLarge.copy(fontFamily = fontFamily),
            bodyMedium = baseTypography.bodyMedium.copy(fontFamily = fontFamily),
            bodySmall = baseTypography.bodySmall.copy(fontFamily = fontFamily),
            labelLarge = baseTypography.labelLarge.copy(fontFamily = fontFamily),
            labelMedium = baseTypography.labelMedium.copy(fontFamily = fontFamily),
            labelSmall = baseTypography.labelSmall.copy(fontFamily = fontFamily),
        )
    }

    CompositionLocalProvider(
        LocalFolioPalette provides palette,
        LocalFolioTierSpec provides tierSpec,
        LocalFolioFontFamily provides fontFamily,
    ) {
        MaterialTheme(
            typography = typography,
            content = content,
        )
    }
}

/**
 * Dual radial ambient gradients matching `.app` background in `flyme15-app.html`:
 * `radial-gradient(80% 60% at 85% 0%, glowTopRight 0%, transparent 70%)`,
 * `radial-gradient(60% 50% at 0% 100%, glowBottomLeft 0%, transparent 70%)`, `bg`.
 */
internal fun Modifier.folioAmbientBackground(palette: FolioPalette): Modifier = drawBehind {
    drawRect(palette.bg)
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return@drawBehind

    // Top-right warm rose/mauve glow (80% width, 60% height radius scaled to match CSS ellipse, fading by 70%).
    val trOrigin = Offset(w * 0.85f, 0f)
    scale(scaleX = 1f, scaleY = (h * 0.60f) / (w * 0.80f), pivot = trOrigin) {
        val r = w * 0.80f
        drawCircle(
            brush = Brush.radialGradient(
                0f to palette.glowTopRight,
                0.70f to Color.Transparent,
                center = trOrigin,
                radius = r,
            ),
            radius = r,
            center = trOrigin,
        )
    }

    // Bottom-left cool teal glow (60% width, 50% height radius, fading by 70%).
    val blOrigin = Offset(0f, h)
    scale(scaleX = 1f, scaleY = (h * 0.50f) / (w * 0.60f), pivot = blOrigin) {
        val r = w * 0.60f
        drawCircle(
            brush = Brush.radialGradient(
                0f to palette.glowBottomLeft,
                0.70f to Color.Transparent,
                center = blOrigin,
                radius = r,
            ),
            radius = r,
            center = blOrigin,
        )
    }
}

/** Bundles Inter (Latin) + Noto Sans SC (Han) with weight 300..900 so H5 light headings match CSS weight 300. */
internal object FolioFonts {
    private val weights = listOf(300, 400, 500, 600, 700, 800, 900)
    private val ready = MutableStateFlow<FontFamily?>(null)
    val prewarmed = ready.asStateFlow()
    private var initialFamily: FontFamily? = null

    fun prewarm(resources: Resources) {
        ready.value = buildFamily(resources, weights)
    }

    fun family(resources: Resources, prewarmed: FontFamily? = ready.value): FontFamily =
        prewarmed ?: initialFamily ?: buildFamily(resources, listOf(400, 600)).also { initialFamily = it }

    private fun buildFamily(resources: Resources, nativeWeights: List<Int>): FontFamily {
        val base = if (Build.VERSION.SDK_INT >= 29) {
            fun nativeFamily(resource: Int, inter: Boolean): android.graphics.fonts.FontFamily {
                fun font(w: Int): Font = Font.Builder(resources, resource)
                    .setWeight(w)
                    .setFontVariationSettings(if (inter) "'wght' $w, 'opsz' 18" else "'wght' $w")
                    .build()
                return android.graphics.fonts.FontFamily.Builder(font(nativeWeights.first())).apply {
                    nativeWeights.drop(1).forEach { addFont(font(it)) }
                }.build()
            }
            Typeface.CustomFallbackBuilder(nativeFamily(R.font.inter_variable, true))
                .addCustomFallback(nativeFamily(R.font.noto_sans_sc_variable, false))
                .setSystemFallback("sans-serif")
                .setStyle(FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT))
                .build()
        } else {
            resources.getFont(R.font.inter_variable)
        }
        return FontFamily(weights.map { weight -> FolioWeightFont(FontWeight(weight), base) })
    }

    private class FolioWeightFont(override val weight: FontWeight, val base: Typeface) : AndroidFont(
        FontLoadingStrategy.Blocking, Loader, FontVariation.Settings(),
    ) {
        override val style = androidx.compose.ui.text.font.FontStyle.Normal
    }

    private object Loader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: Context, font: AndroidFont): Typeface {
            val weighted = font as FolioWeightFont
            return if (weighted.weight.weight == 400) weighted.base
            else Typeface.create(weighted.base, weighted.weight.weight, false)
        }
        override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface =
            loadBlocking(context, font)
    }
}
