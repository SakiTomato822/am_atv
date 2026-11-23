package dev.amatv.core

import android.content.Intent
import dev.amatv.integration.MusicConnectionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountStoreTest {

    private class FakeAccountOperations(
        override var connectionState: MusicConnectionState = MusicConnectionState.ReadyToSignIn,
    ) : AccountOperations {
        var signOutCalled = false
        var shouldFail = false
        var lastData: Intent? = null

        override fun completeWebAuthentication(data: Intent): String {
            lastData = data
            if (shouldFail) {
                error("Auth failed")
            }
            connectionState = MusicConnectionState.SignedIn
            return "fake-user-token"
        }

        override fun signOut() {
            signOutCalled = true
            connectionState = MusicConnectionState.ReadyToSignIn
        }
    }

    @Test
    fun initialStateReflectsOperationsConnectionState() {
        val ops = FakeAccountOperations(connectionState = MusicConnectionState.Demo)
        val store = AccountStore(ops)

        assertEquals(MusicConnectionState.Demo, store.state.value.connectionState)
        assertFalse(store.state.value.isSignedIn)

        val signedInOps = FakeAccountOperations(connectionState = MusicConnectionState.SignedIn)
        val signedInStore = AccountStore(signedInOps)

        assertEquals(MusicConnectionState.SignedIn, signedInStore.state.value.connectionState)
        assertTrue(signedInStore.state.value.isSignedIn)
    }

    @Test
    fun completeSignInSuccessUpdatesConnectionStateToSignedIn() {
        val ops = FakeAccountOperations(connectionState = MusicConnectionState.ReadyToSignIn)
        val store = AccountStore(ops)
        val fakeIntent = Intent()

        val result = checkNotNull(store.completeSignIn(checkNotNull(store.beginSignIn()), fakeIntent))

        assertTrue(result.isSuccess)
        assertEquals(MusicConnectionState.SignedIn, store.state.value.connectionState)
        assertTrue(store.state.value.isSignedIn)
        assertEquals(fakeIntent, ops.lastData)
    }

    @Test
    fun completeSignInFailurePreservesStateAndReturnsFailure() {
        val ops = FakeAccountOperations(connectionState = MusicConnectionState.ReadyToSignIn).apply {
            shouldFail = true
        }
        val store = AccountStore(ops)
        val fakeIntent = Intent()

        val result = checkNotNull(store.completeSignIn(checkNotNull(store.beginSignIn()), fakeIntent))

        assertTrue(result.isFailure)
        assertEquals("Auth failed", result.exceptionOrNull()?.message)
        assertEquals(MusicConnectionState.ReadyToSignIn, store.state.value.connectionState)
        assertFalse(store.state.value.isSignedIn)
    }

    @Test
    fun signOutCallsOperationsAndUpdatesState() {
        val ops = FakeAccountOperations(connectionState = MusicConnectionState.SignedIn)
        val store = AccountStore(ops)

        store.signOut()

        assertTrue(ops.signOutCalled)
        assertEquals(MusicConnectionState.ReadyToSignIn, store.state.value.connectionState)
        assertFalse(store.state.value.isSignedIn)
    }

    @Test
    fun syncConnectionStateReflectsExternalStateChange() {
        val ops = FakeAccountOperations(connectionState = MusicConnectionState.ReadyToSignIn)
        val store = AccountStore(ops)

        assertEquals(MusicConnectionState.ReadyToSignIn, store.state.value.connectionState)

        ops.connectionState = MusicConnectionState.SignedIn
        store.syncConnectionState()

        assertEquals(MusicConnectionState.SignedIn, store.state.value.connectionState)
    }
    @Test
    fun resultFromBeforeSignOutCannotRestoreSessionOrFinishTheNewAttempt() {
        val ops = FakeAccountOperations()
        val store = AccountStore(ops)
        val old = checkNotNull(store.beginSignIn())
        store.signOut()
        val current = checkNotNull(store.beginSignIn())
        val data = Intent()

        assertEquals(null, store.completeSignIn(old, data))
        assertEquals(null, ops.lastData)
        assertFalse(store.state.value.isSignedIn)
        assertTrue(checkNotNull(store.completeSignIn(current, data)).isSuccess)
        assertTrue(store.state.value.isSignedIn)
    }

}
