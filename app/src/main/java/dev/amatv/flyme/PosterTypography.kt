package dev.amatv.flyme

/**
 * Poster type size: [base] at least, and a short line grows until its longest row fills the width,
 * up to 1.6x. Latin letters count as a little over half a CJK character.
 */
internal fun posterFontSize(text: String, widthDp: Float, base: Float): Float {
    val longest = text.split('\n').maxOfOrNull { row ->
        row.sumOf { ch -> if (ch.code < 0x2E80) 0.56 else 1.0 }
    }?.toFloat()?.coerceAtLeast(1f) ?: 1f
    return (widthDp * 0.92f / longest).coerceIn(base, base * 1.6f)
}
