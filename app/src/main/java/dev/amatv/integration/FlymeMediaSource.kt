package dev.amatv.integration

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.amatv.R
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Small interoperability client for the 06's observed OpenAPI Binder contract.
 * Uses our real UID/package; the system still decides whether to grant access.
 * No vendor classes, credentials or binaries are embedded. See docs/flyme-auto.md.
 */
internal class FlymeMediaSource(private val context: Context, private val autoBind: Boolean = true) {
    private val thread = HandlerThread("AM-FlymeMedia").apply { start() }
    private val worker = Handler(thread.looper)
    private val workerDispatcher = worker.asCoroutineDispatcher()
    private val main = Handler(context.mainLooper)
    private val mutableStatus = MutableStateFlow("Not connected")
    val status = mutableStatus.asStateFlow()
    enum class V3State { Pending, Registered, NoBinder, Failed }
    enum class RegistrationPath { V3, V4 }
    data class Registration(val media: IBinder, val token: IBinder, val path: RegistrationPath)
    private val mutableRegistration = MutableStateFlow<Registration?>(null)
    val registration = mutableRegistration.asStateFlow()
    private val v3State = MutableStateFlow(if (autoBind) V3State.Pending else V3State.NoBinder)
    @Volatile private var callbacks: PlaybackMediaSession.Callbacks? = null
    @Volatile private var snapshot: PlaybackSnapshot? = null
    @Volatile private var released = false
    private var bound = false
    private var wantsPlay = false
    private var metadataKey: String? = null
    private var lastProgressTime = 0L
    private val pendingSnapshot = AtomicReference<PlaybackSnapshot?>()
    private val publishQueued = AtomicBoolean(false)

    private val launchIntent by lazy {
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    internal val infoBinder: Binder = object : Binder() {
        init { attachInterface(null, INFO) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(INFO)
                return true
            }
            if (code !in 1..35 || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(INFO)
            val state = snapshot
            val track = state?.currentTrack
            reply.writeNoException()
            when (code) {
                1, 33 -> reply.optionalParcelable(launchIntent)
                2 -> reply.writeString(track?.title.orEmpty())
                3 -> reply.writeString(track?.artist.orEmpty())
                4 -> reply.writeString(track?.album.orEmpty())
                5, 6, 12, 14, 30 -> reply.writeString("")
                7 -> reply.writeLong((state?.durationMs?.takeIf { it > 0L } ?: track?.durationMs ?: 0L).coerceAtLeast(0L))
                8, 19, 21, 23, 34 -> reply.writeInt(0)
                9 -> reply.writeInt(ONLINE)
                10, 13, 15, 17 -> reply.optionalParcelable(null)
                11 -> reply.writeInt(when {
                    state?.isBuffering == true -> 3 // vendor PREPARE
                    state?.isPlaying == true -> 1
                    else -> 0 // vendor PAUSED, distinct from Android PlaybackState
                })
                16 -> reply.optionalParcelable(track?.artworkUrl?.let(Uri::parse))
                18 -> reply.writeInt(0)
                20, 22, 28 -> reply.writeInt(0) // unsupported collect/download/loop
                24 -> reply.writeString(track?.catalogId ?: track?.id.orEmpty())
                25 -> reply.writeString(context.getString(R.string.flyme_app_name))
                26 -> reply.writeString("android.resource://${context.packageName}/${R.drawable.ic_launcher}")
                27 -> reply.writeString(context.packageName)
                29 -> reply.writeInt(1) // play/pause control supported
                31, 32 -> reply.writeInt(-1)
                35 -> reply.writeString("audio")
            }
            return true
        }
    }

    internal val clientBinder: Binder = object : Binder() {
        init { attachInterface(null, CLIENT) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(CLIENT)
                return true
            }
            if (code !in 1..35 || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CLIENT)
            reply.writeNoException()
            when (code) {
                1 -> reply.writeInt(dispatch { play() })
                2, 26 -> reply.writeInt(dispatch { pause() })
                3 -> reply.writeInt(if (snapshot?.canSkipNext == true) dispatch { next() } else 0)
                4 -> reply.writeInt(if (snapshot?.canSkipPrevious == true) dispatch { previous() } else 0)
                8 -> { // selecting this source opens its UI; it never silently starts audio
                    val source = data.readInt()
                    val accepted = !released && source == ONLINE && launchIntent != null
                    if (accepted) main.post { if (!released) runCatching { launchIntent?.send() } }
                    reply.writeInt(if (accepted) 1 else 0)
                }
                10 -> reply.writeStrongBinder(infoBinder)
                11 -> reply.writeIntArray(intArrayOf(ONLINE))
                12 -> reply.writeInt(ONLINE)
                13 -> reply.writeLong(snapshot?.positionMs?.coerceAtLeast(0L) ?: 0L)
                14, 28 -> reply.writeInt(-1) // null AIDL list
                29 -> reply.writeStrongBinder(null)
                25 -> {
                    val focusedPackage = data.readString()
                    if (!focusedPackage.isNullOrBlank() && focusedPackage != context.packageName &&
                        snapshot?.isPlaying == true) dispatch { pause() }
                }
                33, 34, 35 -> Unit // unsupported void notifications
                else -> reply.writeInt(0) // explicitly decline unsupported controls
            }
            return true
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            worker.post { if (!released) connectMedia(binder) }
        }
        override fun onServiceDisconnected(name: ComponentName) {
            worker.post { disconnectV3() }
        }
        override fun onBindingDied(name: ComponentName) {
            worker.post {
                if (!released) {
                    unbind()
                    disconnectV3()
                    bind()
                }
            }
        }
        override fun onNullBinding(name: ComponentName) {
            worker.post { v3Unavailable("OpenAPI returned no service") }
        }
    }

