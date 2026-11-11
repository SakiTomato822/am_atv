package dev.amatv.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.model.LyricTimeline
import dev.amatv.model.Track

/**
 * Portrait lyrics. The scrolling list follows Apple Music's phone lyrics page: a small cover and title
 * on top, lyrics filling the screen, controls only after a tap. The poster mode shows the current line
 * as large type with the controls always present. Both use the car page's background style.
 */
@Composable
internal fun PortraitLyrics(
    track: Track,
    soak: Boolean,
    poster: Boolean,
    position: State<Long>,
    offsetMs: Long,
    playWhenReady: Boolean,
    controlsVisible: Boolean,
    visible: Boolean,
    lowPerformance: Boolean,
    canSeek: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    translation: Boolean,
    fontScale: Float,
    onReveal: () -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    onTranslate: () -> Unit,
    onPosterChange: (Boolean) -> Unit,
    onSeek: (Long) -> Unit,
    emptyContent: @Composable () -> Unit,
) {
    val palette = rememberArtworkPalette(track.artworkUrl, track.palette)
    val textScale = carTextScale(fontScale)
    val pullDown = rememberPullDownToClose(onBack)
    val noLyrics = track.lyrics.isEmpty()
    Box(Modifier.fillMaxSize().automationId(if (poster) "portrait-lyrics-poster" else "portrait-lyrics-list")
        .then(pullDown.layer)
        .pointerInput(poster) { detectTapGestures { if (!poster) onReveal() } }) {
        if (soak) {
            val base = lerp(palette.first(), Color.Black, 0.55f)
            Box(Modifier.fillMaxSize().background(base))
            Box(Modifier.fillMaxWidth().fillMaxHeight(if (poster) 0.62f else 0.48f)) {
                MotionArtwork(track, Modifier.fillMaxSize(), animate = false, cornerRadius = 0.dp)
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.50f), 0.16f to Color.Black.copy(alpha = 0.12f), 0.30f to Color.Transparent,
                    0.55f to base.copy(alpha = 0.35f),
                    0.80f to base.copy(alpha = 0.80f), 1f to base,
                )))
            }
            SoakGlow(palette)
        } else {
            CarLyricFlow(track, palette, visible && !lowPerformance)
        }
        // The header is the handle for pull-down-to-close; the lyrics below it keep their own drags.
        // A page with no lyrics has nothing to scroll, so the whole page is the handle.
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
            .then(if (noLyrics) pullDown.handle else Modifier)) {
            if (noLyrics) {
                FlymeGlyphButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.browse_back), onBack,
                    Modifier.padding(start = 8.dp, top = 20.dp).automationId("portrait-lyrics-back"),
                    size = 44.dp, iconSize = 30.dp, tint = Color.White)
            } else {
                PortraitHeader(track, showCover = !soak || poster, poster, onPosterChange, onBack, pullDown.handle)
            }
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                // Over the Soak cover the list starts where the cover has faded, so no line sits on bright art.
                val listTop = if (soak) maxHeight * 0.34f else 0.dp
                when {
                    // No lyrics: the cover fills the page, Apple Music style, with the loading or
                    // unavailable note under it.
                    noLyrics -> Column(Modifier.fillMaxSize().padding(horizontal = 28.dp),
                        verticalArrangement = Arrangement.Center) {
                        MotionArtwork(track, Modifier.fillMaxWidth().aspectRatio(1f)
                            .shadow(18.dp, RoundedCornerShape(12.dp)), animate = visible && !lowPerformance,
                            cornerRadius = 12.dp)
                        Text(track.title, Modifier.padding(top = 28.dp), color = Color.White, fontSize = 26.sp,
                            lineHeight = 31.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, Modifier.padding(top = 4.dp), color = Color.White.copy(alpha = 0.66f),
                            fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // The loading or "no lyrics" note, kept small under the title.
                        Box(Modifier.fillMaxWidth().height(40.dp).padding(top = 8.dp)) {
                            emptyContent()
                        }
                    }
                    poster -> PortraitPoster(track, position, offsetMs, translation, textScale)
                    else -> Box(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = listTop)) {
                        CarLyricRows(track.lyrics, position, offsetMs, soften = !soak && !lowPerformance,
                            ripple = !soak && !lowPerformance, controlsVisible, canSeek, textScale * 28f / 34f,
                            anchorFraction = 0.24f, onReveal = onReveal) { time ->
                            onReveal()
                            onSeek((time - offsetMs).coerceAtLeast(0L))
                        }
                    }
                }
            }
            if (poster || noLyrics) {
                PortraitControls(position, track.durationMs, playWhenReady, canSeek, canSkipPrevious, canSkipNext,
                    translation, onPlayPause, onPrevious, onNext, onQueue, onTranslate, onSeek)
            } else {
                // Hidden controls leave only a hairline of progress, like the landscape page.
                Box(Modifier.fillMaxWidth()) {
                    androidx.compose.animation.AnimatedVisibility(!controlsVisible, enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                        PortraitHairline(position, track.durationMs)
                    }
                    androidx.compose.animation.AnimatedVisibility(controlsVisible, enter = fadeIn(tween(300)) + slideInVertically { it / 3 },
                        exit = fadeOut(tween(300)) + slideOutVertically { it / 3 }) {
                        PortraitControls(position, track.durationMs, playWhenReady, canSeek, canSkipPrevious,
                            canSkipNext, translation, onPlayPause, onPrevious, onNext, onQueue, onTranslate, onSeek)
                    }
                }
            }
        }
    }
}

