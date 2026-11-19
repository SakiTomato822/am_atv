package dev.amatv.flyme

/** Most recent first, case-insensitive duplicates collapse into the newest spelling. */
internal fun pushSearchHistory(history: List<String>, term: String, max: Int = 12): List<String> {
    val trimmed = term.trim()
    if (trimmed.isEmpty()) return history
    return (listOf(trimmed) + history.filterNot { it.equals(trimmed, ignoreCase = true) }).take(max)
}
