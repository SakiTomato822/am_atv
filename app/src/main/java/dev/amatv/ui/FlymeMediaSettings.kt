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
import dev.amatv.integration.FlymePlaybackOptions
import dev.amatv.integration.FlymePlaybackSettings
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
    Text(stringResource(R.string.flyme_media_status), color = FlymeInk,
        fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(status, color = FlymeInk.copy(alpha = 0.8f), fontSize = 16.sp)
    Text("Android ${android.os.Build.VERSION.RELEASE} · API ${android.os.Build.VERSION.SDK_INT}",
        color = FlymeInk.copy(alpha = 0.6f), fontSize = 14.sp)
}

/** Recent song changes (gapless or a new prepare, time to sound), for a screenshot from the car. */
@Composable
internal fun FlymePlaybackDiagnostics(diagnostics: PlaybackDiagnostics) {
    val entries by diagnostics.entries.collectAsState()
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.diagnostics_title), color = FlymeInk,
        fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.diagnostics_summary), color = FlymeInk.copy(alpha = 0.6f), fontSize = 14.sp)
    val widevine by androidx.compose.runtime.produceState<String?>(null) {
        value = withContext(Dispatchers.IO) { dev.amatv.integration.widevineStatus() }
    }
    Text(stringResource(R.string.diagnostics_widevine, widevine ?: stringResource(R.string.widevine_checking)),
        color = FlymeInk.copy(alpha = 0.85f), fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
    if (entries.isEmpty()) {
        Text(stringResource(R.string.diagnostics_empty), color = FlymeInk.copy(alpha = 0.8f), fontSize = 15.sp)
    } else {
        entries.forEach { entry ->
            Row(Modifier.widthIn(max = 1100.dp)) {
                Text(entry.clock, color = FlymeInk.copy(alpha = 0.5f), fontSize = 14.sp,
                    modifier = Modifier.width(76.dp))
                Text(entry.text, color = FlymeInk.copy(alpha = 0.85f), fontSize = 14.sp)
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
    exportResult?.let { Text(it, color = FlymeInk.copy(alpha = 0.8f), fontSize = 14.sp) }
}

/** Fade per kind of queue, and how stations play (web player with Widevine, or song by song). */
@Composable
internal fun FlymePlaybackOptionsSettings(options: FlymePlaybackOptions) {
    val settings by options.settings.collectAsState()
    val on = stringResource(R.string.playback_option_on)
    val off = stringResource(R.string.playback_option_off)
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.playback_fade_title), color = FlymeInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.playback_fade_summary), color = FlymeInk.copy(alpha = 0.6f), fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CacheButton(stringResource(R.string.playback_fade_albums, if (settings.fadeAlbums) on else off),
            onClick = { options.update { it.copy(fadeAlbums = !it.fadeAlbums) } }, modifier = Modifier.width(200.dp))
        CacheButton(stringResource(R.string.playback_fade_stations, if (settings.fadeStations) on else off),
            onClick = { options.update { it.copy(fadeStations = !it.fadeStations) } }, modifier = Modifier.width(200.dp))
        CacheButton(stringResource(R.string.playback_fade_playlists, if (settings.fadePlaylists) on else off),
            onClick = { options.update { it.copy(fadePlaylists = !it.fadePlaylists) } }, modifier = Modifier.width(220.dp))
    }
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.playback_station_mode_title), color = FlymeInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Text(stringResource(R.string.playback_station_mode_summary), color = FlymeInk.copy(alpha = 0.6f), fontSize = 14.sp)
    Spacer(Modifier.height(6.dp))
    CacheButton(stringResource(if (settings.stationsAsSongs) R.string.playback_station_mode_songs else R.string.playback_station_mode_auto),
        onClick = { options.update { it.copy(stationsAsSongs = !it.stationsAsSongs) } }, modifier = Modifier.width(420.dp))
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.flyme_lyric_style), color = FlymeInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        for ((style, label) in listOf(
            FlymePlaybackSettings.LyricStyle.Flow to R.string.flyme_lyric_flow,
            FlymePlaybackSettings.LyricStyle.Soak to R.string.flyme_lyric_soak,
        )) {
            CacheButton(
                stringResource(if (settings.lyricStyle == style) R.string.flyme_lyric_style_selected else R.string.flyme_lyric_style_option,
                    stringResource(label)),
                onClick = { options.update { it.copy(lyricStyle = style) } },
                modifier = Modifier.width(170.dp).automationId("lyric-style-${style.name.lowercase()}"),
            )
        }
    }
}

/** Light / dark / system / by time of day, with the light hours chosen in 30-minute steps. */
@Composable
internal fun FlymeAppearanceSettings(options: FlymePlaybackOptions) {
    val settings by options.settings.collectAsState()
    Spacer(Modifier.height(16.dp))
    Text(stringResource(R.string.appearance_title), color = FlymeInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        listOf(
            FlymePlaybackSettings.Appearance.Light to R.string.appearance_light,
            FlymePlaybackSettings.Appearance.Dark to R.string.appearance_dark,
            FlymePlaybackSettings.Appearance.System to R.string.appearance_system,
            FlymePlaybackSettings.Appearance.Time to R.string.appearance_time,
        ).forEach { (mode, label) ->
            val selected = settings.appearance == mode
            CacheButton((if (selected) "✓ " else "") + stringResource(label),
                onClick = { options.update { it.copy(appearance = mode) } }, modifier = Modifier.width(170.dp))
        }
    }
    if (settings.appearance == FlymePlaybackSettings.Appearance.Time) {
        Spacer(Modifier.height(10.dp))
        fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)
        fun step(minute: Int, by: Int) = Math.floorMod(minute + by, 24 * 60)
        @Composable fun hours(label: Int, minute: Int, change: (Int) -> Unit) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(stringResource(label, clock(minute)), color = FlymeInk, fontSize = 16.sp, modifier = Modifier.width(200.dp))
                CacheButton("−30", onClick = { change(step(minute, -30)) }, modifier = Modifier.width(90.dp))
                CacheButton("+30", onClick = { change(step(minute, 30)) }, modifier = Modifier.width(90.dp))
            }
        }
        hours(R.string.appearance_light_from, settings.lightFromMinute) { value -> options.update { it.copy(lightFromMinute = value) } }
        Spacer(Modifier.height(8.dp))
        hours(R.string.appearance_light_until, settings.lightUntilMinute) { value -> options.update { it.copy(lightUntilMinute = value) } }
    }
}
