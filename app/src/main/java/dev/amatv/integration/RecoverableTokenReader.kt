package dev.amatv.integration

/** A damaged encrypted copy must not hide an independently valid SDK session. */
internal fun readRecoverableToken(
    readEncrypted: () -> String?,
    readSdkFallback: () -> String?,
    normalize: (String) -> String = { it },
    isValid: (String) -> Boolean,
    repairEncrypted: (String) -> Unit,
): String? {
    fun candidate(read: () -> String?): String? = runCatching {
        read()?.let(normalize)?.takeIf(isValid)
    }.getOrNull()

    candidate(readEncrypted)?.let { return it }
    val recovered = candidate(readSdkFallback) ?: return null
    // Storage repair may be temporarily unavailable; the valid SDK session
    // remains usable and the next read can retry the encrypted write.
    runCatching { repairEncrypted(recovered) }
    return recovered
}
