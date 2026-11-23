package dev.amatv.core

import android.content.Intent
import dev.amatv.integration.AuthLaunchGate
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
    private val authLaunchGate = AuthLaunchGate()

    fun beginSignIn(): String? = authLaunchGate.tryBegin()

    fun isSignInCurrent(attempt: String): Boolean = authLaunchGate.isCurrent(attempt)

    fun cancelSignIn(attempt: String): Boolean = authLaunchGate.finish(attempt) { true } == true

    fun completeSignIn(attempt: String, data: Intent): Result<Unit>? = authLaunchGate.finish(attempt) {
        runCatching {
            operations.completeWebAuthentication(data)
            _state.value = _state.value.copy(connectionState = MusicConnectionState.SignedIn)
        }
    }

    fun signOut() = authLaunchGate.invalidate {
        operations.signOut()
        _state.value = _state.value.copy(connectionState = operations.connectionState)
    }

    fun syncConnectionState() {
        _state.value = _state.value.copy(connectionState = operations.connectionState)
    }
}
