package dev.amatv.integration

import java.net.URLEncoder

private val evccPercentEscape = Regex("%[0-9a-fA-F]{2}")

/** EVCC conditionally URL-decodes strings with percent escapes; preserve literal %xx and +. */
internal fun evccLyricsMetadataValue(document: PlaybackLyricExport?): String {
    val raw = document?.timedLrc ?: document?.plainText.orEmpty()
    val value = if (evccPercentEscape.containsMatchIn(raw)) URLEncoder.encode(raw, "UTF-8") else raw
    // Encoded expansion must also stay bounded before crossing a media-session Binder.
    return value.takeIf {
        it.toByteArray(Charsets.UTF_8).size <= PlaybackLyricExport.DEFAULT_MAX_PAYLOAD_UTF8_BYTES
    }.orEmpty()
}
