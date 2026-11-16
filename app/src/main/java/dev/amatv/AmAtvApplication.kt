package dev.amatv

import android.app.Application
import dev.amatv.core.LibraryActions
import dev.amatv.core.SettingsStore
import dev.amatv.core.SharedPreferencesSettingsStorage
import dev.amatv.i18n.AppText
import dev.amatv.integration.ContentCache
import dev.amatv.integration.LogRecorder
import dev.amatv.integration.MusicServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class AmAtvApplication : Application() {
    lateinit var settingsStore: SettingsStore
    lateinit var libraryActions: LibraryActions
    val musicServices by lazy { MusicServices(this) }

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
            coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
        // The car build keeps a full log for export; only the app process, not Gecko's.
        if (BuildConfig.FLYME_AUTO && getProcessName() == packageName) LogRecorder.start(this)
    }
}
