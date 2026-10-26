package dev.amatv.integration

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WebAuthenticationSessionTest {
    private class Marker(override var revision: Long = 0L) : WebSessionResetMarker

    @Test fun normalSignInDoesNotInitializeOrClearTheBrowser() = runBlocking {
        val session = WebAuthenticationSession(Marker()) {
            fail("A user who has not signed out must keep the existing browser session")
        }
        session.prepareForSignIn()
    }

    @Test fun signOutSurvivesServiceRecreationAndSignInWaitsForCookieRemoval() = runBlocking {
        val marker = Marker()
        WebAuthenticationSession(marker) { fail("Sign-out must not initialize WebView") }
            .markLocalWebSessionForReset()

        val cookiesRemoved = CompletableDeferred<Unit>()
        var removals = 0
        val recreated = WebAuthenticationSession(marker) {
            removals++
            cookiesRemoved.await()
        }
        val preparation = async(start = CoroutineStart.UNDISPATCHED) { recreated.prepareForSignIn() }
        assertFalse(preparation.isCompleted)
        assertTrue(marker.revision != 0L)
        cookiesRemoved.complete(Unit)
        preparation.await()

        assertEquals(0L, marker.revision)
        recreated.prepareForSignIn()
        assertEquals(1, removals)
    }

    @Test fun missingProviderLeavesCleanupPendingInsteadOfReusingTheOldAccount() = runBlocking {
        val marker = Marker()
        val failure = IllegalStateException("WebView provider unavailable")
        val unavailable = WebAuthenticationSession(marker) { throw failure }
        unavailable.markLocalWebSessionForReset()
        try {
            unavailable.prepareForSignIn()
            fail("A failed reset must prevent the next authentication launch")
        } catch (error: IllegalStateException) {
            assertTrue(error === failure)
        }
        assertTrue(marker.revision != 0L)

        var retries = 0
        WebAuthenticationSession(marker) { retries++ }.prepareForSignIn()
        assertEquals(1, retries)
        assertEquals(0L, marker.revision)
    }

    @Test fun cancellationDoesNotAcknowledgeUnfinishedCleanup() = runBlocking {
        val marker = Marker()
        val entered = CompletableDeferred<Unit>()
        val neverCompleted = CompletableDeferred<Unit>()
        val session = WebAuthenticationSession(marker) {
            entered.complete(Unit)
            neverCompleted.await()
        }
        session.markLocalWebSessionForReset()
        val preparation = async { session.prepareForSignIn() }
        entered.await()
        preparation.cancel()
        try {
            preparation.await()
            fail("Preparation must remain cancellable")
        } catch (_: CancellationException) {
            assertTrue(marker.revision != 0L)
        }
    }

    @Test fun aNewSignOutDuringCleanupCannotBeAcknowledgedByTheOlderReset() = runBlocking {
        val marker = Marker()
        lateinit var session: WebAuthenticationSession
        var removals = 0
        session = WebAuthenticationSession(marker) {
            removals++
            if (removals == 1) session.markLocalWebSessionForReset()
        }
        session.markLocalWebSessionForReset()
        session.prepareForSignIn()
        assertEquals(2, removals)
        assertEquals(0L, marker.revision)
    }

    @Test fun concurrentPreparationsShareOneCompletedReset() = runBlocking {
        val marker = Marker()
        val release = CompletableDeferred<Unit>()
        var removals = 0
        val firstSession = WebAuthenticationSession(marker) { removals++; release.await() }
        val secondSession = WebAuthenticationSession(marker) { removals++; release.await() }
        firstSession.markLocalWebSessionForReset()
        val first = async(start = CoroutineStart.UNDISPATCHED) { firstSession.prepareForSignIn() }
        val second = async(start = CoroutineStart.UNDISPATCHED) { secondSession.prepareForSignIn() }
        assertEquals(1, removals)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        release.complete(Unit)
        first.await()
        second.await()
        assertEquals(1, removals)
    }
}
