package dev.amatv.integration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import dev.amatv.BuildConfig
import dev.amatv.integration.FlymeVoiceProtocol.BROKER
import dev.amatv.integration.FlymeVoiceProtocol.CONNECT_CALLBACK
import dev.amatv.integration.FlymeVoiceProtocol.EX_CALLBACK
import dev.amatv.integration.FlymeVoiceProtocol.SEMANTIC_LISTENER
import dev.amatv.integration.FlymeVoiceProtocol.SERVICE
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONException

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
    )

    private data class Request(val enabled: Boolean, val revision: Long = 0)

    private val request = MutableStateFlow(Request(preferences.flymeVoiceEnabled))
    private val mutableStatus = MutableStateFlow(
        Status(request.value.enabled, if (request.value.enabled) Phase.Connecting else Phase.Off),
    )
    val status = mutableStatus.asStateFlow()

    fun setEnabled(value: Boolean) {
        val previous = request.value
        if (value == previous.enabled) return
        preferences.flymeVoiceEnabled = value
        mutableStatus.update { it.copy(enabled = value, phase = if (value) Phase.Connecting else Phase.Off) }
        request.value = Request(value, previous.revision + 1)
    }

    fun start(scope: CoroutineScope, execute: suspend (FlymeVoiceCommand) -> Unit) {
        scope.launch {
            request.collectLatest {
                if (it.enabled) runSession(it, execute)
                else Log.i(TAG, "V4 voice disabled")
            }
        }
    }

    private suspend fun runSession(
        activeRequest: Request,
        execute: suspend (FlymeVoiceCommand) -> Unit,
    ): Unit = coroutineScope {
        val session = this
        val connected = CompletableDeferred<IBinder>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                connected.complete(binder)
            }

            override fun onServiceDisconnected(name: ComponentName) = fail("service disconnected")
            override fun onBindingDied(name: ComponentName) = fail("binding died")
            override fun onNullBinding(name: ComponentName) = fail("service returned a null binding")

            private fun fail(reason: String) {
                Log.w(TAG, "V4 bindService: $reason ($OPENAPI_PACKAGE)")
                session.cancel(reason)
            }
        }
        val commands = Channel<FlymeVoiceCommand>(Channel.UNLIMITED)
        val exCallback = ExCallback { type, method, data ->
            if (request.value == activeRequest && session.isActive) receiveAction(type, method, data, commands)
        }
        val ready = CompletableDeferred<Unit>()
        val readinessCallback = ConnectCallback(ready)
        var bindingRequested = false
        var broker: IBinder? = null
        var readinessRegistered = false
        var registrationRequested = false
        var declarationToken: IBinder? = null
        var path = "V4"
        var step = "bindService"
        try {
            val bound = context.bindService(
                Intent("com.ecarx.easframework.intent.action.EASFRAMEWORK").setPackage(OPENAPI_PACKAGE),
                connection, Context.BIND_AUTO_CREATE,
            )
            bindingRequested = true
            if (!bound) {
                Log.w(TAG, "bindService=false: V4 service unavailable in $OPENAPI_PACKAGE")
                return@coroutineScope
            }
            Log.i(TAG, "V4 bindService=true")
            val remote = checkNotNull(withTimeoutOrNull(STARTUP_TIMEOUT) { connected.await() }) {
                "ServiceConnection timed out"
            }
            broker = remote
            setPhase(activeRequest, Phase.Connected)
            withContext(Dispatchers.IO) {
                step = "init"
                val descriptor = remote.interfaceDescriptor
                check(descriptor == BROKER) { "Unexpected broker descriptor: $descriptor" }
                readinessRegistered = FlymeMediaSource.rpc(remote, BROKER, 2,
                    { writeStrongBinder(readinessCallback) }, { readInt() != 0 })
                Log.i(TAG, "V4 addRemoteServiceCallback=$readinessRegistered")
                FlymeMediaSource.rpc(remote, BROKER, 1, { writeStringArray(arrayOf(SERVICE)) }, { Unit })
                Log.i(TAG, "V4 init([$SERVICE]): completed (void)")
                val available = FlymeMediaSource.rpc(remote, BROKER, 9, {}, { createStringArrayList() })
                if (available?.contains(SERVICE) != true && readinessRegistered &&
                    withTimeoutOrNull(READY_TIMEOUT) { ready.await() } == null
                ) {
                    Log.i(TAG, "V4 MediaCenter readiness callback timed out; checking broker result")
                }
            }
            step = "getMainBinder"
            val mainBinder = withContext(Dispatchers.IO) {
                checkNotNull(brokerCall(remote, step, "NoParam").binder) { "MediaCenter returned no Binder" }
            }
            step = "registerMusic"
            check(withTimeoutOrNull(STARTUP_TIMEOUT) { mediaSource.awaitV3() } != null) {
                "V3 MediaCenter lookup timed out"
            }
            registrationRequested = true
            val registration = checkNotNull(mediaSource.registerVoice(mainBinder) { request.value == activeRequest }) {
                "No MediaCenter registration available: ${mediaSource.status.value}"
            }
            path = registration.path.name
            if (registration.path == FlymeMediaSource.RegistrationPath.V3) {
                check(mainBinder == registration.media) { "V4 and V3 returned different MediaCenter Binders" }
                Log.i(TAG, "V3 registerMusic: reused existing registration")
            } else {
                Log.i(TAG, "V4 registerMusic: registered using broker MediaCenter Binder")
            }
            withContext(Dispatchers.IO) {
                step = "registerEx"
                brokerCall(remote, step, context.packageName, exCallback)
                step = "declareVrSemanticsCapability"
                declarationToken = registration.token
                val payload = FlymeVoiceProtocol.declaration(context.packageName, BuildConfig.VERSION_NAME)
                check(brokerCall(remote, step, payload.toString(), registration.token).data == "true") {
                    "MediaCenter did not acknowledge declaration"
                }
            }
            Log.i(TAG, "V4 voice declaration acknowledged using $path registration")
            setPhase(activeRequest, Phase.Declared)
            launch {
                commands.receiveAsFlow().collectLatest { command ->
                    if (request.value != activeRequest || mediaSource.registration.value != registration) return@collectLatest
                    try {
                        execute(command)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w(TAG, "Command failed: $command", error)
                    }
                }
            }
            mediaSource.registration.first { it != registration }
            Log.w(TAG, "$path registerMusic: registration lost")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w(TAG, "$path $step failed: ${error.message}", error)
        } finally {
            commands.cancel()
            setPhase(activeRequest, Phase.Unavailable)
            withContext(NonCancellable) {
                try {
                    broker?.let { remote ->
                        withContext(Dispatchers.IO) {
                            declarationToken?.let { token ->
                                cleanup("cancelVrSemanticsCapability") {
                                    val payload = FlymeVoiceProtocol.channelInfo(context.packageName, BuildConfig.VERSION_NAME)
                                    check(brokerCall(remote, "cancelVrSemanticsCapability", payload.toString(), token).data == "true")
                                }
                            }
                            if (readinessRegistered) cleanup("removeRemoteServiceCallback") {
                                val removed = FlymeMediaSource.rpc(remote, BROKER, 3,
                                    { writeStrongBinder(readinessCallback) }, { readInt() != 0 })
                                Log.i(TAG, "V4 removeRemoteServiceCallback=$removed")
                            }
                        }
                    }
                } finally {
                    try {
                        if (registrationRequested) mediaSource.unregisterV4()
                    } finally {
                        if (bindingRequested) context.unbindService(connection)
                    }
                }
            }
        }
    }

    private fun setPhase(activeRequest: Request, phase: Phase) {
        if (request.value == activeRequest) mutableStatus.update { it.copy(phase = phase) }
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

    private fun brokerCall(remote: IBinder, method: String, param: String, binder: IBinder? = null): FlymeVoiceProtocol.Reply {
        val response = FlymeMediaSource.rpc(remote, BROKER, if (binder == null) 4 else 6, {
            writeInt(1)
            FlymeVoiceProtocol.writeMessage(method, param, ::writeString, ::writeByteArray)
            if (binder != null) writeStrongBinder(binder)
        }, { FlymeVoiceProtocol.readReply(this) })
        Log.i(TAG, "V4 $method: ${response?.describe() ?: "no reply"}")
        check(response?.accepted == true) { "EAS rejected $method" }
        return response
    }

    private inline fun cleanup(step: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            Log.w(TAG, "V4 $step failed: ${error.message}", error)
        }
    }

    private class ExCallback(private val onAction: (Int, String?, String?) -> Unit) : Binder() {
        init { attachInterface(null, EX_CALLBACK) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code != 1 || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(EX_CALLBACK)
            val type = data.readInt()
            val method = data.readString()
            val payload = data.readString()
            data.readStrongBinder()
            onAction(type, method, payload)
            reply.writeNoException()
            reply.writeString(null)
            return true
        }
    }

    private class ConnectCallback(private val ready: CompletableDeferred<Unit>) : Binder() {
        init { attachInterface(null, CONNECT_CALLBACK) }

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in 1..2 || reply == null) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(CONNECT_CALLBACK)
            val service = data.readString()
            if (code == 2) data.readInt()
            if (code == 1 && service == SERVICE) ready.complete(Unit)
            reply.writeNoException()
            return true
        }
    }

    companion object {
        const val TAG = "AMATV-Voice"
        private const val OPENAPI_PACKAGE = "com.ecarx.sdk.openapi"
        private const val READY_TIMEOUT = 4_000L
        private const val STARTUP_TIMEOUT = 10_000L
    }
}
