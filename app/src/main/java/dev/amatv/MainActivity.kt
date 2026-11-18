package dev.amatv

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.amatv.ui.AmAtvApp
import dev.amatv.ui.CarLayout

open class MainActivity : AppCompatActivity() {
    /** True in the Flyme small window, which runs the whole app in a portrait window. */
    protected open val miniWindow: Boolean get() = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, BuildConfig.FLYME_AUTO)
        if (BuildConfig.FLYME_AUTO) {
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        } else {
            WindowInsetsControllerCompat(window, window.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        val host = if (BuildConfig.FLYME_AUTO) {
            WindowHosts.claim(this)
            HostWindow(
                mini = miniWindow,
                canOpenMini = WindowHosts.canOpenMini(this),
            ) { toMini -> WindowHosts.switch(this, toMini) }
        } else null
        setContent {
            CompositionLocalProvider(LocalHostWindow provides host) {
                if (BuildConfig.FLYME_AUTO) CarLayout { AmAtvApp() } else AmAtvApp()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.FLYME_AUTO) WindowHosts.release(this)
    }
}
