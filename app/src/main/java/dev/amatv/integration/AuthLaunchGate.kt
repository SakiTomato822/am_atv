package dev.amatv.integration

/** Hold from the first sign-in click through token preparation and ActivityResult. */
class AuthLaunchGate {
    private var active = false

    @Synchronized
    fun tryBegin(): Boolean {
        if (active) return false
        active = true
        return true
    }

    /** Release on preparation/launch failure, cancellation, or the final activity result. */
    @Synchronized
    fun finish() {
        active = false
    }
}
