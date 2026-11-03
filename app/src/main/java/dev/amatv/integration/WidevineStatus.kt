package dev.amatv.integration

import android.media.MediaDrm
import android.media.NotProvisionedException
import android.util.Log
import dev.amatv.R
import dev.amatv.i18n.AppText
import java.util.UUID

/**
 * What the system's Widevine looks like. GeckoView plays stations through Android's MediaDrm;
 * it never downloads a CDM of its own, so a missing or unprovisioned system Widevine leaves
 * MusicKit JS on 30-second previews whatever the network.
 */
internal fun widevineStatus(): String {
    val uuid = UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L)
    val status = runCatching {
        if (!MediaDrm.isCryptoSchemeSupported(uuid)) return@runCatching AppText.text(R.string.widevine_unsupported)
        val drm = MediaDrm(uuid)
        try {
            val level = runCatching { drm.getPropertyString("securityLevel") }.getOrDefault("?")
            val vendor = runCatching { drm.getPropertyString(MediaDrm.PROPERTY_VENDOR) }.getOrDefault("?")
            val version = runCatching { drm.getPropertyString(MediaDrm.PROPERTY_VERSION) }.getOrDefault("?")
            try {
                drm.closeSession(drm.openSession())
                AppText.text(R.string.widevine_ready, level, vendor, version)
            } catch (notProvisioned: NotProvisionedException) {
                AppText.text(R.string.widevine_not_provisioned, level)
            }
        } finally {
            drm.close()
        }
    }.getOrElse { AppText.text(R.string.widevine_error, it.javaClass.simpleName + (it.message?.let { m -> ": $m" } ?: "")) }
    Log.i("AMATV-Widevine", status)
    return status
}
