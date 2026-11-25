package dev.amatv.flyme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.amatv.BuildConfig
import dev.amatv.R
import dev.amatv.core.AppearanceMode
import dev.amatv.core.AutoLowPerformanceReason
import dev.amatv.core.LowPerformanceMode
import dev.amatv.core.SettingsState
import dev.amatv.core.SettingsStore
import dev.amatv.integration.ContentCacheStorage
import dev.amatv.integration.LogRecorder
import dev.amatv.integration.FlymeMediaSource.ConnectionState
import dev.amatv.integration.FlymeVoiceBridge.Phase
import dev.amatv.integration.LoudnessMode
import dev.amatv.integration.MusicConnectionState
import dev.amatv.integration.MusicServices
import dev.amatv.artwork.ArtworkImages
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal enum class FolioSettingsSection(val labelRes: Int, val itemKey: String) {
    Account(R.string.app_account, "grp-account"),
    Appearance(R.string.appearance_title, "grp-look"),
    Playback(R.string.nav_now_playing, "grp-play"),
    Lyrics(R.string.player_lyrics, "grp-lyr"),
    Vehicle(R.string.flyme_media_status, "grp-car"),
    Storage(R.string.browse_cache_title, "grp-store"),
    About(R.string.flyme_app_name, "grp-about"),
    Gapless(R.string.diagnostics_gapless_history, "grp-gapless"),
}

internal fun settingsSectionIndex(itemKey: String): Int =
    FolioSettingsSection.entries.indexOfFirst { it.itemKey == itemKey }

