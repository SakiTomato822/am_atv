package dev.amatv

import android.app.Application
import dev.amatv.core.SettingsStore
import dev.amatv.core.SharedPreferencesSettingsStorage
import dev.amatv.i18n.AppText
import dev.amatv.integration.ContentCache
import dev.amatv.integration.LogRecorder

class AmAtvApplication : Application() {
    lateinit var settingsStore: SettingsStore

    override fun onCreate() {
        super.onCreate()
        AppText.initialize(this)
        val contentCache = ContentCache.get(this)
        settingsStore = SettingsStore(
            storage = SharedPreferencesSettingsStorage(this),
            onCacheDiskChanged = { contentCache.setEnabled(it) },
            onCachePrefetchChanged = { contentCache.setPrefetch(it) },
        )
        // The car build keeps a full log for export; only the app process, not Gecko's.
        if (BuildConfig.FLYME_AUTO && getProcessName() == packageName) LogRecorder.start(this)
    }
}
