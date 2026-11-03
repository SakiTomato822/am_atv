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
        Spacer(Modifier.height(8.dp))
        CacheButton(stringResource(R.string.diagnostics_clear), onClick = diagnostics::clear,
            modifier = Modifier.width(140.dp))
    }
}
