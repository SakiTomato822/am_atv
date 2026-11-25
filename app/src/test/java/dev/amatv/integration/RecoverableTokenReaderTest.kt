package dev.amatv.integration

import org.junit.Assert.*
import org.junit.Test

class RecoverableTokenReaderTest {
    // Synthetic values only; the helper has no Android/Keystore dependency.
    private val valid = "synthetic-valid-session-value"
    private val usable: (String) -> Boolean = { it.length > 20 && it.none(Char::isWhitespace) }

    @Test fun repeatedReadsDecryptOnceAndWritesAndClearsReplaceTheCachedValue() {
        val cache = TokenMemoryCache(usable)
        var stored: String? = valid
        var decryptions = 0
        fun read() = cache.read { ++decryptions; stored }

        repeat(100) { assertEquals(valid, read()) }
        assertEquals(1, decryptions)

        stored = "updated-synthetic-session-value"
        cache.update(stored)
        assertEquals(stored, read())
        assertEquals(1, decryptions)

        stored = null
        cache.update(null)
        assertNull(read())
        assertEquals(2, decryptions)
    }

    @Test fun expiredAndInvalidEntriesAreRecheckedWithoutCaching() {
        var now = 0
        val cache = TokenMemoryCache<Int> { it > now }
        var reads = 0
        assertEquals(10, cache.read { ++reads; 10 })
        now = 10
        assertNull(cache.read { ++reads; 7 })
        assertEquals(20, cache.read { ++reads; 20 })
        assertEquals(3, reads)
    }

    @Test fun corruptEncryptedCopyRecoversSdkSessionAndRepairsEncryptedStorage() {
        var repaired: String? = null
        val result = readRecoverableToken(
            readEncrypted = { throw javax.crypto.AEADBadTagException() },
            readSdkFallback = { valid },
            isValid = usable,
            repairEncrypted = { repaired = it },
        )
        assertEquals(valid, result)
        assertEquals(valid, repaired)
    }

    @Test fun missingEncryptedCopyRecoversAndNormalizesSdkSession() {
        var repaired: String? = null
        assertEquals(valid, readRecoverableToken(
            readEncrypted = { null },
            readSdkFallback = { " $valid " },
            normalize = String::trim,
            isValid = usable,
            repairEncrypted = { repaired = it },
        ))
        assertEquals(valid, repaired)
    }

    @Test fun healthyEncryptedCopyIsPreferredWithoutReadingOrOverwritingSdkSession() {
        var fallbackRead = false
        var repairAttempted = false
        assertEquals(valid, readRecoverableToken(
            readEncrypted = { valid },
            readSdkFallback = { fallbackRead = true; "another-synthetic-session-value" },
            isValid = usable,
            repairEncrypted = { repairAttempted = true },
        ))
        assertFalse(fallbackRead)
        assertFalse(repairAttempted)
    }

    @Test fun invalidSdkFallbackIsNeverReturnedOrWritten() {
        for (fallback in listOf(null, "", "short", "invalid synthetic session with spaces")) {
            var repairAttempted = false
            assertNull(readRecoverableToken(
                readEncrypted = { throw javax.crypto.AEADBadTagException() },
                readSdkFallback = { fallback },
                isValid = usable,
                repairEncrypted = { repairAttempted = true },
            ))
            assertFalse(repairAttempted)
        }
    }

    @Test fun temporaryEncryptionWriteFailureDoesNotDiscardRecoveredSdkSession() {
        assertEquals(valid, readRecoverableToken(
            readEncrypted = { null },
            readSdkFallback = { valid },
            isValid = usable,
            repairEncrypted = { error("Keystore temporarily unavailable") },
        ))
    }

    @Test fun invalidEncryptedTokenFallsThroughToIndependentlyValidatedSdkCopy() {
        assertEquals(valid, readRecoverableToken(
            readEncrypted = { "expired" },
            readSdkFallback = { valid },
            isValid = { it == valid },
            repairEncrypted = {},
        ))
    }
}
