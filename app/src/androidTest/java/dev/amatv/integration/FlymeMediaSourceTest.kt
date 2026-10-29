package dev.amatv.integration

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Contract tests use real Android Parcels and fake system Binders; no vehicle required. */
@RunWith(AndroidJUnit4::class)
class FlymeMediaSourceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun registersOwnIdentityAndOnlineTypeWithoutRequestingPlayback() {
        val source = FlymeMediaSource(context, autoBind = false)
        val operations = mutableListOf<Int>()
        val token = Binder()
        val media = object : Binder() {
            init { attachInterface(null, FlymeMediaSource.MEDIA) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.enforceInterface(FlymeMediaSource.MEDIA)
                operations += code
                requireNotNull(reply).writeNoException()
                when (code) {
                    19 -> {
                        assertEquals(context.packageName, data.readString())
                        val client = requireNotNull(data.readStrongBinder())
                        assertEquals(6, call(client, FlymeMediaSource.CLIENT, 12) { readInt() })
                        reply.writeStrongBinder(token)
                    }
                    7 -> {
                        assertEquals(token, data.readStrongBinder())
                        assertArrayEquals(intArrayOf(6), data.createIntArray())
                        reply.writeInt(1)
                    }
                    8 -> {
                        assertEquals(token, data.readStrongBinder())
                        assertEquals(6, data.readInt())
                    }
                    4 -> reply.writeInt(1)
                    else -> fail("Unexpected transaction: $code")
                }
                return true
            }
        }
        val pool = object : Binder() {
            init { attachInterface(null, FlymeMediaSource.POOL) }
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                assertEquals(2, code)
                data.enforceInterface(FlymeMediaSource.POOL)
                assertEquals(Process.myPid(), data.readInt())
                assertEquals(Process.myUid(), data.readInt())
                assertEquals(context.packageName, data.readString())
                assertEquals("mediacenter", data.readString())
                requireNotNull(reply).writeNoException()
                reply.writeStrongBinder(media)
                return true
            }
        }
        try {
            source.connectMedia(pool)
            assertTrue(source.status.value.startsWith("Registered"))
            assertEquals(listOf(19, 7, 8), operations)
        } finally { source.release() }
    }

    @Test fun rejectsUnknownProtocolBeforeSendingTransactions() {
        val source = FlymeMediaSource(context, autoBind = false)
        try {
            source.connectMedia(object : Binder() {
                init { attachInterface(null, "different.interface") }
                override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                    fail("Must not send transactions to an unknown protocol")
                    return false
                }
            })
            assertTrue(source.status.value.startsWith("Native media unavailable"))
        } finally { source.release() }
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
