package dev.amatv.integration

import java.util.UUID

/** Hold from the first sign-in click through token preparation and ActivityResult. */
class AuthLaunchGate {
    private var active: String? = null

    @Synchronized
    fun tryBegin(): String? {
        if (active != null) return null
        return UUID.randomUUID().toString().also { active = it }
    }

    @Synchronized
    fun isCurrent(attempt: String): Boolean = active == attempt

    @Synchronized
    fun <T> finish(attempt: String, complete: () -> T): T? {
        if (active != attempt) return null
        active = null
        return complete()
    }

    @Synchronized
    fun invalidate(changeAccount: () -> Unit) {
        active = null
        changeAccount()
    }
}