@Composable
internal fun FolioSettingsPage(
    settingsStore: SettingsStore,
    settings: SettingsState,
    services: MusicServices,
    connectionState: MusicConnectionState,
    setupMessage: String?,
    bottomPadding: Dp,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onRefresh: () -> Unit,
    onNotice: (String) -> Unit,
    onRegisterScrollToTop: (suspend () -> Unit) -> Unit = {},
    onOpenWelcome: () -> Unit = {},
) {
    val palette = LocalFolioPalette.current
    val tierSpec = LocalFolioTierSpec.current
    val fontFamily = LocalFolioFontFamily.current
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    val listState = rememberFolioScrollToTopState(onRegisterScrollToTop)

    val cacheSettings by services.contentCache.settings.collectAsState()
    var cacheStorage by remember { mutableStateOf<ContentCacheStorage?>(null) }
    LaunchedEffect(cacheSettings) {
        cacheStorage = withContext(Dispatchers.IO) {
            services.contentCache.storage(ArtworkImages.diskBytes(context))
        }
    }
    var exportingLog by remember { mutableStateOf(false) }
    var showGaplessLog by remember { mutableStateOf(false) }
    val gaplessEntries by services.playbackDiagnostics.gaplessEntries.collectAsState()

    var selectedSection by remember { mutableStateOf(FolioSettingsSection.Account) }

    val sectionContent: @Composable (FolioSettingsSection) -> Unit = { section ->
        SettingsGroup(title = stringResource(section.labelRes)) {
            when (section) {
                FolioSettingsSection.Account -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, top = 4.dp, bottom = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(
                                    Brush.linearGradient(
                                        listOf(Color(0xFFFF5C72), Color(0xFF7A5CFF)),
                                    ),
                                ),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "Apple Music",
                                color = palette.fg,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 18.sp,
                            )
                            val statusText = when (connectionState) {
                                MusicConnectionState.SignedIn -> stringResource(R.string.browse_apple_connected)
                                MusicConnectionState.ReadyToSignIn -> setupMessage ?: stringResource(R.string.browse_apple_sign_in)
                                MusicConnectionState.Demo -> setupMessage ?: stringResource(R.string.browse_apple_sign_in)
                            }
                            Text(
                                text = statusText,
                                color = palette.fg2,
                                fontFamily = fontFamily,
                                fontSize = 13.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                        }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.rb))
                    SettingsRow(
                        title = stringResource(R.string.browse_refresh_apple),
                    ) {
                        FolioChip(
                            label = stringResource(R.string.browse_reload),
                            selected = false,
                            onClick = onRefresh,
                        )
                    }
                    SettingsRow(
                        title = "Storefront",
                        showDivider = connectionState != MusicConnectionState.Demo,
                    ) {
                        Text(
                            text = BuildConfig.APPLE_MUSIC_STOREFRONT.uppercase(),
                            color = palette.fg2,
                            fontFamily = fontFamily,
                            fontSize = 14.sp,
                        )
                    }
                    when (connectionState) {
                        MusicConnectionState.SignedIn -> {
                            Text(
                                text = stringResource(R.string.browse_sign_out_tv),
                                color = palette.danger,
                                fontFamily = fontFamily,
                                fontWeight = FontWeight.Medium,
                                fontSize = 16.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable(onClick = onSignOut)
                                    .padding(horizontal = 4.dp, vertical = 14.dp),
                            )
                        }
                        MusicConnectionState.ReadyToSignIn -> {
                            Box(Modifier.padding(top = 12.dp, start = 4.dp)) {
                                FolioChip(
                                    label = stringResource(R.string.browse_sign_in_tv),
                                    selected = true,
                                    onClick = onSignIn,
                                )
                            }
                        }
                        MusicConnectionState.Demo -> Unit
                    }
                }

                FolioSettingsSection.Appearance -> {
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
                    val performanceReason = when (settings.autoLowPerformanceReason) {
                        AutoLowPerformanceReason.Hardware -> stringResource(R.string.browse_performance_reason_hardware)
                        AutoLowPerformanceReason.PlaybackJank -> stringResource(R.string.browse_performance_reason_jank)
                        null -> null
                    }
                    val performanceHint = stringResource(R.string.browse_performance_mode_hint)
                    SettingsRow(
                        title = stringResource(R.string.browse_performance_low),
                        subtitle = if (performanceReason == null) performanceHint else {
                            performanceHint + "\n" +
                                stringResource(R.string.browse_performance_auto_enabled, performanceReason)
                        },
                        showDivider = false,
                    ) {
                        FolioSegmentedPills(
                            options = listOf(
                                LowPerformanceMode.Auto to stringResource(R.string.browse_performance_auto),
                                LowPerformanceMode.On to stringResource(R.string.browse_performance_on),
                                LowPerformanceMode.Off to stringResource(R.string.browse_performance_off),
                            ),
                            selected = settings.lowPerformanceMode,
                            onSelect = settingsStore::setLowPerformanceMode,
                        )
                    }
                }

                FolioSettingsSection.Playback -> {
                    SettingsToggleRow(
                        title = stringResource(R.string.player_autoplay),
                        checked = settings.autoplayEnabled,
                        onCheckedChange = { settingsStore.autoplayEnabled = it },
                    )
                    SettingsRow(
                        title = stringResource(R.string.playback_loudness_title),
                        subtitle = stringResource(R.string.playback_loudness_summary),
                        controlBelow = true,
                    ) {
                        FolioSegmentedPills(
                            options = listOf(
                                LoudnessMode.Off to stringResource(R.string.playback_loudness_off),
                                LoudnessMode.Standard to stringResource(R.string.playback_loudness_standard),
                                LoudnessMode.Loud to stringResource(R.string.playback_loudness_loud),
                                LoudnessMode.Loudest to stringResource(R.string.playback_loudness_loudest),
                            ),
                            selected = settings.loudnessMode,
                            onSelect = settingsStore::setLoudnessMode,
                        )
                    }
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

                FolioSettingsSection.Lyrics -> {
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

                FolioSettingsSection.Vehicle -> {
                    val voice = services.flymeVoice
                    if (voice != null) {
                        val voiceStatus by voice.status.collectAsState()
                        SettingsToggleRow(
                            title = stringResource(R.string.flyme_voice_setting),
                            checked = voiceStatus.enabled,
                            onCheckedChange = voice::setEnabled,
                        )
                        SettingsToggleRow(
                            title = stringResource(R.string.flyme_voice_announcements),
                            checked = voiceStatus.announcementsEnabled,
                            onCheckedChange = voice::setAnnouncementsEnabled,
                        )
                        val phase = stringResource(when (voiceStatus.phase) {
                            Phase.Off -> R.string.flyme_voice_off
                            Phase.Connecting -> R.string.flyme_voice_connecting
                            Phase.Connected -> R.string.flyme_voice_connected
                            Phase.Declared -> R.string.flyme_voice_declared
                            Phase.Unavailable -> R.string.flyme_voice_unavailable
                        })
                        val lastSemantic = voiceStatus.lastSemanticAt?.let {
                            val time = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(it))
                            "$time · ${voiceStatus.lastSemantic}"
                        }
                        SettingsRow(
                            title = stringResource(R.string.flyme_voice_status, phase),
                            subtitle = lastSemantic,
                        ) {}
                    }
                    val nativeStatus = services.nativeMediaStatus?.collectAsState()?.value
                    if (nativeStatus != null) {
                        val connection = stringResource(when (nativeStatus.state) {
                            ConnectionState.Disconnected -> R.string.flyme_media_disconnected
                            ConnectionState.Connecting -> R.string.flyme_voice_connecting
                            ConnectionState.Connected -> R.string.flyme_voice_connected
                        })
                        SettingsRow(title = stringResource(R.string.flyme_media_status)) {
                            Text("${nativeStatus.path.name} · $connection", color = palette.fg2, fontFamily = fontFamily, fontSize = 14.sp)
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

                FolioSettingsSection.Storage -> {
                    val storage = cacheStorage
                    val cacheUsed = storage?.let { formatStorageSize(it.usedBytes) }
                        ?: stringResource(R.string.browse_loading)
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

                FolioSettingsSection.About -> {
                    SettingsRow(
                        title = stringResource(R.string.flyme_app_name),
                    ) {
                        Text(
                            text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                            color = palette.fg2,
                            fontFamily = fontFamily,
                            fontSize = 14.sp,
                        )
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onOpenWelcome)
                            .padding(horizontal = 4.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(R.string.welcome_replay_settings),
                            color = palette.fg,
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.Medium,
                            fontSize = 16.sp,
                        )
                    }
                }

                FolioSettingsSection.Gapless -> {
                    SettingsToggleRow(
                        title = stringResource(R.string.diagnostics_gapless_show),
                        checked = showGaplessLog,
                        onCheckedChange = { showGaplessLog = it },
                    )
                }
            }
        }
    }

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
                FolioSettingsSection.entries.forEach { section ->
                    val pressed = section == selectedSection
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FolioRadii.NavButton))
                            .background(if (pressed) palette.rb2 else palette.bg.copy(alpha = 0f))
                            .clickable {
                                selectedSection = section
                                scope.launch {
                                    listState.animateScrollToItem(settingsSectionIndex(section.itemKey))
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = stringResource(section.labelRes),
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
            items(FolioSettingsSection.entries, key = { it.itemKey }) { section ->
                sectionContent(section)
            }
            if (showGaplessLog) {
                if (gaplessEntries.isEmpty()) {
                    item(key = "gapless-empty") {
                        Text(stringResource(R.string.diagnostics_gapless_empty), color = palette.fg2, fontFamily = fontFamily)
                    }
                }
                val keys = stableLazyKeys(gaplessEntries.map { "gapless:${it.timeMs}:${it.text}" })
                items(gaplessEntries.size, key = { keys[it] }) { index ->
                    val entry = gaplessEntries[index]
                    Text(
                        text = "${entry.clock}  ${entry.text}",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        color = palette.fg2,
                        fontFamily = fontFamily,
                        fontSize = 13.sp,
                    )
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
    controlBelow: Boolean = false,
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
            if (!controlBelow) control()
        }
        if (controlBelow) {
            Box(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 12.dp)) { control() }
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
