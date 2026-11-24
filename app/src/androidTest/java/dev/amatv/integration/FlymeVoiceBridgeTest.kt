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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlymeVoiceBridgeTest {
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
        val isolatedContext = object : ContextWrapper(target) {
            override fun getSharedPreferences(name: String, mode: Int) =
                super.getSharedPreferences("voice_test_$name", mode)
        }
        val preferences = PlaybackPreferences(isolatedContext)
        preferences.flymeVoiceEnabled = false
        val source = FlymeMediaSource(target, autoBind = false)
        val voice = FlymeVoiceBridge(target, preferences, source)
        val scope = CoroutineScope(Dispatchers.Main)
        try {
            assertEquals(FlymeVoiceBridge.Phase.Off, voice.status.value.phase)
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
            source.release()
            scope.coroutineContext.job.cancelAndJoin()
        }
    }
}
