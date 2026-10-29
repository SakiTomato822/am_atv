package dev.amatv.integration

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackErrorRoutingTest {
    @Test fun acceptsQueueFailureWhileOldSongRemainsCurrentDuringPrepare() {
        assertTrue(isPlaybackErrorForActiveRequest(
            callbackGeneration = 8L,
            requestGeneration = 8L,
            activeGeneration = 8L,
            issuedPrepareGeneration = 8L,
            isCurrentController = true,
            requestPreparing = true,
            nativeCatalogId = "old-song",
            requestedCatalogId = "new-station",
            stationContainerMatches = false,
        ))
    }

    @Test fun rejectsStaleGenerationEvenWhileAQueueIsPreparing() {
        assertFalse(isPlaybackErrorForActiveRequest(
            callbackGeneration = 7L,
            requestGeneration = 8L,
            activeGeneration = 8L,
            issuedPrepareGeneration = 8L,
            isCurrentController = true,
            requestPreparing = true,
            nativeCatalogId = "old-song",
            requestedCatalogId = "new-station",
            stationContainerMatches = false,
        ))
    }

    @Test fun rejectsUnrelatedItemErrorAfterNewQueueIsPrepared() {
        assertFalse(isPlaybackErrorForActiveRequest(
            callbackGeneration = 8L,
            requestGeneration = 8L,
            activeGeneration = 8L,
            issuedPrepareGeneration = 8L,
            isCurrentController = true,
            requestPreparing = false,
            nativeCatalogId = "old-song",
            requestedCatalogId = "new-station",
            stationContainerMatches = false,
        ))
    }
}
