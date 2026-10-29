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
