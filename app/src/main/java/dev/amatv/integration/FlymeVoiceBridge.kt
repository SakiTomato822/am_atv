package dev.amatv.integration

import android.content.Context
import android.os.Binder
import android.os.DeadObjectException
import android.os.Parcel
import android.util.Log
import dev.amatv.BuildConfig
import dev.amatv.integration.FlymeVoiceProtocol.EX_CALLBACK
import dev.amatv.integration.FlymeVoiceProtocol.SEMANTIC_LISTENER
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

internal class FlymeVoiceBridge(
    private val context: Context,
    private val preferences: PlaybackPreferences,
    private val mediaSource: FlymeMediaSource,
) {
    enum class Phase { Off, Connecting, Connected, Declared, Unavailable }
    data class Status(
        val enabled: Boolean,
        val phase: Phase,
        val lastSemanticAt: Long? = null,
        val lastSemantic: String = "",
        val announcementsEnabled: Boolean = true,
    )

    private data class Request(val enabled: Boolean, val revision: Long = 0)

    private val request = MutableStateFlow(Request(preferences.flymeVoiceEnabled))
    private val mutableStatus = MutableStateFlow(
        Status(request.value.enabled, if (request.value.enabled) Phase.Connecting else Phase.Off,
            announcementsEnabled = preferences.flymeVoiceAnnouncements),
    )
    val status = mutableStatus.asStateFlow()

    // MediaCenter exposes registerEx, but has no Ex-only unregister operation. Reuse one
    // Binder so toggling voice never accumulates entries in its RemoteCallbackList.
    @Volatile private var onAction: ((Int, String?, String?) -> Unit)? = null
    private val exCallback = object : Binder() {
        init { attachInterface(null, EX_CALLBACK) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..2 || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(EX_CALLBACK)
            if (code == 2) {
                // onExAction returns a nullable IExContent. The voice bridge supplies no widget lists.
                reply.writeNoException()
                reply.writeInt(0)
                return true
            }
            val type = data.readInt()
            val method = data.readString()
            val payload = data.readString()
            data.readStrongBinder()
            onAction?.invoke(type, method, payload)
            reply.writeNoException()
            reply.writeString(null)
            return true
        }
    }

    fun setEnabled(value: Boolean) {
        val previous = request.value
        if (value == previous.enabled) return
        preferences.flymeVoiceEnabled = value
        mutableStatus.update { it.copy(enabled = value, phase = if (value) Phase.Connecting else Phase.Off) }
        request.value = Request(value, previous.revision + 1)
        mediaSource.setVoiceEnabled(value)
    }

    fun setAnnouncementsEnabled(value: Boolean) {
        preferences.flymeVoiceAnnouncements = value
        mutableStatus.update { it.copy(announcementsEnabled = value) }
    }

    fun start(scope: CoroutineScope, execute: suspend (FlymeVoiceCommand) -> FlymeVoiceFeedback?) =
        startWithFeedback(scope) { command, _ -> execute(command) }

    fun startWithFeedback(
        scope: CoroutineScope,
        execute: suspend (FlymeVoiceCommand, (FlymeVoiceFeedback) -> Unit) -> FlymeVoiceFeedback?,
    ) {
        mediaSource.setVoiceEnabled(request.value.enabled)
        scope.launch {
            request.combine(mediaSource.voiceSession, ::Pair).collectLatest { (active, session) ->
                if (active.enabled) {
                    if (session == null) setPhase(active, Phase.Unavailable)
                    else runSession(active, session, execute)
                }
            }
        }
    }

    private suspend fun runSession(
        active: Request,
        session: FlymeMediaSource.VoiceSession,
        execute: suspend (FlymeVoiceCommand, (FlymeVoiceFeedback) -> Unit) -> FlymeVoiceFeedback?,
    ) {
        val commands = Channel<FlymeVoiceCommand>(Channel.UNLIMITED)
        val registration = session.registration
        val broker = session.broker
        var declarationRequested = false
        var step = "registerEx"
        fun isCurrent() = request.value == active && mediaSource.voiceSession.value === session
        onAction = { type, method, data ->
            if (isCurrent()) receiveAction(type, method, data, commands)
        }
        setPhase(active, Phase.Connected)
        try {
            withContext(Dispatchers.IO) {
                broker.call(step, context.packageName, exCallback)
                step = "declareVrSemanticsCapability"
                val payload = FlymeVoiceProtocol.declaration(context.packageName, BuildConfig.VERSION_NAME)
                declarationRequested = true
                check(broker.call(step, payload.toString(), registration.token).data == "true") {
                    "MediaCenter did not acknowledge declaration"
                }
            }
            Log.i(TAG, "V4 voice declaration acknowledged using ${registration.path} registration")
            setPhase(active, Phase.Declared)
            commands.receiveAsFlow().collectLatest { command ->
                if (isCurrent()) {
                    try {
                        coroutineScope {
                            val announcements = Channel<FlymeVoiceFeedback>(Channel.UNLIMITED)
                            launch {
                                for (feedback in announcements) {
                                    if (!feedback.failure && !status.value.announcementsEnabled) continue
                                    try {
                                        Log.i(TAG, "tts=${feedback.text}")
                                        val response = withContext(Dispatchers.IO) {
                                            broker.call(FlymeVoiceProtocol.SEND_TTS,
                                                FlymeVoiceProtocol.ttsResult(feedback.text).toString(), registration.token)
                                        }
                                        Log.i(TAG, "ttsReceipt ${response.describe()}")
                                        check(response.data == "true") { "MediaCenter rejected TTS enqueue" }
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: DeadObjectException) {
                                        Log.w(TAG, "V4 voice feedback lost the OpenAPI Binder", error)
                                        mediaSource.voiceConnectionDied(session)
                                        break
                                    } catch (error: Exception) {
                                        Log.w(TAG, "TTS enqueue failed", error)
                                    }
                                }
                            }
                            try {
                                execute(command) { announcements.trySend(it) }?.let { announcements.send(it) }
                            } finally {
                                announcements.close()
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: DeadObjectException) {
                        Log.w(TAG, "V4 voice feedback lost the OpenAPI Binder", error)
                        mediaSource.voiceConnectionDied(session)
                    } catch (error: Exception) {
                        Log.w(TAG, "Command failed: $command", error)
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: DeadObjectException) {
            declarationRequested = false
            Log.w(TAG, "V4 $step lost the OpenAPI Binder", error)
            mediaSource.voiceConnectionDied(session)
        } catch (error: Exception) {
            Log.w(TAG, "V4 $step failed", error)
        } finally {
            onAction = null
            commands.cancel()
            setPhase(active, Phase.Unavailable)
            if (declarationRequested) withContext(NonCancellable + Dispatchers.IO) {
                if (mediaSource.voiceSession.value === session) cancelDeclaration(session)
            }
        }
    }

    private fun cancelDeclaration(session: FlymeMediaSource.VoiceSession) {
        try {
            val payload = FlymeVoiceProtocol.channelInfo(context.packageName, BuildConfig.VERSION_NAME)
            check(session.broker.call("cancelVrSemanticsCapability", payload.toString(), session.registration.token).data == "true") {
                "MediaCenter did not acknowledge cancellation"
            }
        } catch (error: DeadObjectException) {
            Log.w(TAG, "V4 cancelVrSemanticsCapability lost the OpenAPI Binder", error)
            mediaSource.voiceConnectionDied(session)
        } catch (error: Exception) {
            Log.w(TAG, "V4 cancelVrSemanticsCapability failed", error)
        }
    }

    private fun setPhase(active: Request, phase: Phase) {
        if (request.value == active) mutableStatus.update { it.copy(phase = phase) }
    }

    private fun receiveAction(type: Int, method: String?, data: String?, commands: Channel<FlymeVoiceCommand>) {
        val header = "onAction type=$type method=$method"
        if (data == null || data.length <= 900) {
            Log.i(TAG, "$header data=$data")
        } else {
            var start = 0
            while (start < data.length) {
                var end = (start + 900).coerceAtMost(data.length)
                if (end < data.length && data[end - 1].isHighSurrogate()) end--
                Log.i(TAG, "$header data[$start..$end]=${data.substring(start, end)}")
                start = end
            }
        }
        if (type == 4 && method == FlymeVoiceProtocol.TTS_LISTENER && data != null) {
            try {
                Log.i(TAG, "ttsPlayback status=${JSONObject(data).getInt("status")}")
            } catch (error: JSONException) {
                Log.w(TAG, "Invalid TTS playback receipt", error)
            }
            return
        }
        if (type != 2 || method != SEMANTIC_LISTENER || data == null) {
            Log.i(TAG, "command=ignored (callback type, method or data)")
            return
        }
        val preview = if (data.length > 160) data.take(159) + "…" else data
        mutableStatus.update { it.copy(lastSemanticAt = System.currentTimeMillis(), lastSemantic = preview) }
        val command = try {
            FlymeVoiceProtocol.parseSemantic(data)
        } catch (error: JSONException) {
            Log.w(TAG, "command=ignored (invalid semantic JSON)", error)
            return
        }
        Log.i(TAG, "command=$command")
        if (command !is FlymeVoiceCommand.Ignored) commands.trySend(command)
    }

    companion object {
        const val TAG = "AMATV-Voice"
    }
}
