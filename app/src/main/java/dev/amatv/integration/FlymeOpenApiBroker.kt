package dev.amatv.integration

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import dev.amatv.integration.FlymeMediaSource.Companion.rpc
import dev.amatv.integration.FlymeVoiceProtocol.BROKER
import dev.amatv.integration.FlymeVoiceProtocol.CONNECT_CALLBACK
import dev.amatv.integration.FlymeVoiceProtocol.SERVICE
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/** All binding state is owned by the media worker. Each new bind has its own callbacks. */
internal class FlymeOpenApiBinding(
    private val context: Context,
    private val worker: Handler,
    private val action: String,
    private val path: FlymeMediaSource.RegistrationPath,
    private val onConnected: (IBinder, Boolean) -> Unit,
    private val onDisconnected: () -> Unit,
    private val onUnavailable: () -> Unit,
) {
    private var connection: Connection? = null
    private var remote: IBinder? = null
    private var reconnectReason: String? = null
    private var reconnects = 0

    fun bind() {
        check(connection == null)
        val next = Connection()
        connection = next
        val bound = try {
            context.bindService(Intent(action).setPackage(OPENAPI_PACKAGE), next, Context.BIND_AUTO_CREATE)
        } catch (error: SecurityException) {
            unbind()
            Log.w(TAG, "$path bindService denied", error)
            onUnavailable()
            return
        }
        Log.i(TAG, "$path bindService=$bound")
        if (!bound) {
            unbind()
            onUnavailable()
        }
    }

    fun unbind() {
        val current = connection ?: return
        connection = null
        remote = null
        context.unbindService(current)
    }

    fun disconnected(reason: String) {
        if (remote == null) return
        remote = null
        reconnectReason = reason
        Log.w(TAG, "$path OpenAPI disconnected: $reason")
        onDisconnected()
    }

    private inner class Connection : ServiceConnection {
        private fun post(action: () -> Unit) {
            worker.post { if (connection === this) action() }
        }

        override fun onServiceConnected(name: ComponentName, binder: IBinder) = post {
            if (remote != binder) {
                disconnected("service Binder replaced")
                remote = binder
                val reason = reconnectReason
                reconnectReason = null
                if (reason != null) Log.i(TAG, "$path reconnect #${++reconnects}: $reason")
                onConnected(binder, reason != null)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) = post {
            disconnected("onServiceDisconnected")
        }

        override fun onBindingDied(name: ComponentName) = post {
            disconnected("onBindingDied")
            reconnectReason = listOfNotNull(reconnectReason, "onBindingDied").distinct().joinToString(" / ")
            unbind()
            bind()
        }

        override fun onNullBinding(name: ComponentName) = post {
            disconnected("onNullBinding")
            unbind()
            Log.w(TAG, "$path OpenAPI returned a null binding")
            onUnavailable()
        }
    }

    companion object {
        private const val OPENAPI_PACKAGE = "com.ecarx.sdk.openapi"
        private const val TAG = "AM-FlymeMedia"
    }
}

internal class FlymeOpenApiBroker(private val remote: IBinder) {
    suspend fun mainBinder(): IBinder {
        val descriptor = remote.interfaceDescriptor
        check(descriptor == BROKER) { "Unexpected broker descriptor: $descriptor" }
        val ready = CompletableDeferred<Unit>()
        val callback = ConnectCallback(ready)
        val registered = rpc(remote, BROKER, 2, { writeStrongBinder(callback) }, { readInt() != 0 })
        Log.i(TAG, "V4 addRemoteServiceCallback=$registered")
        try {
            rpc(remote, BROKER, 1, { writeStringArray(arrayOf(SERVICE)) }, { Unit })
            Log.i(TAG, "V4 init([$SERVICE]): completed")
            val available = rpc(remote, BROKER, 9, {}, { createStringArrayList() })
            if (available?.contains(SERVICE) != true && registered &&
                withTimeoutOrNull(READY_TIMEOUT) { ready.await() } == null
            ) {
                Log.w(TAG, "V4 MediaCenter readiness callback timed out")
            }
            return checkNotNull(call("getMainBinder", "NoParam").binder) { "MediaCenter returned no Binder" }
        } finally {
            if (registered && remote.isBinderAlive) {
                val removed = rpc(remote, BROKER, 3, { writeStrongBinder(callback) }, { readInt() != 0 })
                Log.i(TAG, "V4 removeRemoteServiceCallback=$removed")
            }
        }
    }

    fun call(method: String, param: String, binder: IBinder? = null): FlymeVoiceProtocol.Reply {
        val response = rpc(remote, BROKER, if (binder == null) 4 else 6, {
            writeInt(1)
            FlymeVoiceProtocol.writeMessage(method, param, ::writeString, ::writeByteArray)
            if (binder != null) writeStrongBinder(binder)
        }, { FlymeVoiceProtocol.readReply(this) })
        Log.i(TAG, "V4 $method: ${response?.describe() ?: "no reply"}")
        check(response?.accepted == true) { "EAS rejected $method" }
        return response
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
        const val ACTION = "com.ecarx.easframework.intent.action.EASFRAMEWORK"
        private const val TAG = "AM-FlymeMedia"
        private const val READY_TIMEOUT = 4_000L
    }
}
