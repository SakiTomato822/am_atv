package dev.amatv

import android.app.Application
import android.os.Build
import android.os.LocaleList
import android.os.SystemClock
import androidx.core.app.LocaleManagerCompat
import dev.amatv.core.AccountStore
import dev.amatv.core.ContentStore
import dev.amatv.core.LibraryActions
import dev.amatv.core.LyricsStore
import dev.amatv.core.PlayHistoryStore
import dev.amatv.core.PlaybackStore
import dev.amatv.core.SettingsStore
import dev.amatv.core.SharedPreferencesPlayHistoryStorage
import dev.amatv.core.SharedPreferencesSettingsStorage
import dev.amatv.core.SharedPreferencesWelcomePreferences
import dev.amatv.core.WelcomePreferences
import dev.amatv.core.detectLowPerformanceHardware
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
    lateinit var welcomePreferences: WelcomePreferences
    lateinit var libraryActions: LibraryActions
    lateinit var lyricsStore: LyricsStore
    lateinit var accountStore: AccountStore
    lateinit var contentStore: ContentStore
    lateinit var playHistoryStore: PlayHistoryStore
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
            diagnostics = musicServices.playbackDiagnostics,
            playHistoryStore = playHistoryStore,
        ).also { store ->
            store.attach()
            musicServices.startFlymeVoice(
                applicationScope,
                searchAndPlay = { store.playVoiceSearch(musicServices, it) },
                // Voice only ever adds; an already-liked song must not raise the "can't remove" notice.
                favorite = { if (libraryActions.state.value.current.isFavorite != true) libraryActions.toggleFavorite() },
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        AppText.initialize(this)
        val contentCache = ContentCache.get(this)
        val isMainProcess = getProcessName() == packageName
        settingsStore = SettingsStore(
            storage = SharedPreferencesSettingsStorage(this),
            onCacheDiskChanged = { contentCache.setEnabled(it) },
            onCachePrefetchChanged = { contentCache.setPrefetch(it) },
            lowPerformanceHardware = isMainProcess && detectLowPerformanceHardware(this),
        )
        welcomePreferences = SharedPreferencesWelcomePreferences(this)
        playHistoryStore = PlayHistoryStore(
            storage = SharedPreferencesPlayHistoryStorage(this),
            coroutineScope = applicationScope,
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
        if (isMainProcess) {
            if (BuildConfig.FLYME_AUTO) LogRecorder.start(this)
            val account = accountStore.state.value
            if (account.isSignedIn) {
                contentStore.startSignedInContent(account.connectionState, startupContentLanguageTag())
            }
        }
    }

    private fun startupContentLanguageTag(): String {
        val configuredLocales = resources.configuration.locales
        if (Build.VERSION.SDK_INT >= 33) return configuredLocales.toLanguageTags()

        // AppCompat applies the stored app locales ahead of the system locales in each activity.
        val appLocales = LocaleManagerCompat.getApplicationLocales(this)
        val locales = (List(appLocales.size()) { appLocales[it] } +
            List(configuredLocales.size()) { configuredLocales[it] }).distinct()
        return LocaleList(*locales.toTypedArray()).toLanguageTags()
    }
}
