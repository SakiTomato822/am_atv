package dev.amatv

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.amatv.flyme.FolioApp
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
        val settingsStore = (applicationContext as AmAtvApplication).settingsStore
        setContent {
            val settings by settingsStore.state.collectAsState()
            var folioLight by remember { mutableStateOf(false) }
            if (BuildConfig.FLYME_AUTO) {
                SideEffect {
                    val controller = WindowInsetsControllerCompat(window, window.decorView)
                    if (settings.tryNewUi) {
                        WindowCompat.setDecorFitsSystemWindows(window, false)
                        @Suppress("DEPRECATION")
                        window.statusBarColor = android.graphics.Color.TRANSPARENT
                        @Suppress("DEPRECATION")
                        window.navigationBarColor = android.graphics.Color.TRANSPARENT
                        controller.isAppearanceLightStatusBars = folioLight
                        controller.isAppearanceLightNavigationBars = folioLight
                    } else {
                        WindowCompat.setDecorFitsSystemWindows(window, true)
                        @Suppress("DEPRECATION")
                        window.statusBarColor = android.graphics.Color.BLACK
                        @Suppress("DEPRECATION")
                        window.navigationBarColor = android.graphics.Color.BLACK
                        controller.isAppearanceLightStatusBars = false
                        controller.isAppearanceLightNavigationBars = false
                    }
                }
            }
            CompositionLocalProvider(LocalHostWindow provides host) {
                if (BuildConfig.FLYME_AUTO && settings.tryNewUi) {
                    FolioApp(onLightThemeChanged = { folioLight = it })
                } else if (BuildConfig.FLYME_AUTO) {
                    CarLayout { AmAtvApp() }
                } else {
                    AmAtvApp()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (BuildConfig.FLYME_AUTO) WindowHosts.release(this)
    }
}