    fun setCallbacks(value: PlaybackMediaSession.Callbacks?) {
        callbacks = value
        if (value != null && autoBind) worker.post { if (!released && !bound) bind() }
    }

    fun playbackRequested() = worker.post {
        if (!released) {
            wantsPlay = true
            requestFocus()
        }
    }

    fun playbackPaused() = worker.post { wantsPlay = false }

    fun publish(value: PlaybackSnapshot) {
        if (released) return
        snapshot = value
        pendingSnapshot.set(value)
        if (publishQueued.compareAndSet(false, true)) worker.post {
            publishQueued.set(false)
            pendingSnapshot.getAndSet(null)?.let { if (!released) publishOnWorker(it) }
        }
    }

    fun clear() {
        snapshot = null
        pendingSnapshot.set(null)
        worker.post {
            wantsPlay = false
            metadataKey = null
            safely {
                mutableRegistration.value?.let { current ->
                    rpc(current.media, MEDIA, 6,
                        { writeStrongBinder(current.token); writeStrongBinder(infoBinder) }, { readInt() })
                }
            }
        }
    }

    fun release() {
        released = true
        callbacks = null
        pendingSnapshot.set(null)
        worker.post {
            mutableRegistration.value?.let(::unregister)
            unbind()
            thread.quitSafely()
        }
    }

    private fun bind() {
        v3State.value = V3State.Pending
        try {
            val version = context.packageManager.getPackageInfo("ecarx.xsf.mediacenter", 0).longVersionCode
            check(version == MEDIA_CENTER_VERSION_CODE) { "Unsupported MediaCenter version: $version" }
            Log.i(TAG, "V3 binding OpenAPI service")
            if (mutableRegistration.value == null) report("V3 connecting")
            bound = context.bindService(Intent("ecarx.intent.action.OpenAPIService")
                .setPackage("com.ecarx.sdk.openapi"), connection, Context.BIND_AUTO_CREATE)
            if (!bound) v3Unavailable("OpenAPI bindService=false")
        } catch (error: Exception) {
            v3State.value = V3State.Failed
            Log.w(TAG, "V3 bind failed: ${error.message}", error)
            if (mutableRegistration.value == null) report("Native media unavailable: V3 ${error.message}")
        }
    }

