package dev.amatv.model

import androidx.compose.ui.graphics.Color

private val fallbackPalettes = listOf(
    listOf(Color(0xFF38104E), Color(0xFFA41E62), Color(0xFFE17665)),
    listOf(Color(0xFF102E49), Color(0xFF147185), Color(0xFFD3A25E)),
    listOf(Color(0xFF242044), Color(0xFF5B55A7), Color(0xFFEA786D)),
    listOf(Color(0xFF143A32), Color(0xFF378C69), Color(0xFFD6C94D)),
)

internal fun fallbackTrackPalette(id: String): List<Color> =
    fallbackPalettes[(id.hashCode() and Int.MAX_VALUE) % fallbackPalettes.size]
