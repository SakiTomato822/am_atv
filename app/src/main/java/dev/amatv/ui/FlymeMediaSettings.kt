package dev.amatv.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.R
import dev.amatv.integration.MusicServices
import dev.amatv.integration.PlaybackDiagnostics
import dev.amatv.integration.LogRecorder
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn

@Composable
internal fun FlymeMediaSettings(services: MusicServices) {
    val flow = services.nativeMediaStatus ?: return
    val status by flow.collectAsState()
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.flyme_media_status), color = Color.White,
        fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(status, color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
    Text("Android ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}",
        color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
}

/** Recent song changes (gapless or a new prepare, time to sound), for a screenshot from the car. */
@Composable
internal fun FlymePlaybackDiagnostics(diagnostics: PlaybackDiagnostics) {
    val entries by diagnostics.entries.collectAsState()
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.diagnostics_title), color = Color.White,
        fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.diagnostics_summary), color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
    val widevine by androidx.compose.runtime.produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { dev.amatv.integration.widevineStatus() }
    }
    Text(stringResource(R.string.diagnostics_widevine, widevine ?: stringResource(R.string.widevine_checking)),
        color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
    if (entries.isEmpty()) {
        Text(stringResource(R.string.diagnostics_empty), color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
    } else {
        entries.forEach { entry ->
            Row(Modifier.widthIn(max = 1100.dp)) {
                Text(entry.clock, color = Color.White.copy(alpha = 0.5f), fontSize = 14.sp,
                    modifier = Modifier.width(76.dp))
                Text(entry.text, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
            }
        }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var exportResult by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CacheButton(stringResource(if (exporting) R.string.diagnostics_exporting else R.string.diagnostics_export_log),
            onClick = {
                if (!exporting) scope.launch {
                    exporting = true
                    exportResult = runCatching { withContext(Dispatchers.IO) { LogRecorder.export(context) } }
                        .fold({ context.getString(R.string.diagnostics_exported, it) },
                            { context.getString(R.string.diagnostics_export_failed, it.message ?: it.javaClass.simpleName) })
                    exporting = false
                }
            },
            modifier = Modifier.width(220.dp))
        if (entries.isNotEmpty()) {
            CacheButton(stringResource(R.string.diagnostics_clear), onClick = diagnostics::clear,
                modifier = Modifier.width(140.dp))
        }
    }
    exportResult?.let { Text(it, color = Color.White.copy(alpha = 0.8f), fontSize = 14.sp) }
}
