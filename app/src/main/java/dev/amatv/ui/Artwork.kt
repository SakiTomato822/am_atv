package dev.amatv.ui

import android.view.LayoutInflater
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import coil3.BitmapImage
import coil3.ImageLoader
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.intercept.Interceptor
import coil3.request.CachePolicy
import coil3.compose.AsyncImage
import coil3.compose.asPainter
import coil3.disk.DiskCache
import coil3.decode.BlackholeDecoder
import coil3.memory.MemoryCache
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap
import okio.Path.Companion.toOkioPath
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import android.os.Process
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import dev.amatv.R
import dev.amatv.model.Track
import dev.amatv.integration.ContentCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun MotionArtwork(
    track: Track,
    modifier: Modifier = Modifier,
    animate: Boolean = true,
    cornerRadius: androidx.compose.ui.unit.Dp = 22.dp,
    crossfadeTrackChanges: Boolean = false,
) {
    if (crossfadeTrackChanges) {
        RetainedMotionArtwork(
            requestedTrack = track,
            modifier = modifier,
            animate = animate,
            cornerRadius = cornerRadius,
        )
    } else {
        ArtworkSurface(
            track = track,
            modifier = modifier,
            animate = animate,
            cornerRadius = cornerRadius,
        )
    }
}

/**
 * Owns the decoded images, rather than replacing AsyncImage request trees.
 * The visible image remains opaque while the ready replacement fades over it.
 * A subsequent decode may run during the blend, but cannot cancel it midway.
 */
@Composable
private fun RetainedMotionArtwork(
    requestedTrack: Track,
    modifier: Modifier,
    animate: Boolean,
    cornerRadius: androidx.compose.ui.unit.Dp,
) {
    val context = LocalContext.current
    val imageLoader = remember(context) { ArtworkImages.loader(context) }
    val density = LocalDensity.current
    val requestedKey = requestedTrack.artworkVisualKey()
    var coverState by remember { mutableStateOf("loading") }
    BoxWithConstraints(modifier = modifier
        .automationId("player-artwork")
        .semantics {
            stateDescription = when {
                coverState == "blending" -> "切換中"
                coverState == "ready" -> "已載入"
                else -> "載入中"
            }
        }
        .clip(RoundedCornerShape(cornerRadius))) {
        val imageSize = with(density) { maxWidth.toPx().roundToInt().coerceAtLeast(1) }
        val ready = produceState<DecodedArtwork?>(null, imageLoader, requestedKey, imageSize) {
            val url = requestedTrack.motionPreviewUrl ?: requestedTrack.artworkUrl
            val image = url?.takeIf { it.startsWith("https://") }?.let {
                imageLoader.execute(ImageRequest.Builder(context).data(it).size(imageSize).build()).image
            }
            // Android can upload the decoded bitmap ahead of the first visible
            // draw. Never decode/upload it again just to change fade ownership.
            if (image is BitmapImage) withContext(ArtworkImages.decodeDispatcher) {
                image.bitmap.prepareToDraw()
            }
            value = DecodedArtwork(
                key = requestedKey,
                painter = image?.asPainter(context) ?: SyntheticArtworkPainter(requestedTrack),
                motionUrl = requestedTrack.motionArtworkUrl,
            )
        }
        var displayed by remember { mutableStateOf<DecodedArtwork?>(null) }
        var incoming by remember { mutableStateOf<DecodedArtwork?>(null) }
        val fade = remember { Animatable(0f) }
        val initialPlaceholder = remember { SyntheticArtworkPainter(requestedTrack) }
        LaunchedEffect(ready) {
            snapshotFlow { ready.value }.filterNotNull().collect { next ->
                if (next.key == displayed?.key) return@collect
                if (displayed == null) {
                    displayed = next
                    coverState = "ready"
                } else {
                    // Establish alpha before publishing the new image. The
                    // previous implementation reset it one frame too late.
                    fade.snapTo(0f)
                    incoming = next
                    coverState = "blending"
                    fade.animateTo(1f, tween(360, easing = MotionTokens.KaraokeLineEasing))
                    displayed = next
                    incoming = null
                    coverState = "ready"
                }
            }
        }
        Image(
            painter = displayed?.painter ?: initialPlaceholder,
            contentDescription = "${requestedTrack.title} artwork",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
        incoming?.let { frame ->
            Image(
                painter = frame.painter,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = fade.value },
            )
        }
        // One video surface, only for the currently requested and fully shown
        // artwork. Fading images never create hidden video decoders.
        if (animate && incoming == null && displayed?.key == requestedKey) {
            displayed?.motionUrl?.let { EditorialMotionVideo(it, Modifier.fillMaxSize()) }
        }
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
            listOf(Color.Transparent, Color.Black.copy(alpha = 0.29f)),
        )))
    }
}

