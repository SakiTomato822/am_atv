package dev.amatv.integration

import com.apple.android.sdk.authentication.TokenProvider

/**
 * MusicKit calls this provider from its playback thread while resolving the
 * catalog queue and FairPlay stream. Keep it as a concrete SDK implementation
 * (matching Apple's sample) rather than a java.lang.reflect.Proxy.
 */
internal class MusicKitTokenProvider(
    private val developerToken: String,
    private val userToken: String,
) : TokenProvider {
    override fun getDeveloperToken(): String = developerToken

    override fun getUserToken(): String = userToken
}
