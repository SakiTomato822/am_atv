package dev.amatv

import android.app.Application
import android.os.SystemClock
import dev.amatv.core.AccountStore
import dev.amatv.core.ContentStore
import dev.amatv.core.LibraryActions
import dev.amatv.core.LyricsStore
import dev.amatv.core.PlaybackStore
import dev.amatv.core.SettingsStore
import dev.amatv.core.SharedPreferencesSettingsStorage
import dev.amatv.i18n.AppText
import dev.amatv.integration.ContentCache
import dev.amatv.integration.LogRecorder
import dev.amatv.integration.MusicServices
import dev.amatv.integration.PlaybackPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AmAtvApplication : Application() {
    lateinit var settingsStore: SettingsStore
    lateinit var libraryActions: LibraryActions
    lateinit var lyricsStore: LyricsStore
    lateinit var accountStore: AccountStore
    lateinit var contentStore: ContentStore
    val musicServices by lazy { MusicServices(this) }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /**
     * The player, for as long as the process lives. Created by the first window that opens (never in Gecko's
     * process); later windows, including a switch between full screen and the small window, use the same one.
     */
    val playbackStore by lazy {
        PlaybackStore(
            player = musicServices,
            sessions = PlaybackPreferences(this),
            accountStore = accountStore,
            settingsStore = settingsStore,
            lyricsStore = lyricsStore,
            libraryActions = libraryActions,
            coroutineScope = applicationScope,
            clock = SystemClock::elapsedRealtime,
        ).also { it.attach() }
    }

    override fun onCreate() {
        super.onCreate()
        AppText.initialize(this)
        val contentCache = ContentCache.get(this)
        settingsStore = SettingsStore(
            storage = SharedPreferencesSettingsStorage(this),
            onCacheDiskChanged = { contentCache.setEnabled(it) },
            onCachePrefetchChanged = { contentCache.setPrefetch(it) },
        )
        libraryActions = LibraryActions(
            api = musicServices.api,
            coroutineScope = applicationScope,
        )
        lyricsStore = LyricsStore(
            source = musicServices,
            settingsStore = settingsStore,
            coroutineScope = applicationScope,
            appLanguageTagProvider = { resources.configuration.locales[0].toLanguageTag() },
        )
        accountStore = AccountStore(operations = musicServices)
        contentStore = ContentStore(
            operations = musicServices,
            coroutineScope = applicationScope,
        )
        // The car build keeps a full log for export; only the app process, not Gecko's.
        if (BuildConfig.FLYME_AUTO && getProcessName() == packageName) LogRecorder.start(this)
    }
}
