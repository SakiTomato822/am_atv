package dev.amatv.artwork

import android.os.Process
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.intercept.Interceptor
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.ImageResult
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import dev.amatv.integration.ContentCache
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath

/** One loader for thumbnails and palette requests, with bounded background decoding. */
internal object ArtworkImages {
    val decodeDispatcher = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "AMATV-artwork").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    @Volatile private var shared: ImageLoader? = null

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