    internal fun connectMedia(pool: IBinder) {
        val existing = mutableRegistration.value
        if (existing != null) {
            Log.i(TAG, "V3 registerInMusic skipped: ${existing.path} already registered")
            return
        }
        try {
            check(pool.interfaceDescriptor == POOL) { "Unsupported OpenAPI interface" }
            val remote = rpc(pool, POOL, 2, {
                writeInt(Process.myPid()); writeInt(Process.myUid())
                writeString(context.packageName); writeString("mediacenter")
            }, { readStrongBinder() })
            if (remote == null) {
                v3Unavailable("getService (2) returned no MediaCenter Binder")
                return
            }
            Log.i(TAG, "V3 getService (2): MediaCenter Binder received")
            registerMedia(remote, RegistrationPath.V3)
            v3State.value = V3State.Registered
        } catch (error: Exception) {
            v3State.value = V3State.Failed
            Log.w(TAG, "V3 connection failed: ${error.message}", error)
            if (mutableRegistration.value == null) report("Native media unavailable: V3 ${error.message}")
        }
    }

    suspend fun awaitV3() = v3State.first { it != V3State.Pending }

    suspend fun registerVoice(remote: IBinder, enabled: () -> Boolean): Registration? = withContext(workerDispatcher) {
        when (voiceRegistrationPath(enabled() && !released, v3State.value)) {
            RegistrationPath.V3 -> mutableRegistration.value
            RegistrationPath.V4 -> mutableRegistration.value ?: try {
                registerMedia(remote, RegistrationPath.V4)
            } catch (error: Exception) {
                report("Native media unavailable: V4 ${error.message}")
                Log.w(TAG, "V4 registration failed", error)
                throw error
            }
            null -> null
        }
    }

    suspend fun unregisterV4() = withContext(workerDispatcher) {
        mutableRegistration.value?.takeIf { it.path == RegistrationPath.V4 }?.let(::unregister)
        Unit
    }

    private fun disconnectV3() {
        if (mutableRegistration.value?.path == RegistrationPath.V3) {
            mutableRegistration.value = null
            metadataKey = null
        }
        if (!released) v3Unavailable("OpenAPI disconnected")
    }

    private fun v3Unavailable(reason: String) {
        v3State.value = V3State.NoBinder
        Log.i(TAG, "V3 unavailable: $reason")
        if (mutableRegistration.value == null) report("Native media unavailable: V3 $reason")
    }

    private fun registerMedia(remote: IBinder, path: RegistrationPath): Registration {
        check(remote.interfaceDescriptor == MEDIA) { "Unsupported MediaCenter interface" }
        val registered = checkNotNull(rpc(remote, MEDIA, 19, {
            writeString(context.packageName); writeStrongBinder(clientBinder)
        }, { readStrongBinder() })) { "MediaCenter registration returned no token" }
        val current = Registration(remote, registered, path)
        Log.i(TAG, "$path registerInMusic (19): token received")
        try {
            rpc(remote, MEDIA, 7, {
                writeStrongBinder(registered); writeIntArray(intArrayOf(ONLINE))
            }, { Unit })
            Log.i(TAG, "$path updateMediaSourceTypeList (7): ONLINE")
            rpc(remote, MEDIA, 8, { writeStrongBinder(registered); writeInt(ONLINE) }, { Unit })
            Log.i(TAG, "$path updateCurrentSourceType (8): ONLINE")
        } catch (error: Exception) {
            unregister(current)
            throw error
        }
        mutableRegistration.value = current
        metadataKey = null
        report("Registered $path online source (6); source-list visibility needs vehicle verification")
        snapshot?.let(::publishOnWorker)
        if (wantsPlay) requestFocus()
        return current
    }

