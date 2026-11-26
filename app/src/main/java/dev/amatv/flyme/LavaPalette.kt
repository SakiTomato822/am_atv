package dev.amatv.flyme

import dev.amatv.artwork.ArtworkPalette

/** Keeps artwork hues, including neutral covers, while reserving dark space behind the text. */
internal object LavaPalette {
    fun from(colors: IntArray, isLight: Boolean = false, homeRibbon: Boolean = false): FloatArray {
        val source = colors.toList().ifEmpty { listOf(0xFF33333B.toInt()) }
        fun channels(c: Int) = floatArrayOf((c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f)
        fun chroma(c: Int): Float = channels(c).let { it.max() - it.min() }
        // The extractor puts the largest color population first. A tiny saturated detail
        // (for example skin on a blue cover) must not take over the whole background.
        val primary = source.first()
        val secondary = source.maxBy { ArtworkPalette.distance(primary, it) * (chroma(it) + .12f) }
        val darkest = source.minBy { c -> channels(c).let { it[0] * .2126f + it[1] * .7152f + it[2] * .0722f } }
        val ordered = listOf(darkest, primary, secondary, source.getOrElse(2) { primary }, source.getOrElse(3) { secondary })
        return FloatArray(15).also { result ->
            ordered.forEachIndexed { index, color ->
                val rgb = channels(color)
                if (homeRibbon) {
                    // Keep the cover's hue and lift muted covers to a visible saturation. Light: bands
                    // at mid lightness on a near-white base tinted by the hue. Dark: brighter bands on
                    // a deep base of the same hue.
                    val band = if (isLight) {
                        ribbonBand(rgb, lightness = if (index == 1) .58f else .66f)
                    } else {
                        ribbonBand(rgb, lightness = if (index == 1) .46f else .40f)
                    }
                    for (channel in 0..2) {
                        result[index * 3 + channel] = when {
                            index != 0 -> band[channel]
                            isLight -> .90f + band[channel] * .10f
                            else -> band[channel] * .30f
                        }
                    }
                    return@forEachIndexed
                }
                if (isLight) {
                    val gray = rgb[0] * .2126f + rgb[1] * .7152f + rgb[2] * .0722f
                    val lightness = (if (index == 0) .94f else .90f) + gray * .035f
                    for (channel in 0..2) result[index * 3 + channel] = lightness + (rgb[channel] - gray) * .055f
                    return@forEachIndexed
                }
                val high = rgb.max().coerceAtLeast(.001f)
                val target = if (index == 0) high.coerceIn(.12f, .21f) else high.coerceIn(.24f, .49f)
                val scale = target / high
                for (channel in 0..2) result[index * 3 + channel] = (rgb[channel] * scale).coerceIn(0f, 1f)
            }
        }
    }

    /** HSL with the hue kept; near-gray covers stay neutral, any other cover gets at least 45 % saturation. */
    private fun ribbonBand(rgb: FloatArray, lightness: Float): FloatArray {
        val max = rgb.max()
        val min = rgb.min()
        val l = (max + min) / 2f
        val d = max - min
        val s = if (d == 0f) 0f else d / (1f - kotlin.math.abs(2f * l - 1f))
        val h = when {
            d == 0f -> 0f
            max == rgb[0] -> ((rgb[1] - rgb[2]) / d).mod(6f)
            max == rgb[1] -> (rgb[2] - rgb[0]) / d + 2f
            else -> (rgb[0] - rgb[1]) / d + 4f
        }
        val sat = if (s < .05f) s else (s * 1.3f).coerceIn(.45f, .80f)
        val c = (1f - kotlin.math.abs(2f * lightness - 1f)) * sat
        val x = c * (1f - kotlin.math.abs(h.mod(2f) - 1f))
        val m = lightness - c / 2f
        val (r, g, b) = when (h.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return floatArrayOf(r + m, g + m, b + m)
    }
}

/** Rebased color interpolation; a late artwork decode cannot restart a crossfade from black. */
internal class LavaPaletteTransition(initial: FloatArray) {
    private val current = initial.copyOf()
    private val from = initial.copyOf()
    private val target = initial.copyOf()
    private var startNanos = Long.MIN_VALUE
    private val durationNanos = 1_800_000_000L

    fun retarget(next: FloatArray, nowNanos: Long) {
        if (next.contentEquals(target)) return
        sample(nowNanos).copyInto(from)
        next.copyInto(target)
        startNanos = nowNanos
    }

    fun sample(nowNanos: Long): FloatArray {
        val p = if (startNanos == Long.MIN_VALUE) 1f else
            ((nowNanos - startNanos).coerceAtLeast(0L).toDouble() / durationNanos).toFloat().coerceIn(0f, 1f)
        val eased = p * p * (3f - 2f * p)
        for (i in current.indices) current[i] = from[i] + (target[i] - from[i]) * eased
        return current
    }

    fun isChanging(nowNanos: Long): Boolean = startNanos != Long.MIN_VALUE && nowNanos - startNanos < durationNanos
}
