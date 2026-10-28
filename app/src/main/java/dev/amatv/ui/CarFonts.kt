package dev.amatv.ui

import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontStyle
import android.os.Build
import androidx.compose.ui.text.font.FontFamily
import dev.amatv.R

/** Real Android fallback families: Latin from Inter, Han from Noto Sans SC. */
internal object CarFonts {
    @Volatile private var cached: FontFamily? = null
    @Volatile private var cachedTypeface: Typeface? = null

    @Synchronized fun family(resources: Resources): FontFamily {
        cached?.let { return it }
        return FontFamily(typeface(resources)).also { cached = it }
    }

    @Synchronized fun typeface(resources: Resources): Typeface {
        cachedTypeface?.let { return it }
        val typeface = if (Build.VERSION.SDK_INT >= 29) {
            fun nativeFamily(resource: Int, inter: Boolean): android.graphics.fonts.FontFamily {
                fun font(weight: Int): Font = Font.Builder(resources, resource)
                    .setWeight(weight)
                    .setFontVariationSettings(if (inter) "'wght' $weight, 'opsz' 18" else "'wght' $weight")
                    .build()
                return android.graphics.fonts.FontFamily.Builder(font(400)).apply {
                    listOf(500, 600, 700).forEach { addFont(font(it)) }
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
}
