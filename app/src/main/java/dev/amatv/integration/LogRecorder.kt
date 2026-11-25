package dev.amatv.integration

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import android.util.Log
import dev.amatv.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps this process's own logcat in two rotating files, so a car owner can export a full log
 * without adb. An app may read its own log lines without any permission. Tokens and other long
 * secrets are masked before anything reaches the disk.
 */
object LogRecorder {
    private const val TAG = "AMATV-LogRecorder"
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private val jwt = Regex("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}")
    private val longSecret = Regex("[A-Za-z0-9+/=_-]{80,}")
    private val namedSecret = Regex("(?i)((?:token|authorization|bearer|cookie)[\"']?\\s*[:=]\\s*[\"']?)[^\\s\"',;]{12,}")

    @Volatile private var started = false
    private lateinit var directory: File
    private val outputLock = Any()
    private val flushTimer = ScheduledThreadPoolExecutor(1) { work ->
        Thread(work, "AMATV-log-flush").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.apply { removeOnCancelPolicy = true }
    private var output: OutputStream? = null
    private var flushTask: ScheduledFuture<*>? = null
    private var pendingLines = 0

    fun start(context: Context) {
        if (started) return
        started = true
        directory = File(context.applicationContext.filesDir, "logs").apply { mkdirs() }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                synchronized(outputLock) { flushPending() }
            } catch (failure: IOException) {
                Log.w(TAG, "Crash log flush failed", failure)
            } finally {
                previousHandler?.uncaughtException(thread, error)
            }
        }
        Thread({ record() }, "AMATV-log-recorder").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start()
        }
    }

    internal fun redact(line: String): String = line
        .replace(jwt, "[jwt]")
        .replace(namedSecret) { it.groupValues[1] + "[redacted]" }
        .replace(longSecret, "[redacted]")

    private fun record() {
        while (true) {
            runCatching {
                val process = ProcessBuilder("logcat", "-v", "threadtime", "--pid=${Process.myPid()}")
                    .redirectErrorStream(true).start()
                synchronized(outputLock) { output = openCurrent() }
                var written = current().length()
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { raw ->
                            val bytes = (redact(raw) + "\n").toByteArray(Charsets.UTF_8)
                            synchronized(outputLock) {
                                if (written + bytes.size > MAX_FILE_BYTES) {
                                    closeOutput()
                                    previous().delete()
                                    current().renameTo(previous())
                                    output = openCurrent()
                                    written = 0L
                                }
                                checkNotNull(output).write(bytes)
                                written += bytes.size
                                if (++pendingLines == 64) flushPending()
                                else if (pendingLines == 1) scheduleFlush()
                            }
                        }
                    }
                } finally {
                    synchronized(outputLock) { closeOutput() }
                    process.destroy()
                }
            }.onFailure { Log.w(TAG, "Log capture stopped: ${it.javaClass.simpleName}") }
            Thread.sleep(5_000L)
        }
    }

    private fun scheduleFlush() {
        flushTask = flushTimer.schedule({
            synchronized(outputLock) {
                try {
                    flushPending()
                } catch (failure: IOException) {
                    Log.w(TAG, "Periodic log flush failed", failure)
                }
            }
        }, 1L, TimeUnit.SECONDS)
    }

    private fun flushPending() {
        flushTask?.cancel(false)
        flushTask = null
        pendingLines = 0
        output?.flush()
    }

    private fun closeOutput() {
        val stream = output
        output = null
        flushPending()
        stream?.close()
    }

    private fun current() = File(directory, "current.log")
    private fun previous() = File(directory, "previous.log")
    private fun openCurrent(): OutputStream = FileOutputStream(current(), true).buffered(64 * 1024)

    /**
     * Writes the recorded log to the shared Download folder (Android 10+), or to the app's
     * external files folder on older systems. Returns where it went, for the Settings page.
     */
    fun export(context: Context): String {
        synchronized(outputLock) { flushPending() }
        val name = "AMATV-log-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date()) + ".txt"
        val header = buildString {
            appendLine("AM Folio ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT} · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Exported ${Date()}")
            appendLine()
        }.toByteArray(Charsets.UTF_8)
        fun write(out: OutputStream) = out.use { stream ->
            stream.write(header)
            listOf(previous(), current()).filter(File::isFile).forEach { file -> file.inputStream().use { it.copyTo(stream) } }
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = context.contentResolver
            val uri = checkNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)) { "Download folder unavailable" }
            write(checkNotNull(resolver.openOutputStream(uri)) { "Download folder unavailable" })
            "${Environment.DIRECTORY_DOWNLOADS}/$name"
        } else {
            val folder = checkNotNull(context.getExternalFilesDir(null)) { "External storage unavailable" }
            val target = File(folder, name)
            write(FileOutputStream(target))
            target.absolutePath
        }
    }
}
