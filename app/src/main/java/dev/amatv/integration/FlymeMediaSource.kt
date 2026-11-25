package dev.amatv.integration

import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Binder
import android.os.DeadObjectException
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.os.Process
import android.os.SystemClock
import android.util.Log
import dev.amatv.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Small interoperability client for the 06's observed OpenAPI Binder contract.
 * Uses our real UID/package; the system still decides whether to grant access.
 * No vendor classes, credentials or binaries are embedded. See docs/flyme-auto.md.
 */
internal class FlymeMediaSource(
    private val context: Context,
    private val autoBind: Boolean = true,
    private val mediaCenterVersion: () -> Long = {
        context.packageManager.getPackageInfo("ecarx.xsf.mediacenter", 0).longVersionCode
    },
) {
    private val thread = HandlerThread("AM-FlymeMedia").apply { start() }
    private val worker = Handler(thread.looper)
    private val appSourceWhitelist = FlymeAppSourceWhitelist(context, worker)
    private val workerDispatcher = worker.asCoroutineDispatcher()
    private val scope = CoroutineScope(workerDispatcher)
    private val main = Handler(context.mainLooper)
    enum class RegistrationPath { V3, V4 }
    enum class ConnectionState { Disconnected, Connecting, Connected }
    data class Status(val path: RegistrationPath, val state: ConnectionState)
    private val mutableStatus = MutableStateFlow(Status(RegistrationPath.V3, ConnectionState.Disconnected))
    val status = mutableStatus.asStateFlow()
    class Registration(val media: IBinder, val token: IBinder, val path: RegistrationPath)
    class VoiceSession(val broker: FlymeOpenApiBroker, val registration: Registration)
    private val mutableVoiceSession = MutableStateFlow<VoiceSession?>(null)
    val voiceSession = mutableVoiceSession.asStateFlow()
    private var registration: Registration? = null
    private var broker: FlymeOpenApiBroker? = null
    private var brokerMedia: IBinder? = null
    private var brokerJob: Job? = null
    @Volatile private var callbacks: PlaybackMediaSession.Callbacks? = null
    @Volatile private var snapshot: PlaybackSnapshot? = null
    @Volatile private var released = false
    private var started = false
    private var brokerStarted = false
    private var voiceRequested = false
    private var wantsPlay = false
    private var metadataKey: String? = null
    private var lastProgressTime = 0L
    private var lastProgressPosition = 0L
    private val pendingSnapshot = AtomicReference<PlaybackSnapshot?>()
    private val publishQueued = AtomicBoolean(false)

    private val launchIntent by lazy {
        context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }

    internal val infoBinder: Binder = playbackInfoBinder { snapshot }

    private fun playbackInfoBinder(readSnapshot: () -> PlaybackSnapshot?): Binder = object : Binder() {
        init { attachInterface(null, INFO) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code == INTERFACE_TRANSACTION) {
                reply?.writeString(INFO)
                return true
            }
            if (code !in 1..35 || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(INFO)
            val state = readSnapshot()
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
                    state?.isBuffering == true -> 3 // vendor BUFFERING
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
                31 -> reply.writeInt(0) // MediaCenter maps any nonzero value to VIP required
                32 -> reply.writeInt(-1)
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
                    if (accepted) main.post {
                        if (!released) try {
                            launchIntent?.send()
                        } catch (error: PendingIntent.CanceledException) {
                            Log.w(TAG, "Unable to open selected media source", error)
                        }
                    }
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

    private val v3Binding = FlymeOpenApiBinding(
        context, worker, "ecarx.intent.action.OpenAPIService", RegistrationPath.V3,
        onConnected = ::connectV3,
        onDisconnected = ::forgetRegistration,
        onUnavailable = { selectPath(v3HasBinder = false) },
    )
    private val v4Binding = FlymeOpenApiBinding(
        context, worker, FlymeOpenApiBroker.ACTION, RegistrationPath.V4,
        onConnected = ::connectV4,
        onDisconnected = ::disconnectV4,
        onUnavailable = {
            if (status.value.path == RegistrationPath.V4) {
                appSourceWhitelist.unavailable("V4 MediaCenter Binder unavailable")
            }
            disconnectV4()
        },
    )

    fun setCallbacks(value: PlaybackMediaSession.Callbacks?) {
        callbacks = value
        if (value != null && autoBind) worker.post {
            if (!released && !started) {
                started = true
                startV3()
            }
        }
    }

    fun setVoiceEnabled(enabled: Boolean) = worker.post {
        voiceRequested = enabled
        if (!released && enabled && registration?.path == RegistrationPath.V3) startBroker()
    }

    fun voiceConnectionDied(session: VoiceSession) = worker.post {
        if (!released && broker === session.broker) v4Binding.disconnected("DeadObjectException")
    }

    fun playbackRequested() = worker.post {
        if (!released) {
            wantsPlay = true
            safely {
                if (requestFocus()) snapshot?.let(::publishOnWorker)
            }
        }
    }

    fun playbackPaused() = worker.post { wantsPlay = false }

    fun publish(value: PlaybackSnapshot) {
        if (released) return
        snapshot = value
        pendingSnapshot.set(value)
        if (publishQueued.compareAndSet(false, true)) worker.post {
            publishQueued.set(false)
            pendingSnapshot.getAndSet(null)?.let { if (!released) safely { publishOnWorker(it) } }
        }
    }

    fun clear() {
        snapshot = null
        pendingSnapshot.set(null)
        worker.post {
            wantsPlay = false
            metadataKey = null
            safely {
                registration?.let { current ->
                    sendPlaybackInfo(current, null)
                }
            }
        }
    }

    fun release() {
        released = true
        callbacks = null
        pendingSnapshot.set(null)
        worker.post {
            appSourceWhitelist.release()
            registration?.let(::unregister)
            scope.cancel()
            v3Binding.unbind()
            v4Binding.unbind()
            thread.quitSafely()
        }
    }

    private fun startV3() {
        val version = try {
            mediaCenterVersion()
        } catch (error: PackageManager.NameNotFoundException) {
            Log.i(TAG, "V3 MediaCenter package unavailable", error)
            selectPath(v3HasBinder = false)
            return
        }
        if (version == MEDIA_CENTER_VERSION_CODE) {
            updateStatus(ConnectionState.Connecting)
            v3Binding.bind()
        } else {
            Log.i(TAG, "V3 unsupported MediaCenter version: $version")
            selectPath(v3HasBinder = false)
        }
    }

    private fun connectV3(pool: IBinder, reconnecting: Boolean) {
        if (released) return
        updateStatus(ConnectionState.Connecting)
        try {
            check(pool.interfaceDescriptor == POOL) { "Unsupported OpenAPI interface" }
            val remote = rpc(pool, POOL, 2, {
                writeInt(Process.myPid()); writeInt(Process.myUid())
                writeString(context.packageName); writeString("mediacenter")
            }, { readStrongBinder() })
            when (selectPath(v3HasBinder = remote != null)) {
                RegistrationPath.V3 -> {
                    registerMedia(checkNotNull(remote), RegistrationPath.V3, reconnecting)
                    if (voiceRequested) startBroker()
                    updateVoiceSession()
                }
                RegistrationPath.V4 -> Log.i(TAG, "V3 getService (2) returned no MediaCenter Binder")
            }
        } catch (error: DeadObjectException) {
            Log.w(TAG, "V3 connection lost during MediaCenter setup", error)
            v3Binding.disconnected("DeadObjectException")
        } catch (error: Exception) {
            Log.w(TAG, "V3 MediaCenter setup failed", error)
            updateStatus(ConnectionState.Disconnected)
        }
    }

    private fun selectPath(v3HasBinder: Boolean): RegistrationPath {
        val path = registrationPath(v3HasBinder)
        if (!released && path == RegistrationPath.V4) {
            v3Binding.unbind()
            mutableStatus.value = Status(path, ConnectionState.Connecting)
            val media = brokerMedia
            if (media != null) completeV4Registration(media, reconnecting = true)
            else if (!brokerStarted) startBroker()
            else updateStatus(ConnectionState.Disconnected)
        }
        return path
    }

    private fun startBroker() {
        if (brokerStarted) return
        brokerStarted = true
        v4Binding.bind()
    }

    private fun connectV4(remote: IBinder, reconnecting: Boolean) {
        if (released) return
        if (status.value.path == RegistrationPath.V4) updateStatus(ConnectionState.Connecting)
        brokerJob = scope.launch {
            val connected = FlymeOpenApiBroker(remote)
            try {
                val media = connected.mainBinder()
                broker = connected
                brokerMedia = media
                if (status.value.path == RegistrationPath.V4) completeV4Registration(media, reconnecting)
                else updateVoiceSession()
            } catch (error: CancellationException) {
                throw error
            } catch (error: DeadObjectException) {
                Log.w(TAG, "V4 connection lost during MediaCenter setup", error)
                v4Binding.disconnected("DeadObjectException")
            } catch (error: Exception) {
                Log.w(TAG, "V4 MediaCenter setup failed", error)
                if (status.value.path == RegistrationPath.V4) {
                    appSourceWhitelist.unavailable("V4 MediaCenter Binder unavailable")
                }
                disconnectV4()
            }
        }
    }

    private fun completeV4Registration(media: IBinder, reconnecting: Boolean) {
        try {
            registerMedia(media, RegistrationPath.V4, reconnecting)
            updateVoiceSession()
            if (registration?.path == RegistrationPath.V4) {
                appSourceWhitelist.start(checkNotNull(broker))
            }
        } catch (error: DeadObjectException) {
            Log.w(TAG, "V4 connection lost during MediaCenter registration", error)
            v4Binding.disconnected("DeadObjectException")
        } catch (error: Exception) {
            Log.w(TAG, "V4 MediaCenter registration failed", error)
            updateStatus(ConnectionState.Disconnected)
        }
    }

    private fun disconnectV4() {
        appSourceWhitelist.disconnect()
        brokerJob?.cancel()
        broker = null
        brokerMedia = null
        mutableVoiceSession.value = null
        if (status.value.path == RegistrationPath.V4) forgetRegistration()
    }

    private fun forgetRegistration() {
        registration = null
        metadataKey = null
        lastProgressTime = 0L
        lastProgressPosition = 0L
        mutableVoiceSession.value = null
        updateStatus(ConnectionState.Disconnected)
    }

    private fun updateVoiceSession() {
        val connected = broker ?: return
        val current = registration ?: return
        if (brokerMedia != current.media) {
            Log.w(TAG, "V4 broker and ${current.path} registration returned different MediaCenter Binders")
            return
        }
        mutableVoiceSession.value = VoiceSession(connected, current)
    }

    private fun registerMedia(remote: IBinder, path: RegistrationPath, reconnecting: Boolean) {
        check(registration == null) { "MediaCenter already registered" }
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
            updateCurrentSourceType(current, "registered")
        } catch (error: DeadObjectException) {
            throw error
        } catch (error: Exception) {
            unregister(current)
            throw error
        }
        registration = current
        metadataKey = null
        Log.i(TAG, "Registered $path online source (6)")
        safely {
            val latest = snapshot
            if (if (reconnecting) latest?.isPlaying == true else wantsPlay) requestFocus()
            latest?.let(::publishOnWorker)
        }
        if (registration == current) updateStatus(ConnectionState.Connected)
    }

    private fun unregister(current: Registration) {
        if (registration == current) forgetRegistration()
        try {
            check(rpc(current.media, MEDIA, 4, { writeStrongBinder(current.token) }, { readInt() != 0 })) {
                "MediaCenter rejected unregister"
            }
            Log.i(TAG, "${current.path} unregister (4): completed")
        } catch (error: Exception) {
            Log.w(TAG, "${current.path} unregister (4) failed", error)
        }
    }

    private fun requestFocus(): Boolean {
        val current = registration ?: return false
        val accepted = rpc(current.media, MEDIA, 5, { writeStrongBinder(current.token) }, { readInt() != 0 })
        Log.i(TAG, "${current.path} requestPlay (5): accepted=$accepted")
        if (accepted) {
            appSourceWhitelist.onFocus()
            // The session forwards the package/source callback only for its focused token.
            updateCurrentSourceType(current, "focused")
            // MediaCenter only forwards transaction 6 for its focused token.
            // An earlier successful reply can represent metadata it did not forward.
            metadataKey = null
            lastProgressTime = 0L
            lastProgressPosition = 0L
        } else {
            Log.w(TAG, "MediaCenter denied playback focus")
            dispatch { pause() }
        }
        return accepted
    }

    private fun updateCurrentSourceType(current: Registration, phase: String) {
        rpc(current.media, MEDIA, 8, { writeStrongBinder(current.token); writeInt(ONLINE) }, { Unit })
        Log.i(TAG, "${current.path} updateCurrentSourceType (8): ONLINE phase=$phase")
    }

    private fun publishOnWorker(value: PlaybackSnapshot) {
        if (value.isEnded || value.errorMessage != null) wantsPlay = false
        val current = registration ?: return
        val track = value.currentTrack
        val key = "${track?.id}|${track?.title}|${track?.artist}|${track?.album}|${track?.artworkUrl}|${value.durationMs}|${value.isPlaying}|${value.isBuffering}|${value.errorMessage}"
        if (key != metadataKey) {
            sendPlaybackInfo(current, value)
            metadataKey = key
            Log.i(TAG, "${current.path} updateMusicPlaybackState (6): published " +
                "id=${track?.catalogId ?: track?.id} playing=${value.isPlaying} buffering=${value.isBuffering} " +
                "hasArtwork=${!track?.artworkUrl.isNullOrBlank()} durationMs=${value.durationMs}")
        }
        val now = SystemClock.elapsedRealtime()
        val position = value.positionMs.coerceAtLeast(0L)
        if ((value.isPlaying && now - lastProgressTime >= 1_000L) ||
            (!value.isPlaying && position != lastProgressPosition)
        ) {
            rpc(current.media, MEDIA, 10, { writeStrongBinder(current.token); writeLong(position) }, { Unit })
            if (lastProgressTime == 0L) Log.i(TAG, "${current.path} updateCurrentProgress (10): positionMs=$position")
            lastProgressTime = now
            lastProgressPosition = position
        }
    }

    private fun sendPlaybackInfo(current: Registration, value: PlaybackSnapshot?) {
        // The vendor reads PlaybackInfo and PlaybackState separately from the same binder.
        // Keep both reads on the same snapshot so their item IDs agree across a track change.
        val info = playbackInfoBinder { value }
        check(rpc(current.media, MEDIA, 6, {
            writeStrongBinder(current.token); writeStrongBinder(info)
        }, { readInt() != 0 })) { "MediaCenter rejected playback metadata" }
    }

    private fun dispatch(command: PlaybackMediaSession.Callbacks.() -> Unit): Int {
        val receiver = callbacks ?: return 0
        if (released) return 0
        main.post { if (!released && callbacks === receiver) receiver.command() }
        return 1
    }

    private fun updateStatus(state: ConnectionState) {
        mutableStatus.value = status.value.copy(state = state)
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (error: DeadObjectException) {
            val path = status.value.path
            Log.w(TAG, "$path MediaCenter Binder died", error)
            val binding = if (path == RegistrationPath.V4) v4Binding else v3Binding
            binding.disconnected("DeadObjectException")
        } catch (error: Exception) {
            Log.w(TAG, "${status.value.path} native media operation failed", error)
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

        internal fun registrationPath(v3HasBinder: Boolean) =
            if (v3HasBinder) RegistrationPath.V3 else RegistrationPath.V4

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
