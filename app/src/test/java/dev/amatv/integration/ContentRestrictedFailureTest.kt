package dev.amatv.integration

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentRestrictedFailureTest {
    @Test fun onlyWebPlaybackCode3082MeansRestrictedContent() {
        assertTrue(isContentRestrictedFailure(3082))
        assertFalse(isContentRestrictedFailure(null))
        assertFalse(isContentRestrictedFailure(Int.MIN_VALUE))
    }

    @Test fun restrictedContentStaysAnUnexpectedFailureForRetryRouting() {
        assertEquals(PlaybackFailureKind.Unexpected, classifyPlaybackFailure(0, 3082, Int.MIN_VALUE))
    }
}
