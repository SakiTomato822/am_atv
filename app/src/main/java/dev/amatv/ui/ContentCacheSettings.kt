package dev.amatv.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.integration.ContentCache
import dev.amatv.integration.ContentCacheStorage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
internal fun ContentCacheSettings(cache: ContentCache) {
    val context = LocalContext.current.applicationContext
    val settings by cache.settings.collectAsState()
    val scope = rememberCoroutineScope()
    var storage by remember { mutableStateOf<ContentCacheStorage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val diskFocus = remember { FocusRequester() }
    val prefetchFocus = remember { FocusRequester() }
    val clearFocus = remember { FocusRequester() }
    LaunchedEffect(settings) {
        storage = withContext(Dispatchers.IO) { cache.storage(ArtworkImages.diskBytes(context)) }
    }
    Spacer(Modifier.height(8.dp))
    Text("封面與歌詞快取", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.CenterStart) {
        val current = storage
        if (current == null) AppleLoadingIndicator(Modifier.size(18.dp))
        else Text(
            "已使用 ${storageSize(current.usedBytes)} · 可用 ${storageSize(current.availableBytes)}",
            color = Color.White.copy(alpha = 0.62f), fontSize = 14.sp,
        )
    }
    if (storage?.storageLow == true) {
        Text("空間不足，已暫停新增快取", color = Color(0xFFFFC5A1), fontSize = 14.sp)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CacheButton(
            label = "磁碟快取：${if (settings.enabled) "開啟" else "關閉"}",
            onClick = { cache.setEnabled(!settings.enabled) },
            modifier = Modifier.width(240.dp).focusRequester(diskFocus).focusProperties {
                left = FocusRequester.Cancel; right = prefetchFocus; down = clearFocus
            },
        )
        CacheButton(
            label = "後兩首封面與歌詞預載：${if (settings.prefetchNextTwo) "開啟" else "關閉"}",
            onClick = { cache.setPrefetch(!settings.prefetchNextTwo) },
            modifier = Modifier.width(360.dp).focusRequester(prefetchFocus).focusProperties {
                left = diskFocus; right = FocusRequester.Cancel; down = clearFocus
            },
        )
    }
    CacheButton(
        label = if (settings.clearing) "清除中…" else "清除封面與歌詞快取",
        onClick = {
            if (!settings.clearing) scope.launch {
                error = null
                try { cache.clear { ArtworkImages.clear(context) } }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = "部分快取無法清除，請重試。" }
            }
        },
        modifier = Modifier.width(290.dp).focusRequester(clearFocus).focusProperties {
            up = diskFocus; left = FocusRequester.Cancel; right = FocusRequester.Cancel; down = FocusRequester.Cancel
        },
    )
    error?.let { Text(it, color = Color(0xFFFFB0BA), fontSize = 14.sp) }
}

@Composable
private fun CacheButton(label: String, onClick: () -> Unit, modifier: Modifier) {
    FocusSurface(
        onClick = onClick, modifier = modifier.height(46.dp), cornerRadius = 23.dp,
        background = Color.White.copy(alpha = 0.12f), focusedBackground = Color.White.copy(alpha = 0.28f),
        padding = PaddingValues(horizontal = 18.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun storageSize(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) {
    String.format(Locale.getDefault(), "%.1f GB", bytes / (1024.0 * 1024 * 1024))
} else "${bytes / (1024 * 1024)} MB"
