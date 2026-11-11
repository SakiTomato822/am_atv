package dev.amatv

import android.os.Bundle
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.delay
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import dev.amatv.ui.AmAtvApp
import dev.amatv.ui.CarLayout

open class MainActivity : AppCompatActivity() {
    /** True in the Flyme small window, which shows only Now Playing. */
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
            // A host that replaces a playing one (a window switch, or the launcher while the small
            // window plays) carries playback on; a fresh start leaves playback to the user.
            val replacedPlaying = WindowHosts.claim(this)
            val handedOver = savedInstanceState == null && intent.getBooleanExtra(WindowHosts.EXTRA_RESUME, false)
            HostWindow(
                mini = miniWindow,
                resume = replacedPlaying || handedOver,
                canOpenMini = WindowHosts.canOpenMini(this),
            ) { toMini, resume -> WindowHosts.switch(this, toMini, resume) }
        } else null
        setContent {
            CompositionLocalProvider(LocalHostWindow provides host) {
                if (BuildConfig.FLYME_AUTO) {
                    // Wait for a replaced window to release its player first (at most a few seconds).
                    val previousReleased by WindowHosts.previousReleased.collectAsState()
                    LaunchedEffect(previousReleased) {
                        if (!previousReleased) {
                            delay(HANDOVER_TIMEOUT_MS)
                            WindowHosts.stopWaiting()
                        }
                    }
                    CarLayout { if (previousReleased) AmAtvApp() }
                } else AmAtvApp()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // After super: the composition, and with it the player, has been released by now.
        if (BuildConfig.FLYME_AUTO) WindowHosts.release(this)
    }

    private companion object {
        const val HANDOVER_TIMEOUT_MS = 4_000L
    }
}
