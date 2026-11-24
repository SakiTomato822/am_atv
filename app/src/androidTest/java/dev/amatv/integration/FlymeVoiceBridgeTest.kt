package dev.amatv.integration

import android.content.ContextWrapper
import android.content.Intent
import android.os.Binder
import android.os.Parcel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FlymeVoiceBridgeTest {
    private fun isolatedContext(prefix: String) = object : ContextWrapper(
        InstrumentationRegistry.getInstrumentation().targetContext,
    ) {
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("${prefix}_$name", mode)
    }

    @Test fun frameworkMessageRoundTripsThroughAndroidParcel() {
        val parcel = Parcel.obtain()
        try {
            FlymeVoiceProtocol.writeMessage("getMainBinder", "NoParam", parcel::writeString, parcel::writeByteArray)
            parcel.setDataPosition(0)
            assertEquals("mediacenter", parcel.readString())
            assertEquals("MediaCenterAPI", parcel.readString())
            assertEquals("getMainBinder", parcel.readString())
            assertArrayEquals("NoParam".toByteArray(), parcel.createByteArray())
            assertArrayEquals(byteArrayOf(), parcel.createByteArray())
            assertEquals(0, parcel.dataAvail())
        } finally {
            parcel.recycle()
        }
    }

    @Test fun frameworkReplyReadsNestedParcelableAndBinder() {
        val parcel = Parcel.obtain()
        val token = Binder()
        try {
            parcel.writeInt(1)
            parcel.writeInt(200)
            parcel.writeString("broker ok")
            parcel.writeByteArray(byteArrayOf(7))
            parcel.writeString("com.ecarx.sdk.openapi.msg.SupportServiceRetMessage")
            parcel.writeInt(200)
            parcel.writeString("service ok")
            parcel.writeByteArray("true".toByteArray())
            parcel.writeStrongBinder(token)
            parcel.setDataPosition(0)
            assertEquals(
                FlymeVoiceProtocol.Reply(200, "broker ok", 200, "service ok", "true", token),
                FlymeVoiceProtocol.readReply(parcel),
            )
            assertEquals(0, parcel.dataAvail())
        } finally {
            parcel.recycle()
        }
    }

    @Test fun missingBrokerIsUnavailableAndCanBeDisabled() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent("com.ecarx.easframework.intent.action.EASFRAMEWORK").setPackage("com.ecarx.sdk.openapi")
        assumeTrue(target.packageManager.resolveService(intent, 0) == null)
        val preferences = PlaybackPreferences(isolatedContext("voice_test"))
        preferences.flymeVoiceEnabled = false
        val source = FlymeMediaSource(target)
        val voice = FlymeVoiceBridge(target, preferences, source)
        val scope = CoroutineScope(Dispatchers.Main)
        try {
            assertEquals(FlymeVoiceBridge.Phase.Off, voice.status.value.phase)
            source.setCallbacks(PlaybackMediaSession.Callbacks(play = {}, pause = {}, next = {}, previous = {}, seekTo = {}))
            withContext(Dispatchers.Main) {
                voice.start(scope) { error("A voice command cannot arrive without the broker") }
                voice.setEnabled(true)
            }
            val unavailable = withTimeout(5_000L) {
                voice.status.first { it.phase == FlymeVoiceBridge.Phase.Unavailable }
            }
            assertNull(unavailable.lastSemanticAt)
            withContext(Dispatchers.Main) { voice.setEnabled(false) }
            assertEquals(FlymeVoiceBridge.Phase.Off, voice.status.value.phase)
            assertFalse(preferences.flymeVoiceEnabled)
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            source.release()
        }
    }

    @Test fun voiceUsesResidentRegistrationAndRestoresDeclarationAfterDisconnect() = runBlocking {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val services = FakeFlymeServices(isolatedContext("voice_reconnect_test"))
        val preferences = PlaybackPreferences(services).apply { flymeVoiceEnabled = false }
        val source = services.startSource()
        val voice = FlymeVoiceBridge(services, preferences, source)
        val scope = CoroutineScope(Dispatchers.Main)
        val commands = Channel<FlymeVoiceCommand>(Channel.UNLIMITED)
        val firstMedia = FakeFlymeMediaCenter(target)
        val firstBroker = FakeFlymeBroker(firstMedia)
        try {
            voice.start(scope) { commands.send(it) }
            services.nextBinding(FakeFlymeServices.V3_ACTION).connect(services.pool(null))
            val binding = services.nextBinding(FlymeOpenApiBroker.ACTION)
            binding.connect(firstBroker)
            source.awaitState(FlymeMediaSource.RegistrationPath.V4, FlymeMediaSource.ConnectionState.Connected)
            assertEquals(FlymeVoiceBridge.Phase.Off, voice.status.value.phase)
            assertEquals(listOf(2, 1, 9, 4, 3), firstBroker.transactions)

            withContext(Dispatchers.Main) { voice.setEnabled(true) }
            withTimeout(5_000) { voice.status.first { it.phase == FlymeVoiceBridge.Phase.Declared } }
            val originalCallback = firstBroker.callback
            assertEquals(0, firstBroker.requestWidgetList())
            firstBroker.sendPlay()
            assertEquals(FlymeVoiceCommand.Control.Play, withTimeout(5_000) { commands.receive() })
            val lastSemantic = voice.status.value.lastSemanticAt

            withContext(Dispatchers.Main) { voice.setEnabled(false) }
            assertEquals(Unit, firstBroker.cancellations.poll(5, TimeUnit.SECONDS))
            firstBroker.sendPlay()
            assertEquals(lastSemantic, voice.status.value.lastSemanticAt)
            assertTrue(commands.tryReceive().isFailure)
            assertEquals(listOf(19, 7, 8), firstMedia.operations)
            assertEquals(listOf("getMainBinder", "registerEx", "declareVrSemanticsCapability", "cancelVrSemanticsCapability"),
                firstBroker.methods)
            assertFalse(binding in services.unbound)

            withContext(Dispatchers.Main) { voice.setEnabled(true) }
            withTimeout(5_000) { voice.status.first { it.phase == FlymeVoiceBridge.Phase.Declared } }
            assertSame(originalCallback, firstBroker.callback)
            assertEquals(listOf(19, 7, 8), firstMedia.operations)

            binding.disconnect()
            withTimeout(5_000) { voice.status.first { it.phase == FlymeVoiceBridge.Phase.Unavailable } }
            val secondMedia = FakeFlymeMediaCenter(target)
            val secondBroker = FakeFlymeBroker(secondMedia)
            binding.connect(secondBroker)
            withTimeout(5_000) { voice.status.first { it.phase == FlymeVoiceBridge.Phase.Declared } }
            assertEquals(listOf(19, 7, 8), secondMedia.operations)
            assertEquals(listOf("getMainBinder", "registerEx", "declareVrSemanticsCapability"), secondBroker.methods)
            assertSame(originalCallback, secondBroker.callback)
            secondBroker.sendPlay()
            assertEquals(FlymeVoiceCommand.Control.Play, withTimeout(5_000) { commands.receive() })
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            source.release()
        }
    }

    @Test fun v3VoiceUsesTheExistingTokenWithoutRegisteringV4Media() = runBlocking {
        val services = FakeFlymeServices(isolatedContext("v3_voice_test"))
        val preferences = PlaybackPreferences(services).apply { flymeVoiceEnabled = false }
        val source = services.startSource()
        val voice = FlymeVoiceBridge(services, preferences, source)
        val scope = CoroutineScope(Dispatchers.Main)
        val media = FakeFlymeMediaCenter(services)
        val broker = FakeFlymeBroker(media)
        try {
            voice.start(scope) { error("No voice command sent") }
            services.nextBinding(FakeFlymeServices.V3_ACTION).connect(services.pool(media))
            source.awaitState(FlymeMediaSource.RegistrationPath.V3, FlymeMediaSource.ConnectionState.Connected)
            assertEquals(listOf(FakeFlymeServices.V3_ACTION), services.bindActions)

            withContext(Dispatchers.Main) { voice.setEnabled(true) }
            services.nextBinding(FlymeOpenApiBroker.ACTION).connect(broker)
            withTimeout(5_000) { voice.status.first { it.phase == FlymeVoiceBridge.Phase.Declared } }
            assertEquals(listOf(19, 7, 8), media.operations)
            assertEquals(listOf("getMainBinder", "registerEx", "declareVrSemanticsCapability"), broker.methods)

            withContext(Dispatchers.Main) { voice.setEnabled(false) }
            assertEquals(Unit, broker.cancellations.poll(5, TimeUnit.SECONDS))
            assertEquals(listOf(19, 7, 8), media.operations)
            source.awaitState(FlymeMediaSource.RegistrationPath.V3, FlymeMediaSource.ConnectionState.Connected)
            Unit
        } finally {
            scope.coroutineContext.job.cancelAndJoin()
            source.release()
        }
    }
}
