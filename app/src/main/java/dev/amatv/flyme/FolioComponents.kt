package dev.amatv.flyme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.AmAtvApplication
import dev.amatv.R
import dev.amatv.artwork.rememberArtworkPaletteResult
import dev.amatv.model.CatalogKind
import dev.amatv.model.CatalogOpenAction
import dev.amatv.model.Track
import dev.amatv.model.openAction

@Composable
private fun FolioBadgedTitle(
    track: Track,
    modifier: Modifier,
    centered: Boolean = false,
    title: @Composable (Modifier) -> Unit,
) {
    if (!track.isExplicit) {
        title(modifier)
        return
    }
    val palette = LocalFolioPalette.current
    Row(
        modifier = modifier,
        horizontalArrangement = if (centered) Arrangement.Center else Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        title(Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        Box(
            modifier = Modifier
                .size(14.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(palette.fg3)
                .semantics { contentDescription = "Explicit" },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "E",
                color = palette.bg,
                fontFamily = LocalFolioFontFamily.current,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
            )
        }
    }
}

@Composable
internal fun FolioTrackTitle(
    track: Track,
    color: Color,
    fontSize: TextUnit,
    fontFamily: FontFamily,
    fontWeight: FontWeight,
    maxLines: Int,
    overflow: TextOverflow,
    lineHeight: TextUnit = TextUnit.Unspecified,
) {
    FolioBadgedTitle(track, Modifier) { modifier ->
        Text(
            text = track.title,
            modifier = modifier,
            color = color,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            fontSize = fontSize,
            lineHeight = lineHeight,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

@Composable
internal fun FolioMarqueeTrackTitle(
    track: Track,
    color: Color,
    fontSize: TextUnit,
    fontFamily: FontFamily,
    fontWeight: FontWeight,
    modifier: Modifier,
    lineHeight: TextUnit = TextUnit.Unspecified,
    textAlign: TextAlign? = null,
) {
    FolioBadgedTitle(track, modifier, centered = textAlign == TextAlign.Center) { titleModifier ->
        FolioMarqueeText(
            text = track.title,
            color = color,
            fontFamily = fontFamily,
            fontWeight = fontWeight,
            fontSize = fontSize,
            lineHeight = lineHeight,
            textAlign = textAlign,
            resetKey = track.id,
            modifier = titleModifier,
        )
    }
}

@Composable
internal fun FolioArtwork(
    track: Track,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(FolioRadii.Card),
    animate: Boolean = false,
) {
    Box(modifier.clip(shape)) {
        MotionArtwork(
            track = track,
            modifier = Modifier.fillMaxSize(),
            animate = animate,
            cornerRadius = 0.dp,
        )
    }
}

@Composable
fun FolioSectionTitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current
    Text(
        text = text,
        color = palette.fg,
        fontFamily = fontFamily,
        fontWeight = FontWeight.Light,
        fontSize = tierSpec.secf,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(top = 8.dp, bottom = 10.dp),
    )
}

/** Big-type list row matching `.lst .card` in `flyme15-app.html`. */
@Composable
fun FolioListRowCard(
    item: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    titleMaxLines: Int = 1,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current
    val isArtist = item.catalogKind == CatalogKind.Artist
    val shape = if (isArtist) CircleShape else RoundedCornerShape(FolioRadii.ListCover)
    val origin = rememberFolioCardOrigin(item, shape)
    val subtitle = when {
        item.artist.isNotBlank() -> item.artist
        !item.pickReason.isNullOrBlank() -> item.pickReason
        else -> item.album.ifBlank { folioKindLabel(item.catalogKind) }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FolioRadii.Row))
            .folioBrowsePressable(pressedScale = 0.985f) { origin.open(onClick) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolioArtwork(
            track = item,
            modifier = Modifier.size(tierSpec.lic).folioArtworkOrigin(origin),
            shape = shape,
        )
        Spacer(Modifier.width(16.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            FolioTrackTitle(
                track = item,
                color = palette.fg,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = tierSpec.lif,
                lineHeight = tierSpec.lif * 1.15f,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    color = palette.fg2,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Square or circular cover card matching `.card` in `flyme15-app.html`. */
@Composable
fun FolioGridCard(
    item: Track,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalFolioPalette.current
    val fontFamily = LocalFolioFontFamily.current
    val isArtist = item.catalogKind == CatalogKind.Artist
    val shape = if (isArtist) CircleShape else RoundedCornerShape(FolioRadii.Card)
    val origin = rememberFolioCardOrigin(item, shape)
    val subtitle = when {
        isArtist -> stringResource(R.string.browse_kind_artist)
        item.artist.isNotBlank() -> item.artist
        !item.pickReason.isNullOrBlank() -> item.pickReason
        else -> folioKindLabel(item.catalogKind)
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FolioRadii.Card))
            .folioBrowsePressable { origin.open(onClick) },
    ) {
        FolioArtwork(
            track = item,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f).folioArtworkOrigin(origin),
            shape = shape,
        )
        Spacer(Modifier.height(6.dp))
        FolioTrackTitle(
            track = item,
            color = palette.fg,
            fontFamily = fontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                color = palette.fg2,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 12.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun rememberFolioCardOrigin(item: Track, shape: Shape): FolioArtworkOrigin? =
    when (item.openAction) {
        CatalogOpenAction.Collection, CatalogOpenAction.OpenArtist -> rememberFolioArtworkOrigin(shape, item.id)
        else -> null
    }

private fun FolioArtworkOrigin?.open(onClick: () -> Unit) {
    if (this == null) onClick() else click(onClick)
}

/** Song row matching `.sr` in `flyme15-app.html`. */
@Composable
fun FolioSongRow(
    track: Track,
    index: Int,
    showCover: Boolean,
    showArtist: Boolean,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current
    val titleColor = if (isCurrent) palette.accent else palette.fg

    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = tierSpec.rowh)
            .clip(RoundedCornerShape(FolioRadii.Row))
            .folioBrowsePressable(pressedScale = 0.985f, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (showCover) {
            FolioArtwork(
                track = track,
                modifier = Modifier.size(44.dp),
                shape = RoundedCornerShape(FolioRadii.SongCover),
            )
        } else {
            Box(
                modifier = Modifier.width(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isCurrent) {
                    FolioEqualizerBars(active = isPlaying, color = palette.accent)
                } else {
                    Text(
                        text = (index + 1).toString(),
                        color = palette.fg3,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        style = TextStyle(fontFamily = fontFamily, fontFeatureSettings = "tnum"),
                    )
                }
            }
        }

        Column(Modifier.weight(1f)) {
            FolioTrackTitle(
                track = track,
                color = titleColor,
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if ((showCover || showArtist) && track.artist.isNotBlank()) {
                Text(
                    text = track.artist,
                    color = palette.fg2,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (track.durationMs > 1_000L) {
            Text(
                text = formatFolioDuration(track.durationMs),
                color = palette.fg3,
                fontSize = 13.sp,
                style = TextStyle(fontFamily = fontFamily, fontFeatureSettings = "tnum"),
            )
        }
    }
}

internal const val TOP_MINI_CAPSULE_MAX_WIDTH_DP: Float = 360f
internal const val TOP_MINI_CAPSULE_MIN_WIDTH_DP: Float = 220f
internal const val TOP_MINI_CAPSULE_GAP_FROM_TITLE_DP: Float = 20f
internal const val TOP_MINI_CAPSULE_HEIGHT_DP: Float = 56f

internal data class FolioTopMiniPlayerLayout(
    val showCapsule: Boolean,
    val widthDp: Float,
)

/**
 * Computes whether the top-right landscape mini player renders as a compact capsule or falls back
 * to [FolioMiniPlayerDot], ensuring it never overlaps [pivotRightEdgeDp] inside [windowWidthDp].
 */
internal fun computeTopRightMiniPlayerLayout(
    windowWidthDp: Float,
    pivotRightEdgeDp: Float,
    endPaddingDp: Float,
    gapFromTitleDp: Float = TOP_MINI_CAPSULE_GAP_FROM_TITLE_DP,
    minCapsuleWidthDp: Float = TOP_MINI_CAPSULE_MIN_WIDTH_DP,
    maxCapsuleWidthDp: Float = TOP_MINI_CAPSULE_MAX_WIDTH_DP,
): FolioTopMiniPlayerLayout {
    val availableDp = windowWidthDp - endPaddingDp - gapFromTitleDp - pivotRightEdgeDp
    return if (availableDp < minCapsuleWidthDp) {
        FolioTopMiniPlayerLayout(showCapsule = false, widthDp = 0f)
    } else {
        FolioTopMiniPlayerLayout(
            showCapsule = true,
            widthDp = availableDp.coerceAtMost(maxCapsuleWidthDp),
        )
    }
}

/** Glass mini-player capsule (Narrow/Short/Medium) or circular artwork dot (Wide/Ultrawide). */
@Composable
fun FolioMiniPlayerCapsule(
    track: Track,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pillShape = RoundedCornerShape(FolioRadii.Capsule)
    val coverShape = RoundedCornerShape(FolioRadii.CapsuleCover)
    FolioMiniPlayerPill(
        track = track,
        isPlaying = isPlaying,
        onTogglePlayback = onTogglePlayback,
        onOpenPlayer = onOpenPlayer,
        pillShape = pillShape,
        coverShape = coverShape,
        coverSize = 48.dp,
        titleSize = 16.sp,
        artistSize = 13.sp,
        playButtonSize = 48.dp,
        playIconSize = 22.dp,
        startPad = 8.dp,
        endPad = 6.dp,
        progressVerticalPad = 3.dp,
        modifier = modifier
            .widthIn(max = 520.dp)
            .fillMaxWidth()
            .height(64.dp),
    )
}

/**
 * Compact top-right mini-player capsule used on landscape tiers (`Wide` / `Ultrawide`) when
 * enough space clears the rightmost pivot title (`>= 220dp`), capped at `360dp`.
 */
@Composable
fun FolioTopMiniPlayerCapsule(
    track: Track,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onOpenPlayer: () -> Unit,
    capsuleWidthDp: Dp,
    modifier: Modifier = Modifier,
) {
    FolioMiniPlayerPill(
        track = track,
        isPlaying = isPlaying,
        onTogglePlayback = onTogglePlayback,
        onOpenPlayer = onOpenPlayer,
        pillShape = CircleShape,
        coverShape = CircleShape,
        coverSize = 40.dp,
        titleSize = 14.sp,
        artistSize = 12.sp,
        playButtonSize = 40.dp,
        playIconSize = 20.dp,
        startPad = 8.dp,
        endPad = 8.dp,
        progressVerticalPad = 2.5.dp,
        modifier = modifier
            .width(capsuleWidthDp)
            .height(TOP_MINI_CAPSULE_HEIGHT_DP.dp),
    )
}

@Composable
private fun FolioMiniPlayerPill(
    track: Track,
    isPlaying: Boolean,
    onTogglePlayback: () -> Unit,
    onOpenPlayer: () -> Unit,
    pillShape: Shape,
    coverShape: Shape,
    coverSize: Dp,
    titleSize: TextUnit,
    artistSize: TextUnit,
    playButtonSize: Dp,
    playIconSize: Dp,
    startPad: Dp,
    endPad: Dp,
    progressVerticalPad: Dp,
    modifier: Modifier = Modifier,
) {
    val palette = LocalFolioPalette.current
    val fontFamily = LocalFolioFontFamily.current
    val origin = rememberFolioArtworkOrigin(coverShape)

    val app = LocalContext.current.applicationContext as AmAtvApplication
    val playbackState by app.playbackStore.state.collectAsState()
    val position = rememberFolioPlaybackPositionMs(playbackState, active = true)
    val durationMs = playbackState.snapshot?.durationMs ?: track.durationMs
    val motion = LocalFolioBrowseMotion.current
    val surface = palette.capsuleBg.copy(alpha = 1f)
    val artwork = rememberArtworkPaletteResult(track.artworkUrl, track.palette)
    val accent = rememberFolioControlAccent(
        appleColors = track.artworkColors,
        palette = artwork.extractedColors(track.artworkUrl),
        background = surface,
        neutral = palette.fg,
        animate = motion.spatial,
    )

    Box(
        modifier = modifier
            .clip(pillShape)
            .folioBrowsePressable { origin.open(onOpenPlayer) }
            .folioMiniPlayerGlass(pillShape),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = startPad, end = endPad),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FolioArtwork(
                track = track,
                modifier = Modifier
                    .size(coverSize)
                    .folioArtworkOrigin(origin),
                shape = coverShape,
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(horizontal = 4.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                FolioMarqueeTrackTitle(
                    track = track,
                    color = palette.fg,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = titleSize,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (track.artist.isNotBlank()) {
                    FolioMarqueeText(
                        text = track.artist,
                        color = palette.fg2,
                        fontFamily = fontFamily,
                        fontWeight = FontWeight.Normal,
                        fontSize = artistSize,
                        resetKey = track.id,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Box(
                modifier = Modifier
                    .size(playButtonSize)
                    .clip(CircleShape)
                    .background(accent.value)
                    .folioBrowsePressable(onClick = onTogglePlayback),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = stringResource(if (isPlaying) R.string.player_pause else R.string.player_play),
                    tint = if (accent.value.alpha > 0f) {
                        controlAccentForeground(accent.value.compositeOver(surface))
                    } else palette.fg,
                    modifier = Modifier.size(playIconSize),
                )
            }
        }
        if (durationMs > 0L && playbackState.snapshot?.isLiveStream != true) {
            FolioHairlineProgressBar(
                positionState = position,
                durationMs = durationMs,
                fg = palette.fg,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = progressVerticalPad),
                accent = accent,
            )
        }
    }
}

/** Accented circular cover dot shown in the top-right on Wide and Ultrawide tiers. */
@Composable
fun FolioMiniPlayerDot(
    track: Track,
    onOpenPlayer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val origin = rememberFolioArtworkOrigin(CircleShape)
    Box(
        modifier = modifier
            .size(tierSpec.ib)
            .clip(CircleShape)
            .folioBrowsePressable { origin.open(onOpenPlayer) }
            .folioMiniPlayerGlass(CircleShape)
            .border(1.dp, palette.accent.copy(alpha = 0.55f), CircleShape)
            .padding(4.dp),
    ) {
        FolioArtwork(
            track = track,
            modifier = Modifier.fillMaxSize().folioArtworkOrigin(origin),
            shape = CircleShape,
        )
    }
}

@Composable
fun FolioCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    outlined: Boolean = true,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    Box(
        modifier = modifier
            .size(tierSpec.ib)
            .clip(CircleShape)
            .background(if (outlined) palette.rb else Color.Transparent)
            .then(if (outlined) Modifier.border(1.dp, palette.glassb, CircleShape) else Modifier)
            .folioBrowsePressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = palette.fg,
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
fun FolioChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalFolioPalette.current
    val fontFamily = LocalFolioFontFamily.current
    val bg by animateColorAsState(
        targetValue = if (selected) palette.fg else palette.rb,
        animationSpec = tween(FolioMotion.FAST_MS),
        label = "chip-bg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) palette.bg else palette.fg,
        animationSpec = tween(FolioMotion.FAST_MS),
        label = "chip-fg",
    )
    val shape = RoundedCornerShape(FolioRadii.Chip)
    Box(
        modifier = modifier
            .clip(shape)
            .background(bg)
            .then(if (!selected) Modifier.border(1.dp, palette.glassb, shape) else Modifier)
            .folioBrowsePressable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = fg,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
            maxLines = 1,
        )
    }
}

@Composable
fun FolioSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalFolioPalette.current
    val trackColor by animateColorAsState(
        targetValue = if (checked) palette.accent else palette.rb2,
        animationSpec = tween(FolioMotion.FAST_MS),
        label = "switch-track",
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 20.dp else 0.dp,
        animationSpec = tween(FolioMotion.FAST_MS),
        label = "switch-thumb",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(width = 50.dp, height = 30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(trackColor)
            .clickable(
                interactionSource = interaction,
                indication = null,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(3.dp),
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(24.dp)
                .clip(CircleShape)
                .background(if (checked) Color.White else palette.fg),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> FolioSegmentedPills(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalFolioPalette.current
    val fontFamily = LocalFolioFontFamily.current
    FlowRow(
        modifier = modifier
            .clip(RoundedCornerShape(FolioRadii.PillContainer))
            .background(palette.rb)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(FolioRadii.PillThumb))
                    .then(
                        if (isSelected) {
                            Modifier
                                .shadow(1.dp, RoundedCornerShape(FolioRadii.PillThumb))
                                .background(palette.bg2)
                        } else Modifier,
                    )
                    .clickable(role = Role.RadioButton, onClick = { onSelect(value) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label,
                    color = if (isSelected) palette.fg else palette.fg2,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Normal,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun FolioEqualizerBars(
    active: Boolean,
    color: Color,
) {
    val heights = if (active) {
        val transition = rememberInfiniteTransition(label = "eq")
        val h1 by transition.animateFloat(
            initialValue = 0.3f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(450), RepeatMode.Reverse),
            label = "eq1",
        )
        val h2 by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(tween(520), RepeatMode.Reverse),
            label = "eq2",
        )
        val h3 by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 0.95f,
            animationSpec = infiniteRepeatable(tween(390), RepeatMode.Reverse),
            label = "eq3",
        )
        listOf(h1, h2, h3)
    } else {
        listOf(0.4f, 0.8f, 0.55f)
    }
    Row(
        modifier = Modifier.height(12.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        heights.forEach { frac ->
            Box(
                Modifier
                    .width(3.dp)
                    .fillMaxHeight(frac)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}

/** Computes exact CSS Grid `repeat(auto-fill, minmax(minWidth, 1fr))` column count and renders uniform rows. */
internal fun adaptiveColumnCount(availableWidth: Dp, minItemWidth: Dp, columnGap: Dp): Int {
    if (availableWidth <= minItemWidth) return 1
    return ((availableWidth.value + columnGap.value) / (minItemWidth.value + columnGap.value))
        .toInt()
        .coerceAtLeast(1)
}

@Composable
internal fun <T> FolioAdaptiveGrid(
    items: List<T>,
    minItemWidth: Dp,
    columnGap: Dp,
    rowGap: Dp,
    modifier: Modifier = Modifier,
    itemContent: @Composable (T) -> Unit,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cols = adaptiveColumnCount(maxWidth, minItemWidth, columnGap)
        val rows = items.chunked(cols)
        Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
            rows.forEach { rowItems ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(columnGap),
                ) {
                    for (col in 0 until cols) {
                        val item = rowItems.getOrNull(col)
                        if (item != null) {
                            Box(Modifier.weight(1f)) { itemContent(item) }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun folioKindLabel(kind: CatalogKind): String = stringResource(
    when (kind) {
        CatalogKind.Song -> R.string.browse_kind_song
        CatalogKind.Artist -> R.string.browse_kind_artist
        CatalogKind.Album -> R.string.browse_kind_album
        CatalogKind.Playlist -> R.string.browse_kind_playlist
        CatalogKind.Station -> R.string.browse_kind_station
        CatalogKind.MusicVideo -> R.string.browse_kind_music_video
    },
)

internal fun formatFolioDuration(ms: Long): String {
    val totalSeconds = (ms / 1_000L).coerceAtLeast(0L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
