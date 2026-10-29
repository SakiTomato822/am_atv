package dev.amatv.ui

import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.os.Build
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.amatv.R

/** Real Android fallback families: Latin from Inter, Han from Noto Sans SC. */
internal object CarFonts {
    @Volatile private var cached: FontFamily? = null
    @Volatile private var cachedTypeface: Typeface? = null
    private val weightedTypefaces = mutableMapOf<Int, Typeface>()

    @Synchronized fun family(resources: Resources): FontFamily {
        cached?.let { return it }
        typeface(resources)
        // FontFamily(Typeface) ignores subsequent Compose weight requests.
        // Give the resolver one real Android font for each supported weight.
        return FontFamily(listOf(400, 500, 600, 700, 800, 900).map {
            CarWeightFont(FontWeight(it))
        }).also { cached = it }
    }

    @Synchronized fun typeface(resources: Resources, weight: Int = 400): Typeface {
        if (weight != 400) return weightedTypefaces.getOrPut(weight) {
            Typeface.create(typeface(resources), weight, false)
        }
        cachedTypeface?.let { return it }
        val typeface = if (Build.VERSION.SDK_INT >= 29) {
            fun nativeFamily(resource: Int, inter: Boolean): android.graphics.fonts.FontFamily {
                fun font(weight: Int): Font = Font.Builder(resources, resource)
                    .setWeight(weight)
                    .setFontVariationSettings(if (inter) "'wght' $weight, 'opsz' 18" else "'wght' $weight")
                    .build()
                return android.graphics.fonts.FontFamily.Builder(font(400)).apply {
                    listOf(500, 600, 700, 800, 900).forEach { addFont(font(it)) }
                }.build()
            }
            Typeface.CustomFallbackBuilder(nativeFamily(R.font.inter_variable, true))
                .addCustomFallback(nativeFamily(R.font.noto_sans_sc_variable, false))
                .setSystemFallback("sans-serif")
                .setStyle(FontStyle(400, FontStyle.FONT_SLANT_UPRIGHT))
                .build()
        } else resources.getFont(R.font.inter_variable)
        return typeface.also { cachedTypeface = it }
    }

    private class CarWeightFont(override val weight: FontWeight) : AndroidFont(
        FontLoadingStrategy.Blocking, Loader, FontVariation.Settings(),
    ) {
        override val style = androidx.compose.ui.text.font.FontStyle.Normal
    }

    private object Loader : AndroidFont.TypefaceLoader {
        override fun loadBlocking(context: android.content.Context, font: AndroidFont): Typeface =
            typeface(context.resources, font.weight.weight)
        override suspend fun awaitLoad(context: android.content.Context, font: AndroidFont): Typeface =
            loadBlocking(context, font)
    }
}
