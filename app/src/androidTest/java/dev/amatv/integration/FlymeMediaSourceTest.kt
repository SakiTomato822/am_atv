package dev.amatv.integration

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.amatv.model.Track
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Contract tests use real Android Parcels and fake system Binders. */
@RunWith(AndroidJUnit4::class)
class FlymeMediaSourceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun successfulV3RegistersOnceWithoutBindingV4() = runBlocking {
        val services = FakeFlymeServices(context)
        val source = services.startSource()
        val media = FakeFlymeMediaCenter(context)
        try {
            val v3 = services.nextBinding(FakeFlymeServices.V3_ACTION)
            val pool = services.pool(media)
            v3.connect(pool)
            v3.connect(pool)
            source.awaitState(FlymeMediaSource.RegistrationPath.V3, FlymeMediaSource.ConnectionState.Connected)
            assertEquals(listOf(19, 7, 8), media.operations)
            assertEquals(listOf(FakeFlymeServices.V3_ACTION), services.bindActions)
        } finally {
            source.release()
        }
    }

    @Test fun noV3BinderRegistersV4WhileVoiceIsOff() = runBlocking {
        val services = FakeFlymeServices(context)
        val source = services.startSource()
        val media = FakeFlymeMediaCenter(context)
        val broker = FakeFlymeBroker(media)
        try {
            services.nextBinding(FakeFlymeServices.V3_ACTION).connect(services.pool(null))
            services.nextBinding(FlymeOpenApiBroker.ACTION).connect(broker)
            source.awaitState(FlymeMediaSource.RegistrationPath.V4, FlymeMediaSource.ConnectionState.Connected)
            assertEquals(listOf(19, 7, 8), media.operations)
            assertEquals(listOf("getMainBinder"), broker.methods)
        } finally {
            source.release()
        }
    }

    @Test fun reconnectsAndRepublishesWithFocusOnlyWhilePlaying() = runBlocking {
        val services = FakeFlymeServices(context)
        val source = services.startSource()
        val v4 = FlymeMediaSource.RegistrationPath.V4
        val connected = FlymeMediaSource.ConnectionState.Connected
        val disconnected = FlymeMediaSource.ConnectionState.Disconnected
        try {
            source.publish(testFlymeSnapshot(isPlaying = false))
            services.nextBinding(FakeFlymeServices.V3_ACTION).connect(services.pool(null))
            val binding = services.nextBinding(FlymeOpenApiBroker.ACTION)
            val before = FakeFlymeMediaCenter(context)
            binding.connect(FakeFlymeBroker(before))
            source.awaitState(v4, connected)
            assertEquals(listOf(19, 7, 8, 6), before.operations.filterNot { it == 10 })

            binding.disconnect()
            source.awaitState(v4, disconnected)
            source.playbackRequested()
            val paused = FakeFlymeMediaCenter(context)
            binding.connect(FakeFlymeBroker(paused))
            source.awaitState(v4, connected)
            assertEquals(listOf(19, 7, 8, 6), paused.operations.filterNot { it == 10 })
            assertEquals(listOf(0 to "Saved song"), paused.publishedMetadata)
            assertEquals(listOf(FakeFlymeServices.V3_ACTION, FlymeOpenApiBroker.ACTION), services.bindActions)
            assertFalse(binding in services.unbound)

            binding.die()
            val replacement = services.nextBinding(FlymeOpenApiBroker.ACTION)
            assertTrue(binding in services.unbound)
            source.awaitState(v4, disconnected)
            source.playbackPaused()
            source.publish(testFlymeSnapshot(isPlaying = true))
            val playing = FakeFlymeMediaCenter(context)
            replacement.connect(FakeFlymeBroker(playing))
            source.awaitState(v4, connected)
            assertEquals(listOf(19, 7, 8, 6, 5), playing.operations.filterNot { it == 10 })
            assertEquals(listOf(1 to "Saved song"), playing.publishedMetadata)

            playing.dieOnNextPublish = true
            source.clear()
            source.awaitState(v4, disconnected)
            assertFalse(replacement in services.unbound)
            val recovered = FakeFlymeMediaCenter(context)
            replacement.connect(FakeFlymeBroker(recovered))
            source.awaitState(v4, connected)
            assertEquals(listOf(19, 7, 8), recovered.operations)
        } finally {
            source.release()
        }
    }

    @Test fun rejectsUnknownProtocolBeforeSendingTransactions() = runBlocking {
        val services = FakeFlymeServices(context)
        val source = services.startSource()
        try {
            services.nextBinding(FakeFlymeServices.V3_ACTION).connect(object : Binder() {
                init { attachInterface(null, "different.interface") }
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    fail("Must not send transactions to an unknown protocol")
                    return false
                }
            })
            source.awaitState(FlymeMediaSource.RegistrationPath.V3, FlymeMediaSource.ConnectionState.Disconnected)
            assertEquals(listOf(FakeFlymeServices.V3_ACTION), services.bindActions)
        } finally {
            source.release()
        }
    }

    @Test fun transportCallbackExecutesExactlyOnceAndStopsAfterRelease() {
        val source = FlymeMediaSource(context, autoBind = false)
        val played = CountDownLatch(1)
        var count = 0
        source.setCallbacks(PlaybackMediaSession.Callbacks(
            play = { count++; played.countDown() }, pause = {}, next = {}, previous = {}, seekTo = {},
        ))
        assertEquals(1, call(source.clientBinder, FlymeMediaSource.CLIENT, 1) { readInt() })
        assertTrue(played.await(3, TimeUnit.SECONDS))
        assertEquals(1, count)
        source.release()
        assertEquals(0, call(source.clientBinder, FlymeMediaSource.CLIENT, 1) { readInt() })
    }

    @Test fun pausedMetadataUsesVendorStateAndOwnPackage() {
        val source = FlymeMediaSource(context, autoBind = false)
        try {
            assertEquals(0, call(source.infoBinder, FlymeMediaSource.INFO, 11) { readInt() })
            assertEquals(6, call(source.infoBinder, FlymeMediaSource.INFO, 9) { readInt() })
            assertEquals(context.packageName, call(source.infoBinder, FlymeMediaSource.INFO, 27) { readString() })
            assertEquals(0, call(source.infoBinder, FlymeMediaSource.INFO, 20) { readInt() })
        } finally { source.release() }
    }

    private fun <T> call(binder: IBinder, descriptor: String, code: Int, read: Parcel.() -> T): T =
        FlymeMediaSource.rpc(binder, descriptor, code, {}, read)
}

