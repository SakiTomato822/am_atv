package dev.amatv.benchmark

import android.os.Bundle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

/** Automatically preserve on-device failure evidence; never write account UI into source control. */
class UiFailureArtifacts : TestWatcher() {
    override fun failed(error: Throwable, description: Description) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(instrumentation.context.getExternalFilesDir(null), "ui-failures")
        if (!output.isDirectory && !output.mkdirs()) return
        val stem = "${description.className.substringAfterLast('.')}_${description.methodName}"
            .replace(Regex("[^A-Za-z0-9_.-]"), "_")
        val device = UiDevice.getInstance(instrumentation)
        val screenshot = File(output, "$stem.png")
        val hierarchy = File(output, "$stem.xml")
        runCatching { device.takeScreenshot(screenshot) }
        runCatching { device.dumpWindowHierarchy(hierarchy) }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("uiFailureScreenshot", screenshot.absolutePath)
            putString("uiFailureHierarchy", hierarchy.absolutePath)
        })
    }
}
