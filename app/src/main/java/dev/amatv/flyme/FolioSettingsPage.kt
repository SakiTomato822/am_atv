package dev.amatv.flyme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.BuildConfig
import dev.amatv.R
import dev.amatv.core.AppearanceMode
import dev.amatv.core.LyricBackgroundStyle
import dev.amatv.core.SettingsState
import dev.amatv.core.SettingsStore
import dev.amatv.integration.ContentCacheStorage
import dev.amatv.integration.LogRecorder
import dev.amatv.integration.MusicServices
import dev.amatv.artwork.ArtworkImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun FolioSettingsPage(
    settingsStore: SettingsStore,
    settings: SettingsState,
    services: MusicServices,
    bottomPadding: Dp,
    onNotice: (String) -> Unit,
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val cacheSettings by services.contentCache.settings.collectAsState()
    var cacheStorage by remember { mutableStateOf<ContentCacheStorage?>(null) }
    LaunchedEffect(cacheSettings) {
        cacheStorage = withContext(Dispatchers.IO) {
            services.contentCache.storage(ArtworkImages.diskBytes(context))
        }
    }
    var exportingLog by remember { mutableStateOf(false) }

    val navLabels = listOf(
        stringResource(R.string.appearance_title),
        stringResource(R.string.nav_now_playing),
        stringResource(R.string.player_lyrics),
        stringResource(R.string.flyme_media_status),
        stringResource(R.string.browse_cache_title),
        stringResource(R.string.flyme_app_name),
    )
    // Exact LazyColumn item indices matching the section headers below.
    val navIndices = listOf(0, 1, 2, 3, 4, 5)
    var selectedSection by remember { mutableIntStateOf(0) }

    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(
                start = tierSpec.px,
                end = tierSpec.pr,
                top = 4.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        if (tierSpec.showsSettingsSidebar) {
            Column(
                modifier = Modifier
                    .width(230.dp)
                    .fillMaxHeight()
                    .padding(bottom = bottomPadding),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                navLabels.forEachIndexed { idx, label ->
                    val pressed = idx == selectedSection
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FolioRadii.NavButton))
                            .background(if (pressed) palette.rb2 else palette.bg.copy(alpha = 0f))
                            .clickable {
                                selectedSection = idx
                                scope.launch { listState.animateScrollToItem(navIndices[idx]) }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = label,
                            color = if (pressed) palette.fg else palette.fg2,
                            fontFamily = fontFamily,
                            fontWeight = if (pressed) FontWeight.SemiBold else FontWeight.Normal,
                            fontSize = 18.sp,
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .widthIn(max = 760.dp)
                .fillMaxHeight(),
            contentPadding = PaddingValues(bottom = bottomPadding),
        ) {
            item(key = "grp-look") {
                SettingsGroup(title = stringResource(R.string.appearance_title)) {
                    SettingsRow(
                        title = stringResource(R.string.appearance_title),
                    ) {
                        FolioSegmentedPills(
                            options = listOf(
                                AppearanceMode.Dark to stringResource(R.string.appearance_dark),
                                AppearanceMode.Light to stringResource(R.string.appearance_light),
                                AppearanceMode.System to stringResource(R.string.appearance_system),
                                AppearanceMode.Time to stringResource(R.string.appearance_time),
                            ),
                            selected = settings.appearance,
                            onSelect = settingsStore::setAppearance,
                        )
                    }
                    if (settings.appearance == AppearanceMode.Time) {
                        SettingsScheduleRow(
                            label = stringResource(R.string.appearance_light_from, formatClockMinute(settings.lightFromMinute)),
                            onDecrease = { settingsStore.setLightSchedule(stepMinute(settings.lightFromMinute, -30), settings.lightUntilMinute) },
                            onIncrease = { settingsStore.setLightSchedule(stepMinute(settings.lightFromMinute, 30), settings.lightUntilMinute) },
                        )
                        SettingsScheduleRow(
                            label = stringResource(R.string.appearance_light_until, formatClockMinute(settings.lightUntilMinute)),
                            onDecrease = { settingsStore.setLightSchedule(settings.lightFromMinute, stepMinute(settings.lightUntilMinute, -30)) },
                            onIncrease = { settingsStore.setLightSchedule(settings.lightFromMinute, stepMinute(settings.lightUntilMinute, 30)) },
                        )
                    }
                    SettingsToggleRow(
                        title = stringResource(R.string.browse_performance_low),
                        subtitle = stringResource(R.string.browse_performance_mode_hint),
                        checked = settings.lowPerformanceMode,
                        onCheckedChange = { settingsStore.lowPerformanceMode = it },
                        showDivider = false,
                    )
                }
            }

            // 1: Playback
            item(key = "grp-play") {
                SettingsGroup(title = stringResource(R.string.nav_now_playing)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.player_autoplay),
                        checked = settings.autoplayEnabled,
                        onCheckedChange = { settingsStore.autoplayEnabled = it },
                    )
                    SettingsRow(
                        title = stringResource(R.string.playback_fade_title),
                        subtitle = stringResource(R.string.playback_fade_summary),
                        showDivider = false,
                    ) {}
                    SettingsToggleRow(
                        title = stringResource(R.string.playback_fade_albums),
                        checked = settings.fadeAlbums,
                        onCheckedChange = settingsStore::setFadeAlbums,
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.playback_fade_stations),
                        checked = settings.fadeStations,
                        onCheckedChange = settingsStore::setFadeStations,
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.playback_fade_playlists),
                        checked = settings.fadePlaylists,
                        onCheckedChange = settingsStore::setFadePlaylists,
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.playback_station_mode_title),
                        subtitle = stringResource(R.string.playback_station_mode_summary),
                        checked = settings.stationsAsSongs,
                        onCheckedChange = settingsStore::setStationsAsSongs,
                        showDivider = false,
                    )
                }
            }

            // 2: Lyrics
            item(key = "grp-lyr") {
                SettingsGroup(title = stringResource(R.string.player_lyrics)) {
                    SettingsRow(
                        title = stringResource(R.string.flyme_lyric_style),
                    ) {
                        FolioSegmentedPills(
                            options = listOf(
                                LyricBackgroundStyle.Flow to stringResource(R.string.flyme_lyric_flow),
                                LyricBackgroundStyle.Soak to stringResource(R.string.flyme_lyric_soak),
                            ),
                            selected = settings.lyricStyle,
                            onSelect = { style ->
                                settingsStore.setLyricStyle(style)
                                FolioPlayerSession.syncDefaultDisplayFromSetting(style)
                            },
                        )
                    }
                    SettingsToggleRow(
                        title = stringResource(R.string.player_lyrics_translation),
                        subtitle = stringResource(R.string.player_lyrics_translation_hint),
                        checked = settings.lyricTranslationEnabled,
                        onCheckedChange = settingsStore::setLyricTranslationEnabled,
                    )
                    SettingsToggleRow(
                        title = stringResource(R.string.player_lyrics_pronunciation),
                        subtitle = stringResource(R.string.player_lyrics_pronunciation_hint),
                        checked = settings.lyricPronunciationEnabled,
                        onCheckedChange = settingsStore::setLyricPronunciationEnabled,
                        showDivider = false,
                    )
                }
            }

            // 3: Vehicle
            item(key = "grp-car") {
                val nativeStatus = services.nativeMediaStatus?.collectAsState()?.value
                SettingsGroup(title = stringResource(R.string.flyme_media_status)) {
                    if (nativeStatus != null) {
                        SettingsRow(title = stringResource(R.string.flyme_media_status)) {
                            Text(nativeStatus, color = palette.fg2, fontFamily = fontFamily, fontSize = 14.sp)
                        }
                    }
                    SettingsRow(title = "Android", showDivider = false) {
                        Text(
                            text = "${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}",
                            color = palette.fg2,
                            fontFamily = fontFamily,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            // 4: Storage & Logs
            item(key = "grp-store") {
                val storage = cacheStorage
                val cacheUsed = storage?.let { formatStorageSize(it.usedBytes) }
                    ?: stringResource(R.string.browse_loading)
                SettingsGroup(title = stringResource(R.string.browse_cache_title)) {
                    SettingsToggleRow(
                        title = stringResource(R.string.browse_cache_disk_on).substringBefore("：").substringBefore(":"),
                        checked = cacheSettings.enabled,
                        onCheckedChange = settingsStore::setCacheDiskEnabled,
                    )
                    SettingsRow(
                        title = stringResource(R.string.browse_cache_title),
                        subtitle = cacheUsed,
                    ) {
                        val clearingLabel = stringResource(R.string.browse_cache_clearing)
                        val clearErrorLabel = stringResource(R.string.browse_cache_clear_error)
                        val clearedLabel = stringResource(R.string.browse_cache_clear)
                        FolioChip(
                            label = if (cacheSettings.clearing) clearingLabel else clearedLabel,
                            selected = false,
                            onClick = {
                                if (!cacheSettings.clearing) {
                                    scope.launch {
                                        try {
                                            services.contentCache.clear { ArtworkImages.clear(context) }
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (_: Exception) {
                                            onNotice(clearErrorLabel)
                                        }
                                    }
                                }
                            },
                        )
                    }
                    SettingsRow(
                        title = stringResource(R.string.diagnostics_export_log),
                        showDivider = false,
                    ) {
                        val exportLabel = stringResource(
                            if (exportingLog) R.string.diagnostics_exporting else R.string.diagnostics_export_log,
                        )
                        FolioChip(
                            label = exportLabel,
                            selected = false,
                            onClick = {
                                if (!exportingLog) {
                                    scope.launch {
                                        exportingLog = true
                                        try {
                                            val path = withContext(Dispatchers.IO) { LogRecorder.export(context) }
                                            onNotice(context.getString(R.string.diagnostics_exported, path))
                                        } catch (cancelled: CancellationException) {
                                            throw cancelled
                                        } catch (error: Exception) {
                                            onNotice(
                                                context.getString(
                                                    R.string.diagnostics_export_failed,
                                                    error.message ?: error.javaClass.simpleName,
                                                ),
                                            )
                                        } finally {
                                            exportingLog = false
                                        }
                                    }
                                }
                            },
                        )
                    }
                }
            }

            // 5: About
            item(key = "grp-about") {
                SettingsGroup(title = stringResource(R.string.flyme_app_name)) {
                    SettingsRow(
                        title = stringResource(R.string.flyme_app_name),
                        showDivider = false,
                    ) {
                        Text(
                            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            color = palette.fg2,
                            fontFamily = fontFamily,
                            fontSize = 14.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    content: @Composable () -> Unit,
) {
    val palette = LocalFolioPalette.current
    val fontFamily = LocalFolioFontFamily.current
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Text(
            text = title,
            color = palette.accent,
            fontFamily = fontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            letterSpacing = 0.6.sp,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        content()
    }
}

@Composable
private fun SettingsToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showDivider: Boolean = true,
) {
    SettingsRow(title = title, subtitle = subtitle, showDivider = showDivider) {
        FolioSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String? = null,
    showDivider: Boolean = true,
    control: @Composable () -> Unit,
) {
    val palette = LocalFolioPalette.current
    val fontFamily = LocalFolioFontFamily.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    color = palette.fg,
                    fontFamily = fontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                )
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        text = subtitle,
                        color = palette.fg2,
                        fontFamily = fontFamily,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            control()
        }
        if (showDivider) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rb))
        }
    }
}

@Composable
private fun SettingsScheduleRow(
    label: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    SettingsRow(title = label) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FolioChip(label = "−30", selected = false, onClick = onDecrease)
            FolioChip(label = "+30", selected = false, onClick = onIncrease)
        }
    }
}

private fun formatClockMinute(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

private fun stepMinute(minute: Int, by: Int): Int = Math.floorMod(minute + by, 24 * 60)

@Composable
private fun formatStorageSize(bytes: Long): String = if (bytes >= 1024L * 1024 * 1024) {
    stringResource(R.string.browse_cache_gigabytes, bytes / (1024.0 * 1024 * 1024))
} else {
    stringResource(R.string.browse_cache_megabytes, bytes / (1024 * 1024))
}
