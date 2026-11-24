package dev.amatv.flyme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle

internal val LocalFolioBrowseBackdrop = staticCompositionLocalOf<LayerBackdrop?> { null }

@Composable
internal fun Modifier.folioMiniPlayerGlass(shape: Shape): Modifier {
    val palette = LocalFolioPalette.current
    val backdrop = LocalFolioBrowseBackdrop.current
    val alpha = when {
        backdrop != null -> if (palette.isLight) 0.50f else 0.58f
        palette.isLight -> 0.86f
        else -> 0.82f
    }
    val fill = palette.capsuleBg.copy(alpha = alpha)
    val border = palette.glassb
    val highlight = palette.glassHighlightTop
    val sheen = remember(highlight) {
        Brush.verticalGradient(0f to highlight, 0.34f to Color.Transparent)
    }
    val tier = rememberFolioGlassTier()
    return if (backdrop != null && tier == FolioGlassTier.FullA) {
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { blur(16.dp.toPx()) },
            highlight = {
                Highlight(width = 0.75.dp, blurRadius = 0.5.dp, alpha = 0.55f, style = HighlightStyle.Plain)
            },
            shadow = null,
            onDrawSurface = { drawMiniPlayerSurface(shape, fill, sheen, border) },
        )
    } else if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val edgeSpec = resolveFolioGlassEdgeSpec(isLight = palette.isLight, albumTint = null, lightBaseBorder = border)
        drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = { blur(16.dp.toPx()) },
            highlight = { null },
            shadow = null,
            onDrawSurface = {
                val outline = shape.createOutline(size, layoutDirection, this)
                drawOutline(outline, fill)
            },
        ).drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val outlinePath = outline.asPath()
            val strokePx = 1.dp.toPx()
            val edgeSheen = folioDirectionalSheenBrush(edgeSpec, size)
            val rimBrush = folioDirectionalRimBrush(edgeSpec, size)
            onDrawWithContent {
                drawContent()
                drawDirectionalGlassEdge(outline, outlinePath, edgeSheen, rimBrush, edgeSpec.bottomLine, strokePx)
            }
        }
    } else {
        val edgeSpec = resolveFolioGlassEdgeSpec(isLight = palette.isLight, albumTint = null, lightBaseBorder = border)
        clip(shape).drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val outlinePath = outline.asPath()
            val strokePx = 1.dp.toPx()
            val edgeSheen = folioDirectionalSheenBrush(edgeSpec, size)
            val rimBrush = folioDirectionalRimBrush(edgeSpec, size)
            onDrawBehind {
                drawOutline(outline, fill)
                drawDirectionalGlassEdge(outline, outlinePath, edgeSheen, rimBrush, edgeSpec.bottomLine, strokePx)
            }
        }
    }
}

private fun DrawScope.drawMiniPlayerSurface(shape: Shape, fill: Color, sheen: Brush, border: Color) {
    val outline = shape.createOutline(size, layoutDirection, this)
    drawOutline(outline, fill)
    drawOutline(outline, sheen)
    drawOutline(outline, border, style = Stroke(1.dp.toPx()))
}
