package dev.amatv.integration

import com.google.protobuf.CodedInputStream
import com.google.protobuf.CodedOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class FlymeAppSourceWhitelistTest {
    private val folio = "dev.amatv.flyme"
    private val icon = "android.resource://$folio/123"

    @Test fun appendsAfterAllExistingEntriesAndPreservesTheirBytes() {
        val qq = source("com.tencent.qqmusic", 2)
        val netease = source("com.netease.cloudmusic", 3)
        val original = qq + netease
        val (count, merged) = FlymeAppSourceConfig.appendIfMissing(original, folio, "AM Folio", icon)

        assertEquals(2, count)
        val updated = checkNotNull(merged)
        assertArrayEquals(original, updated.copyOf(original.size))
        val appended = CodedInputStream.newInstance(updated, original.size, updated.size - original.size)
        assertEquals(162, appended.readTag())
        val entry = CodedInputStream.newInstance(appended.readByteArray())
        assertEquals(10, entry.readTag())
        assertEquals(folio, entry.readString())
        assertEquals(18, entry.readTag())
        assertEquals(icon, entry.readString())
        assertEquals(26, entry.readTag())
        assertEquals("AM Folio", entry.readString())
        assertEquals(32, entry.readTag())
        assertEquals(6, entry.readInt32())
        assertEquals(40, entry.readTag())
        assertEquals(1, entry.readInt32())
        assertEquals(0, entry.readTag())
        assertEquals(0, appended.readTag())
    }

    @Test fun leavesExistingFolioEntryInPlace() {
        val config = source("com.tencent.qqmusic", 2) + source(folio, 3) + source("other", 4)
        assertNull(FlymeAppSourceConfig.appendIfMissing(config, folio, "AM Folio", icon).updated)
    }

    @Test fun doesNotTurnAnEmptyConfigIntoAFolioOnlyList() {
        val (count, merged) = FlymeAppSourceConfig.appendIfMissing(byteArrayOf(), folio, "AM Folio", icon)
        assertEquals(0, count)
        assertNull(merged)
    }

    @Test fun givesEachWriteAttemptAFiveSecondCooldown() {
        val throttle = AppSourceWriteThrottle()
        assertEquals(0L, throttle.remainingMs(0L))
        throttle.recordWrite(0L)
        assertEquals(5_000L, throttle.remainingMs(0L))
        assertEquals(1L, throttle.remainingMs(4_999L))
        assertEquals(0L, throttle.remainingMs(5_000L))
        throttle.recordWrite(5_000L)
        assertEquals(4_999L, throttle.remainingMs(5_001L))
    }

    private fun source(packageName: String, priority: Int) = encode {
        writeByteArray(20, encode {
            writeString(1, packageName)
            writeInt32(5, priority)
            writeByteArray(7, byteArrayOf(1, 2, 3))
            writeString(99, "unrecognized field")
        })
    }

    private fun encode(write: CodedOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream()
        CodedOutputStream.newInstance(bytes).apply { write(); flush() }
        return bytes.toByteArray()
    }
}
