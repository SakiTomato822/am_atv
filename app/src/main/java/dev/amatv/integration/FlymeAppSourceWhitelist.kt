package dev.amatv.integration

import android.content.Context
import android.os.Binder
import android.os.Handler
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import dev.amatv.R
import java.io.ByteArrayOutputStream

/** Owned by the media worker, including callback-triggered reads and writes. */
internal class FlymeAppSourceWhitelist(
    private val context: Context,
    private val worker: Handler,
) {
    private var broker: FlymeOpenApiBroker? = null
    private var subscribed = false
    private var disabled = false
    private val throttle = AppSourceWriteThrottle()
    private val checkConfig = Runnable { checkConfig() }

    private val callback = object : Binder() {
        init { attachInterface(null, FlymeVoiceProtocol.EX_CALLBACK) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..2 || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(FlymeVoiceProtocol.EX_CALLBACK)
            val id = data.readInt()
            val method = data.readString()
            if (code == 2 && ((id == 526 && method == "onAppSourceInfoChange") ||
                    (id == 527 && method == "onCustomAppSourceInfoChange"))
            ) {
                worker.post(checkConfig)
            }
            reply.writeNoException()
            if (code == 2) reply.writeInt(0) else reply.writeString(null)
            return true
        }
    }

    fun start(connected: FlymeOpenApiBroker) {
        if (disabled) return
        broker = connected
        try {
            connected.callSessionController("register", callback = callback)
            subscribed = true
        } catch (error: Exception) {
            Log.w(TAG, "appSource whitelist: callback registration failed ${error.message}")
        }
        checkConfig()
    }

    fun onFocus() {
        if (!subscribed && broker != null && !disabled) worker.post(checkConfig)
    }

    fun disconnect() {
        worker.removeCallbacks(checkConfig)
        broker = null
        subscribed = false
    }

    fun release() {
        val connected = broker
        val registered = subscribed
        disconnect()
        if (connected != null && registered) {
            try {
                connected.callSessionController("unregister", callback = callback)
            } catch (error: Exception) {
                Log.w(TAG, "appSource whitelist: callback removal failed ${error.message}")
            }
        }
    }

    fun unavailable(reason: String) {
        if (disabled) return
        disabled = true
        worker.removeCallbacks(checkConfig)
        Log.w(TAG, "appSource whitelist: write failed $reason")
    }

    private fun checkConfig() {
        val connected = broker ?: return
        if (disabled) return
        worker.removeCallbacks(checkConfig)
        try {
            val config = checkNotNull(connected.callSessionController("getAppSourceConfig")) {
                "getAppSourceConfig returned no list"
            }
            val launcherIcon = context.applicationInfo.icon
            val (size, merged) = FlymeAppSourceConfig.appendIfMissing(
                config, context.packageName, context.getString(R.string.flyme_app_name),
                "android.resource://${context.packageName}/$launcherIcon",
            )
            if (size == 0) {
                Log.w(TAG, "appSource whitelist: write failed existing list is empty")
                return
            }
            if (merged == null) {
                Log.i(TAG, "appSource whitelist: present")
                return
            }
            val now = SystemClock.elapsedRealtime()
            val wait = throttle.remainingMs(now)
            if (wait > 0) {
                // Re-read at the deadline because mediacontrol may rewrite the list again.
                worker.postDelayed(checkConfig, wait)
                return
            }
            throttle.recordWrite(now)
            connected.callSessionController("setAppSourceConfig", merged)
            Log.i(TAG, "appSource whitelist: appended ($size→${size + 1})")
        } catch (error: Exception) {
            unavailable(error.message ?: error.javaClass.simpleName)
            release()
        }
    }

    private companion object {
        const val TAG = "AM-FlymeMedia"
    }
}

internal class AppSourceWriteThrottle {
    private var nextWriteAtMs = 0L

    fun remainingMs(nowMs: Long): Long = (nextWriteAtMs - nowMs).coerceAtLeast(0L)

    fun recordWrite(nowMs: Long) {
        nextWriteAtMs = nowMs + 5_000L
    }
}

internal object FlymeAppSourceConfig {
    // Controller.ControlRequest.appSourceInfo and Mediabean.AppSourceInfo.packageName.
    private const val SOURCE_FIELD = 20
    private const val SOURCE_TAG = (SOURCE_FIELD shl 3) or 2
    private const val PACKAGE_TAG = (1 shl 3) or 2

    data class Merge(val size: Int, val updated: ByteArray?)

    fun appendIfMissing(config: ByteArray, packageName: String, name: String, icon: String): Merge {
        var size = 0
        val input = CodedInputStream.newInstance(config)
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == SOURCE_TAG) {
                size++
                if (sourcePackage(input.readByteArray()) == packageName) return Merge(size, null)
            } else {
                check(input.skipField(tag)) { "Invalid app source config field" }
            }
        }
        if (size == 0) return Merge(0, null)

        val source = encode {
            writeString(1, packageName)
            writeString(2, icon)
            writeString(3, name)
            writeInt32(4, FlymeMediaSource.ONLINE)
            writeInt32(5, 1)
            // isRunning and isSupportMultipleOpen default to false.
        }
        // Keep every original entry and field byte-for-byte and in the same order.
        return Merge(size, config + encode { writeByteArray(SOURCE_FIELD, source) })
    }

    private fun sourcePackage(source: ByteArray): String {
        val input = CodedInputStream.newInstance(source)
        var packageName = ""
        while (!input.isAtEnd) {
            val tag = input.readTag()
            if (tag == PACKAGE_TAG) packageName = input.readStringRequireUtf8()
            else check(input.skipField(tag)) { "Invalid app source field" }
        }
        return packageName
    }

    private fun encode(write: CodedOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        CodedOutputStream.newInstance(bytes).apply { write(); flush() }
        return bytes.toByteArray()
    }
}
