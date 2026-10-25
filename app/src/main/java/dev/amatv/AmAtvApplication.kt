package dev.amatv

import android.app.Application
import dev.amatv.i18n.AppText

class AmAtvApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppText.initialize(this)
    }
}
