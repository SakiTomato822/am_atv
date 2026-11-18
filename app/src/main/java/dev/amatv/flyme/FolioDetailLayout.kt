package dev.amatv.flyme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.model.CatalogKind
import dev.amatv.model.Track

internal data class FolioDetailLayout(
    val coverSize: Int,
    val titleSize: Int,
    val collectionColumns: Int = 1,
    val buttonsBelowCover: Boolean = false,
    val artistSideBySide: Boolean = false,
    val topSongColumns: Int = 1,
)

internal fun folioDetailLayout(tier: FolioTier): FolioDetailLayout = when (tier) {
    FolioTier.Narrow -> FolioDetailLayout(116, 32, buttonsBelowCover = true)
    FolioTier.Short -> FolioDetailLayout(112, 32, artistSideBySide = true)
    FolioTier.Medium -> FolioDetailLayout(188, 46, topSongColumns = 2)
    FolioTier.Wide -> FolioDetailLayout(220, 60, collectionColumns = 2, artistSideBySide = true, topSongColumns = 2)
    FolioTier.Ultrawide -> FolioDetailLayout(170, 52, collectionColumns = 3, artistSideBySide = true, topSongColumns = 2)
}

/** Balances the columns while keeping reading order down each column, as in H5 CSS columns. */
internal fun folioDetailColumnRanges(count: Int, columns: Int): List<IntRange> {
    val rows = (count + columns - 1) / columns
    return List(columns) { column ->
        val start = column * rows
        start until minOf(start + rows, count)
    }
}

@Composable
internal fun FolioDetailHeader(
    item: Track,
    layout: FolioDetailLayout,
    meta: String?,
    playable: Boolean,
    onPlay: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    byline: (@Composable () -> Unit)? = null,
) {
    val palette = LocalFolioPalette.current
    val font = LocalFolioFontFamily.current
    val shape = if (item.catalogKind == CatalogKind.Artist) CircleShape else RoundedCornerShape(12.dp)
    val actionsBelow = vertical || layout.buttonsBelowCover

    val cover: @Composable () -> Unit = {
        FolioArtwork(item, Modifier.size(layout.coverSize.dp).shadow(12.dp, shape), shape)
    }
    val description: @Composable () -> Unit = {
        Text(
            text = item.title,
            color = palette.fg,
            fontFamily = font,
            fontWeight = FontWeight.Light,
            fontSize = layout.titleSize.sp,
            lineHeight = (layout.titleSize * 1.05f).sp,
            letterSpacing = (-0.5).sp,
        )
        byline?.invoke()
        if (meta != null) FolioDetailLabel(meta)
    }
    Column(
        modifier = modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (vertical) {
            cover()
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { description() }
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(if (layout.buttonsBelowCover) 18.dp else 28.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                cover()
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    description()
                    if (!actionsBelow) {
                        FolioDetailActions(playable, onPlay, Modifier.padding(top = 10.dp).widthIn(max = 380.dp))
                    }
                }
            }
        }
        if (actionsBelow) {
            FolioDetailActions(playable, onPlay, if (vertical) Modifier.widthIn(max = 380.dp) else Modifier)
        }
    }
}

@Composable
private fun FolioDetailActions(enabled: Boolean, onPlay: (Boolean) -> Unit, modifier: Modifier) {
    val palette = LocalFolioPalette.current
    val font = LocalFolioFontFamily.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (shuffle in listOf(false, true)) {
            val background = if (shuffle) palette.rb2 else palette.accent
            val foreground = if (shuffle) palette.fg else Color.White
            Row(
                modifier = Modifier.weight(1f).height(48.dp).clip(CircleShape)
                    .background(background.copy(alpha = background.alpha * if (enabled) 1f else 0.45f))
                    .clickable(enabled = enabled, role = Role.Button) { onPlay(shuffle) }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (!shuffle) Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp), tint = foreground)
                Text(
                    text = stringResource(if (shuffle) R.string.browse_shuffle else R.string.browse_play),
                    color = foreground,
                    fontFamily = font,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
internal fun FolioDetailLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        color = LocalFolioPalette.current.fg2,
        fontFamily = LocalFolioFontFamily.current,
        fontSize = 13.5.sp,
        modifier = modifier,
    )
}
