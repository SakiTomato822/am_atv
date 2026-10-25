package dev.amatv.integration

import java.lang.reflect.Method

/**
 * Read-only session access for the hash-verified MusicKit 1.1.1 binary.
 * These are public SDK binary methods, not Android hidden APIs. Registry b()
 * returns only an existing renderer; it never creates a player or renderer.
 */
internal object MusicKitAudioSession {
    private class Accessors(
        val registry: Method,
        val renderer: Method,
        val sessionId: Method,
    )

    private val accessors: Accessors? by lazy {
        runCatching {
            val registryClass = Class.forName("com.apple.android.music.renderer.a.b")
            val rendererClass = Class.forName("com.apple.android.music.renderer.a.a")
            Accessors(
                registryClass.getMethod("a"),
                registryClass.getMethod("b"),
                rendererClass.getMethod("c"),
            )
        }.getOrNull()
    }

    /** Caller must already own an initialized MusicKit controller. Zero means unavailable. */
    fun currentId(): Int {
        val methods = accessors ?: return 0
        return runCatching {
            val registry = methods.registry.invoke(null)
            val renderer = methods.renderer.invoke(registry) ?: return@runCatching 0
            (methods.sessionId.invoke(renderer) as? Int)?.takeIf { it > 0 } ?: 0
        }.getOrDefault(0)
    }
}