private class DecodedArtwork(val key: String, val painter: Painter, val motionUrl: String?)

private fun Track.artworkVisualKey(): String =
    (motionPreviewUrl ?: artworkUrl ?: "synthetic:$id:${palette.hashCode()}") + "|" + motionArtworkUrl.orEmpty()

@Composable
private fun ArtworkSurface(
    track: Track,
    modifier: Modifier,
    animate: Boolean,
    cornerRadius: androidx.compose.ui.unit.Dp,
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
            contentDescription = "${track.title} artwork",
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

/** One loader for thumbnails and palette requests, with bounded background decoding. */
internal object ArtworkImages {
    val decodeDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "AMATV-artwork").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    @Volatile private var shared: ImageLoader? = null

    @OptIn(coil3.annotation.ExperimentalCoilApi::class)
    suspend fun prefetch(context: android.content.Context, url: String) {
        if (!url.startsWith("https://")) return
        val cache = ContentCache.get(context)
        if (!cache.settings.value.let { it.enabled && it.prefetchNextTwo && !it.clearing }) return
        if (!withContext(Dispatchers.IO) { cache.canWrite() }) return
        loader(context).execute(ImageRequest.Builder(context).data(url)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .decoderFactory(BlackholeDecoder.Factory())
            .build())
    }

    suspend fun clear(context: android.content.Context) = withContext(Dispatchers.IO) {
        loader(context).diskCache?.clear()
        loader(context).memoryCache?.clear()
        paletteCache.evictAll()
    }

    fun diskBytes(context: android.content.Context): Long = loader(context).diskCache?.size ?: 0L

    fun loader(context: android.content.Context): ImageLoader = shared ?: synchronized(this) {
        shared ?: ImageLoader.Builder(context.applicationContext)
            .components { add(ArtworkCachePolicy(ContentCache.get(context)) { shared }) }
            .memoryCache {
                MemoryCache.Builder().maxSizeBytes(
                    (Runtime.getRuntime().maxMemory() / 10).coerceIn(12L * 1024 * 1024, 48L * 1024 * 1024),
                ).build()
            }
            .diskCache {
                DiskCache.Builder().directory(context.applicationContext.cacheDir.resolve("artwork").toOkioPath())
                    .maxSizeBytes(ContentCache.get(context).artworkCapacityBytes).build()
            }
            .fetcherCoroutineContext(Dispatchers.IO.limitedParallelism(4))
            .decoderCoroutineContext(decodeDispatcher)
            .build().also { shared = it }
    }
}

/** Check space before every request, not only when the loader was constructed. */
private class ArtworkCachePolicy(
    private val cache: ContentCache,
    private val imageLoader: () -> ImageLoader?,
) : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult = withContext(Dispatchers.IO) {
        val generation = cache.generation
        val settings = cache.settings.value
        val policy = when {
            !settings.enabled || settings.clearing -> CachePolicy.DISABLED
            !cache.canWrite() -> if (chain.request.diskCachePolicy.readEnabled) CachePolicy.READ_ONLY else CachePolicy.DISABLED
            else -> chain.request.diskCachePolicy
        }
        val result = chain.withRequest(chain.request.newBuilder().diskCachePolicy(policy).build()).proceed()
        if (generation != cache.generation && result is SuccessResult) {
            result.diskCacheKey?.let { imageLoader()?.diskCache?.remove(it) }
            result.memoryCacheKey?.let { imageLoader()?.memoryCache?.remove(it) }
        }
        result
    }
}

private val paletteCache = object : android.util.LruCache<String, List<Color>>(64) {}

@Composable
internal fun rememberArtworkPalette(url: String?, fallback: List<Color>): List<Color> {
    val context = LocalContext.current.applicationContext
    val default = remember(fallback) {
        ArtworkPalette.extract(fallback.map { it.toArgb() }.toIntArray()).map { Color(it) }
            .ifEmpty { List(5) { Color(0xFF233645) } }
    }
    return produceState(initialValue = url?.let(paletteCache::get) ?: default, url, default) {
        if (url == null || !url.startsWith("https://")) return@produceState
        paletteCache.get(url)?.let { value = it; return@produceState }
        val request = ImageRequest.Builder(context).data(url)
            .size(48).allowHardware(false).memoryCacheKey("palette:$url").build()
        val image = ArtworkImages.loader(context).execute(request).image ?: return@produceState
        val colors = withContext(ArtworkImages.decodeDispatcher) {
            val bitmap = image.toBitmap()
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            ArtworkPalette.extract(pixels).map { Color(it) }
        }
        if (colors.isNotEmpty()) { paletteCache.put(url, colors); value = colors }
    }.value
}