    private fun unregister(current: Registration) {
        if (mutableRegistration.value == current) {
            mutableRegistration.value = null
            metadataKey = null
        }
        try {
            check(rpc(current.media, MEDIA, 4, { writeStrongBinder(current.token) }, { readInt() != 0 })) {
                "MediaCenter rejected unregister"
            }
            report("${current.path} unregister (4): completed")
        } catch (error: Exception) {
            report("${current.path} unregister (4) failed: ${error.message}")
            Log.w(TAG, "${current.path} unregister (4) failed", error)
        }
    }

    private fun requestFocus(): Unit = safely {
        val current = mutableRegistration.value ?: return@safely
        val accepted = rpc(current.media, MEDIA, 5, { writeStrongBinder(current.token) }, { readInt() != 0 })
        Log.i(TAG, "${current.path} requestPlay (5): accepted=$accepted")
        if (!accepted) {
            report("MediaCenter denied playback focus")
            dispatch { pause() }
        }
    }

    private fun publishOnWorker(value: PlaybackSnapshot): Unit = safely {
        if (value.isEnded || value.errorMessage != null) wantsPlay = false
        val current = mutableRegistration.value ?: return@safely
        val track = value.currentTrack
        val key = "${track?.id}|${track?.title}|${track?.artist}|${track?.album}|${track?.artworkUrl}|${value.durationMs}|${value.isPlaying}|${value.isBuffering}|${value.errorMessage}"
        if (key != metadataKey) {
            check(rpc(current.media, MEDIA, 6, {
                writeStrongBinder(current.token); writeStrongBinder(infoBinder)
            }, { readInt() != 0 })) { "MediaCenter rejected playback metadata" }
            metadataKey = key
            Log.i(TAG, "${current.path} updateMusicPlaybackState (6): published")
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastProgressTime >= 1000L) {
            rpc(current.media, MEDIA, 10, { writeStrongBinder(current.token); writeLong(value.positionMs.coerceAtLeast(0L)) }, { Unit })
            lastProgressTime = now
        }
    }

    private fun dispatch(command: PlaybackMediaSession.Callbacks.() -> Unit): Int {
        val receiver = callbacks ?: return 0
        if (released) return 0
        main.post { if (!released && callbacks === receiver) receiver.command() }
        return 1
    }

    private fun unbind() {
        if (bound) runCatching { context.unbindService(connection) }
        bound = false
    }

    private fun report(message: String) {
        mutableStatus.value = message
        Log.i(TAG, message)
    }

    private inline fun safely(block: () -> Unit) {
        try { block() } catch (error: Exception) {
            report("Native media unavailable: ${error.message?.take(180) ?: error.javaClass.simpleName}")
            Log.w(TAG, "${mutableRegistration.value?.path} native media operation failed", error)
        }
    }

    companion object {
        private const val TAG = "AM-FlymeMedia"
        internal const val ONLINE = 6
        internal const val MEDIA_CENTER_VERSION_CODE = 26052922L
        internal const val POOL = "com.ecarx.sdk.openapi.IServicePool"
        internal const val MEDIA = "ecarx.xsf.mediacenter.IMediaCenterSvc"
        internal const val CLIENT = "ecarx.xsf.mediacenter.IMusicClient"
        internal const val INFO = "ecarx.xsf.mediacenter.IMusicPlaybackInfo"

        internal fun voiceRegistrationPath(enabled: Boolean, v3: V3State): RegistrationPath? {
            if (!enabled) return null
            return when (v3) {
                V3State.Registered -> RegistrationPath.V3
                V3State.NoBinder -> RegistrationPath.V4
                V3State.Pending, V3State.Failed -> null
            }
        }

        internal fun <T> rpc(remote: IBinder, descriptor: String, code: Int,
            write: Parcel.() -> Unit, read: Parcel.() -> T): T {
            val request = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                request.writeInterfaceToken(descriptor)
                request.write()
                check(remote.transact(code, request, reply, 0)) { "Unsupported transaction $code" }
                reply.readException()
                return reply.read()
            } finally { reply.recycle(); request.recycle() }
        }

        private fun Parcel.optionalParcelable(value: Parcelable?) {
            writeInt(if (value == null) 0 else 1)
            value?.writeToParcel(this, 0)
        }
    }
}
