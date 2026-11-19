package dev.amatv.flyme

import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dev.amatv.R
import dev.amatv.artwork.ArtworkImages
import dev.amatv.model.Track

@Composable
internal fun MotionArtwork(
    track: Track,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    cornerRadius: androidx.compose.ui.unit.Dp = 22.dp,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { ArtworkImages.loader(context) }
    val url = track.motionPreviewUrl ?: track.artworkUrl
    val placeholder = remember(track.id, track.palette) { SyntheticArtworkPainter(track) }
    val request = remember(context, url) {
        ImageRequest.Builder(context).data(url?.takeIf { it.startsWith("https://") }).build()
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(Brush.linearGradient(track.palette)),
    ) {
        AsyncImage(
            model = request,
            imageLoader = imageLoader,
            contentDescription = stringResource(R.string.player_artwork_description, track.title),
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            placeholder = placeholder,
            error = placeholder,
            fallback = placeholder,
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.18f))),
            ),
        )

        AnimatedVisibility(
            visible = animate && track.motionArtworkUrl != null,
            enter = fadeIn(tween(360)),
            exit = fadeOut(tween(220)),
        ) {
            track.motionArtworkUrl?.let { url ->
                EditorialMotionVideo(
                    url = url,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.14f)),
                    ),
                ),
        )
    }
}

private class SyntheticArtworkPainter(private val track: Track) : Painter() {
    override val intrinsicSize = Size.Unspecified
    override fun DrawScope.onDraw() {
        val w = size.width
        val h = size.height
        fun polygon(vararg points: Pair<Float, Float>) = Path().apply {
            points.forEachIndexed { index, (x, y) ->
                if (index == 0) moveTo(x * w, y * h) else lineTo(x * w, y * h)
            }
            close()
        }
        drawRect(
            brush = Brush.linearGradient(
                colors = track.palette,
                start = Offset.Zero,
                end = Offset(w, h),
            ),
        )
        when ((track.id.hashCode() and Int.MAX_VALUE) % 5) {
            0 -> {
                drawPath(
                    polygon(-0.2f to 0.0f, 0.65f to 0.48f, 0.04f to 1.12f),
                    color = track.palette.first().copy(alpha = 0.84f),
                )
                drawPath(
                    polygon(1.18f to -0.14f, 0.66f to 0.48f, 0.0f to 0.02f),
                    color = Color(0xFF25B7B0).copy(alpha = 0.62f),
                )
                drawPath(
                    polygon(1.0f to 1.04f, 0.66f to 0.48f, 0.0f to 1.0f),
                    color = track.palette.last().copy(alpha = 0.58f),
                )
            }
            1 -> {
                drawPath(
                    polygon(0.52f to -0.05f, 0.18f to 0.45f, 0.72f to 0.45f,
                        0.35f to 1.06f, 0.88f to 0.36f, 0.35f to 0.36f),
                    color = Color(0xFFD5F65A).copy(alpha = 0.9f),
                )
                drawPath(
                    polygon(0.0f to 0.58f, 0.38f to 0.58f, 0.0f to 1.0f),
                    color = Color(0xFF2DDBB9).copy(alpha = 0.82f),
                )
            }
            2 -> {
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(Color(0xFFF1A7D0), track.palette[1], Color.Transparent),
                        center = Offset(w * 0.54f, h * 0.42f),
                        radius = w * 0.58f,
                    ),
                    radius = w * 0.62f,
                    center = Offset(w * 0.54f, h * 0.42f),
                )
                drawCircle(
                    color = track.palette.first().copy(alpha = 0.56f),
                    radius = w * 0.27f,
                    center = Offset(w * 0.54f, h * 0.42f),
                )
            }
            3 -> {
                drawCircle(
                    color = Color(0xFFFF5A69).copy(alpha = 0.68f),
                    radius = w * 0.74f,
                    center = Offset(w * 0.65f, h * 0.62f),
                )
                drawCircle(
                    color = track.palette.first().copy(alpha = 0.78f),
                    radius = w * 0.48f,
                    center = Offset(w * 0.65f, h * 0.62f),
                )
                drawCircle(
                    color = Color(0xFFFFC8A9).copy(alpha = 0.36f),
                    radius = w * 0.20f,
                    center = Offset(w * 0.65f, h * 0.62f),
                )
            }
            else -> {
                drawPath(
                    polygon(0.0f to 0.0f, 0.98f to 0.0f, 0.22f to 0.95f),
                    color = track.palette[1].copy(alpha = 0.7f),
                )
                drawPath(
                    polygon(1.0f to 0.18f, 1.0f to 1.0f, 0.1f to 1.0f),
                    color = Color(0xFFB7DA50).copy(alpha = 0.75f),
                )
                drawPath(
                    polygon(0.3f to 0.2f, 0.95f to 0.26f, 0.58f to 0.85f),
                    color = track.palette.first().copy(alpha = 0.5f),
                )
            }
        }
        drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.25f))))
    }
}

@Composable
private fun EditorialMotionVideo(
    url: String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val player = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(MediaItem.fromUri(url))
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            (LayoutInflater.from(viewContext).inflate(
                R.layout.motion_artwork_player,
                null,
                false,
            ) as PlayerView).apply {
                useController = false
                isFocusable = false
                isFocusableInTouchMode = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                setKeepContentOnPlayerReset(true)
                setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                this.player = player
            }
        },
        update = { it.player = player },
    )
}
