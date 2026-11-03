package dev.amatv

import android.app.Application
import dev.amatv.i18n.AppText
import dev.amatv.integration.LogRecorder

class AmAtvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppText.initialize(this)
        // The car build keeps a full log for export; only the app process, not Gecko's.
        if (BuildConfig.FLYME_AUTO && getProcessName() == packageName) LogRecorder.start(this)
    }
}
