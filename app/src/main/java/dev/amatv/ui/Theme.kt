package dev.amatv.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.amatv.BuildConfig
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Color

private val TvColors = darkColorScheme(
    primary = Color(0xFFFA2D55),
    onPrimary = Color.White,
    background = Color(0xFF08070A),
    onBackground = Color.White,
    surface = Color(0xFF17151B),
    onSurface = Color.White,
)

@Composable
fun AmAtvTheme(content: @Composable () -> Unit) {
    val resources = LocalContext.current.resources
    val typography = remember(resources) {
        val base = Typography()
        if (!BuildConfig.FLYME_AUTO) base else {
            val family = CarFonts.family(resources)
            Typography(
                displayLarge = base.displayLarge.copy(fontFamily = family),
                displayMedium = base.displayMedium.copy(fontFamily = family),
                displaySmall = base.displaySmall.copy(fontFamily = family),
                headlineLarge = base.headlineLarge.copy(fontFamily = family),
                headlineMedium = base.headlineMedium.copy(fontFamily = family),
                headlineSmall = base.headlineSmall.copy(fontFamily = family),
                titleLarge = base.titleLarge.copy(fontFamily = family),
                titleMedium = base.titleMedium.copy(fontFamily = family),
                titleSmall = base.titleSmall.copy(fontFamily = family),
                bodyLarge = base.bodyLarge.copy(fontFamily = family, letterSpacing = 0.sp),
                bodyMedium = base.bodyMedium.copy(fontFamily = family),
                bodySmall = base.bodySmall.copy(fontFamily = family),
                labelLarge = base.labelLarge.copy(fontFamily = family),
                labelMedium = base.labelMedium.copy(fontFamily = family),
                labelSmall = base.labelSmall.copy(fontFamily = family),
            )
        }
    }
    MaterialTheme(colorScheme = TvColors, typography = typography) {
        ProvideTextStyle(typography.bodyLarge, content = content)
    }
}