internal class FakeFlymeServices(context: Context) : ContextWrapper(context) {
    val bindActions = CopyOnWriteArrayList<String>()
    val unbound = CopyOnWriteArrayList<Binding>()
    private val bindings = LinkedBlockingQueue<Binding>()
    private val allBindings = CopyOnWriteArrayList<Binding>()

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
        assertEquals("com.ecarx.sdk.openapi", service.`package`)
        assertEquals(Context.BIND_AUTO_CREATE, flags)
        val action = requireNotNull(service.action)
        bindActions += action
        val binding = Binding(action, conn)
        allBindings += binding
        bindings.add(binding)
        return true
    }

    override fun unbindService(conn: ServiceConnection) {
        unbound += allBindings.single { it.connection === conn }
    }

    fun nextBinding(action: String): Binding =
        checkNotNull(bindings.poll(5, TimeUnit.SECONDS)) { "Missing bind for $action" }.also {
            assertEquals(action, it.action)
        }

    fun startSource() = FlymeMediaSource(this, mediaCenterVersion = { FlymeMediaSource.MEDIA_CENTER_VERSION_CODE }).apply {
        setCallbacks(PlaybackMediaSession.Callbacks(play = {}, pause = {}, next = {}, previous = {}, seekTo = {}))
    }

    fun pool(media: IBinder?) = object : Binder() {
        init { attachInterface(null, FlymeMediaSource.POOL) }
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            assertEquals(2, code)
            data.enforceInterface(FlymeMediaSource.POOL)
            assertEquals(Process.myPid(), data.readInt())
            assertEquals(Process.myUid(), data.readInt())
            assertEquals(packageName, data.readString())
            assertEquals("mediacenter", data.readString())
            requireNotNull(reply).writeNoException()
            reply.writeStrongBinder(media)
            return true
        }
    }

    class Binding(val action: String, val connection: ServiceConnection) {
        private val component = ComponentName("com.ecarx.sdk.openapi", "FakeService")
        fun connect(binder: IBinder) = connection.onServiceConnected(component, binder)
        fun disconnect() = connection.onServiceDisconnected(component)
        fun die() = connection.onBindingDied(component)
    }

    companion object {
        const val V3_ACTION = "ecarx.intent.action.OpenAPIService"
    }
}

internal class FakeFlymeMediaCenter(private val context: Context) : Binder() {
    val token = Binder()
    val operations = CopyOnWriteArrayList<Int>()
    val publishedMetadata = CopyOnWriteArrayList<Pair<Int, String?>>()
    @Volatile var dieOnNextPublish = false