@Composable
private fun PortraitHeader(
    track: Track,
    showCover: Boolean,
    poster: Boolean,
    onPosterChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Large enough to anchor the page, after the H5 portrait Metro board: an 84dp cover beside the title.
    Row(modifier.fillMaxWidth().padding(start = 8.dp, end = 12.dp, top = 20.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        FlymeGlyphButton(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.browse_back), onBack,
            Modifier.automationId("portrait-lyrics-back"), size = 44.dp, iconSize = 30.dp, tint = Color.White)
        Box(Modifier.size(4.dp))
        if (showCover) {
            MotionArtwork(track, Modifier.size(84.dp).shadow(10.dp, RoundedCornerShape(8.dp)), animate = false,
                cornerRadius = 8.dp)
            Box(Modifier.size(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(track.title, color = Color.White, fontSize = 26.sp, lineHeight = 31.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(track.artist, Modifier.padding(top = 2.dp), color = Color.White.copy(alpha = 0.66f), fontSize = 16.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        FlymeGlyphButton(
            if (poster) Icons.Rounded.FormatListBulleted else Icons.Rounded.TextFields,
            stringResource(if (poster) R.string.flyme_lyric_list else R.string.flyme_lyric_poster),
            { onPosterChange(!poster) },
            Modifier.automationId("portrait-lyrics-mode"), size = 48.dp, iconSize = 24.dp, tint = Color.White,
        )
    }
}

/** The current line as poster type, broken at its spaces, with the lines around it small and faint. */
@Composable
private fun PortraitPoster(
    track: Track,
    position: State<Long>,
    offsetMs: Long,
    translation: Boolean,
    textScale: Float,
) {
    val lines = track.lyrics
    val schedule = remember(lines) { LyricTimeline.Schedule(lines) }
    val active by remember(schedule, offsetMs, position) {
        derivedStateOf { schedule.snapshotAt(position.value + offsetMs).anchorLineIndex }
    }
    val current = active.coerceAtLeast(0)
    Column(Modifier.fillMaxSize().padding(start = 24.dp, end = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
        Text(if (active > 0) lines[active - 1].renderedText else "", color = Color.White.copy(alpha = 0.62f),
            fontSize = (22 * textScale).sp, lineHeight = (29 * textScale).sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
        AnimatedContent(current, label = "poster-line", transitionSpec = {
            (fadeIn(tween(450)) + slideInVertically(tween(450)) { it / 4 }) togetherWith fadeOut(tween(200))
        }) { index ->
            val line = lines[index]
            Column {
                Text(posterText(line.renderedText),
                    color = Color.White.copy(alpha = if (active < 0) 0.45f else 1f),
                    fontSize = (46 * textScale).sp, lineHeight = (53 * textScale).sp, fontWeight = FontWeight.Black)
                if (translation) line.translation?.let {
                    Text(it, Modifier.padding(top = 6.dp), color = Color.White.copy(alpha = 0.72f),
                        fontSize = (17 * textScale).sp, lineHeight = (22 * textScale).sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((step, alpha) in listOf(1 to 0.78f, 2 to 0.58f, 3 to 0.42f)) {
                val line = lines.getOrNull(current + step) ?: break
                Text(line.renderedText, color = Color.White.copy(alpha = alpha), fontSize = (24 * textScale).sp,
                    lineHeight = (31 * textScale).sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
            }
        }
    }
}

@Composable
private fun PortraitHairline(position: State<Long>, durationMs: Long) {
    Canvas(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 20.dp, bottom = 28.dp).height(3.dp)) {
        drawRect(Color.White.copy(alpha = 0.16f))
        val fraction = (position.value.toFloat() / durationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        drawRect(Color.White.copy(alpha = 0.85f), size = Size(size.width * fraction, size.height))
    }
}

@Composable
private fun PortraitControls(
    position: State<Long>,
    durationMs: Long,
    playWhenReady: Boolean,
    canSeek: Boolean,
    canSkipPrevious: Boolean,
    canSkipNext: Boolean,
    translation: Boolean,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onQueue: () -> Unit,
    onTranslate: () -> Unit,
    onSeek: (Long) -> Unit,
) {
    LyricControlCard(
        position, durationMs, playWhenReady, canSeek, canSkipPrevious, canSkipNext, translation,
        onPlayPause, onPrevious, onNext, onQueue, onTranslate, onSeek,
        modifier = Modifier.automationId("portrait-lyric-controls").fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
    )
}

private val PhraseScripts = setOf(
    Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA,
    Character.UnicodeScript.KATAKANA, Character.UnicodeScript.HANGUL,
)

/**
 * In Chinese, Japanese and Korean lyrics a space separates phrases, so each phrase gets its own poster
 * line. Elsewhere a space is just a word gap and the line wraps normally.
 */
internal fun posterText(text: String): String {
    val trimmed = text.trim()
    val phrased = trimmed.codePoints().anyMatch { Character.UnicodeScript.of(it) in PhraseScripts }
    return if (phrased) trimmed.replace(Regex(" +"), "\n") else trimmed
}


