package dev.amatv.ui

import android.os.Build
import android.graphics.BitmapFactory
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import dev.amatv.MainActivity
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class CarLyricSoftFocusTest {
    @Test
    fun api30BlurLevelsRenderAndSaveScreenshot() {
        assumeTrue("This visual test targets the API 30 raster blur fallback", Build.VERSION.SDK_INT == Build.VERSION_CODES.R)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val line = "未播放歌词清晰度"
        val rows = listOf(
            LyricFocusSample("零", 0.dp, "lyric-soft-focus-none"),
            LyricFocusSample("中", 2.dp, "lyric-soft-focus-mid"),
            LyricFocusSample("远", 4.dp, "lyric-soft-focus-far"),
        )

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    AmAtvTheme {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black)
                                .padding(horizontal = 48.dp, vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            rows.forEachIndexed { index, sample ->
                                Text(
                                    text = "${sample.label} · ${sample.radius.value.toInt()}dp",
                                    color = Color.White.copy(alpha = 0.65f),
                                    fontSize = 18.sp,
                                    modifier = Modifier.fillMaxWidth(),
                                    textAlign = TextAlign.Center,
                                )
                                CarLyricSoftFocus(
                                    radius = sample.radius,
                                    contentKey = sample.contentKey,
                                    modifier = Modifier.fillMaxWidth().height(64.dp),
                                ) {
                                    Text(
                                        text = line,
                                        color = Color.White,
                                        fontSize = 40.sp,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .align(Alignment.Center)
                                            .semantics {
                                                contentDescription = sample.contentKey
                                            },
                                    )
                                }
                                if (index != rows.lastIndex) Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                }
            }

            val screenshotFile = File(targetContext.filesDir, "lyric-soft-focus.png")
            rows.forEach { sample ->
                assertNotNull(
                    "Rendered lyric row ${sample.label}",
                    device.wait(Until.findObject(By.desc(sample.contentKey)), 5_000L),
                )
            }
            device.waitForIdle()
            Thread.sleep(2_500L)
            device.waitForIdle()
            assertTrue(
                "Screenshot should be saved at ${screenshotFile.absolutePath}",
                device.takeScreenshot(screenshotFile),
            )
            val screenshot = requireNotNull(BitmapFactory.decodeFile(screenshotFile.absolutePath))
            try {
                rows.forEach { sample ->
                    val bounds = requireNotNull(device.findObject(By.desc(sample.contentKey))).visibleBounds
                    var visiblePixels = 0
                    for (y in bounds.top.coerceAtLeast(0) until bounds.bottom.coerceAtMost(screenshot.height)) {
                        for (x in bounds.left.coerceAtLeast(0) until bounds.right.coerceAtMost(screenshot.width)) {
                            val pixel = screenshot.getPixel(x, y)
                            if ((pixel and 0x00ffffff) > 0x202020) visiblePixels++
                        }
                    }
                    assertTrue("${sample.label} blur must preserve visible glyphs (pixels=$visiblePixels)",
                        visiblePixels > 40)
                }
            } finally { screenshot.recycle() }
        }
    }

    private data class LyricFocusSample(
        val label: String,
        val radius: androidx.compose.ui.unit.Dp,
        val contentKey: String,
    )
}