    init { attachInterface(null, FlymeMediaSource.MEDIA) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        data.enforceInterface(FlymeMediaSource.MEDIA)
        if (code == 6 && dieOnNextPublish) throw DeadObjectException()
        operations += code
        requireNotNull(reply).writeNoException()
        if (code == 19) {
            assertEquals(context.packageName, data.readString())
            val client = requireNotNull(data.readStrongBinder())
            assertEquals(6, FlymeMediaSource.rpc(client, FlymeMediaSource.CLIENT, 12, {}, { readInt() }))
            reply.writeStrongBinder(token)
        } else {
            assertEquals(token, data.readStrongBinder())
            when (code) {
                4, 5 -> reply.writeInt(1)
                6 -> {
                    val info = requireNotNull(data.readStrongBinder())
                    val state = FlymeMediaSource.rpc(info, FlymeMediaSource.INFO, 11, {}, { readInt() })
                    val title = FlymeMediaSource.rpc(info, FlymeMediaSource.INFO, 2, {}, { readString() })
                    publishedMetadata += state to title
                    reply.writeInt(1)
                }
                7 -> assertArrayEquals(intArrayOf(6), data.createIntArray())
                8 -> assertEquals(6, data.readInt())
                10 -> data.readLong()
                else -> fail("Unexpected transaction: $code")
            }
        }
        assertEquals(0, data.dataAvail())
        return true
    }
}

internal class FakeFlymeBroker(private val media: FakeFlymeMediaCenter) : Binder() {
    val methods = CopyOnWriteArrayList<String>()
    val transactions = CopyOnWriteArrayList<Int>()
    val cancellations = LinkedBlockingQueue<Unit>()
    @Volatile var callback: IBinder? = null
        private set

    init { attachInterface(null, FlymeVoiceProtocol.BROKER) }

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        data.enforceInterface(FlymeVoiceProtocol.BROKER)
        transactions += code
        requireNotNull(reply).writeNoException()
        when (code) {
            1 -> assertArrayEquals(arrayOf("mediacenter"), data.createStringArray())
            2, 3 -> {
                assertNotNull(data.readStrongBinder())
                reply.writeInt(1)
            }
            9 -> reply.writeStringList(listOf("mediacenter"))
            4, 6 -> {
                assertEquals(1, data.readInt())
                assertEquals("mediacenter", data.readString())
                assertEquals("MediaCenterAPI", data.readString())
                val method = requireNotNull(data.readString())
                val param = requireNotNull(data.createByteArray()).toString(Charsets.UTF_8)
                assertArrayEquals(byteArrayOf(), data.createByteArray())
                methods += method
                when (method) {
                    "getMainBinder" -> assertEquals("NoParam", param)
                    "registerEx" -> callback = requireNotNull(data.readStrongBinder())
                    "declareVrSemanticsCapability", "cancelVrSemanticsCapability" -> {
                        assertEquals(media.token, data.readStrongBinder())
                        assertTrue(param.contains("vrChannelInfo"))
                    }
                    else -> fail("Unexpected broker method $method")
                }
                reply.writeInt(1)
                reply.writeInt(200)
                reply.writeString("")
                reply.writeByteArray(null)
                reply.writeString("com.ecarx.sdk.openapi.msg.SupportServiceRetMessage")
                reply.writeInt(200)
                reply.writeString("")
                val result = if (method.endsWith("VrSemanticsCapability")) "true" else ""
                reply.writeByteArray(result.toByteArray())
                reply.writeStrongBinder(if (method == "getMainBinder") media else null)
                if (method == "cancelVrSemanticsCapability") cancellations.add(Unit)
            }
            else -> fail("Unexpected broker transaction $code")
        }
        assertEquals(0, data.dataAvail())
        return true
    }

    fun sendPlay() = FlymeMediaSource.rpc(requireNotNull(callback), FlymeVoiceProtocol.EX_CALLBACK, 1, {
        writeInt(2)
        writeString(FlymeVoiceProtocol.SEMANTIC_LISTENER)
        writeString("""{"semantic":33554433}""")
        writeStrongBinder(null)
    }, { assertNull(readString()) })

    fun requestWidgetList() = FlymeMediaSource.rpc(requireNotNull(callback), FlymeVoiceProtocol.EX_CALLBACK, 2, {
        writeInt(1)
        writeString("getMultiMediaListEx")
        writeString(null)
        writeInt(0)
        writeStrongBinder(null)
    }, { readInt() })
}

internal suspend fun FlymeMediaSource.awaitState(path: FlymeMediaSource.RegistrationPath, state: FlymeMediaSource.ConnectionState) =
    withTimeout(5_000) { status.first { it.path == path && it.state == state } }

private fun testFlymeSnapshot(isPlaying: Boolean) = PlaybackSnapshot(
    currentTrack = Track("test", "Saved song", "Artist", "Album", 10_000, emptyList(), emptyList()),
    queue = emptyList(), positionMs = 2_000, durationMs = 10_000,
    isPlaying = isPlaying, isBuffering = false, canSkipNext = false, canSkipPrevious = false,
    repeatMode = PlaybackRepeatMode.Off, shuffleEnabled = false, autoplayEnabled = false,
    canSetRepeat = false, canSetShuffle = false, canSetAutoplay = false,
)
