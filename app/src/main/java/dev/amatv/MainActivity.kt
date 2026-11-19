package dev.amatv

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.amatv.flyme.FolioApp

open class MainActivity : AppCompatActivity() {
    /** True in the Flyme small window, which runs the whole app in a portrait window. */
    protected open val miniWindow: Boolean get() = false

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        @Suppress("DEPRECATION")
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        WindowHosts.claim(this)
        val host = HostWindow(
            mini = miniWindow,
            canOpenMini = WindowHosts.canOpenMini(this),
        ) { toMini -> WindowHosts.switch(this, toMini) }
        setContent {
            var folioLight by remember { mutableStateOf(false) }
            SideEffect {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightStatusBars = folioLight
                controller.isAppearanceLightNavigationBars = folioLight
            }
            CompositionLocalProvider(LocalHostWindow provides host) {
                FolioApp(onLightThemeChanged = { folioLight = it })
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WindowHosts.release(this)
    }
}
