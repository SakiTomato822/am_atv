package dev.amatv.core

import android.content.Intent
import dev.amatv.integration.MusicConnectionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AccountState(
    val connectionState: MusicConnectionState = MusicConnectionState.ReadyToSignIn,
) {
    val isSignedIn: Boolean get() = connectionState == MusicConnectionState.SignedIn
}

interface AccountOperations {
    val connectionState: MusicConnectionState
    fun completeWebAuthentication(data: Intent): String
    fun signOut()
}

class AccountStore(
    private val operations: AccountOperations,
) {
    private val _state = MutableStateFlow(AccountState(connectionState = operations.connectionState))
    val state: StateFlow<AccountState> = _state.asStateFlow()

    fun completeSignIn(data: Intent): Result<Unit> {
        return runCatching {
            operations.completeWebAuthentication(data)
            _state.value = _state.value.copy(connectionState = MusicConnectionState.SignedIn)
        }
    }

    fun signOut() {
        operations.signOut()
        _state.value = _state.value.copy(connectionState = operations.connectionState)
    }

    fun syncConnectionState() {
        _state.value = _state.value.copy(connectionState = operations.connectionState)
    }
}
