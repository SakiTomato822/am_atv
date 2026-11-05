package dev.amatv.integration.voice

/** One control on screen, as voice sees it. */
data class ScreenTarget(
    val label: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val clickable: Boolean,
    /** Scrolls sideways / up and down (it has a scroll range on that axis). */
    val scrollsHorizontally: Boolean = false,
    val scrollsVertically: Boolean = false,
    val id: Int = 0,
    val primary: String = label,
) {
    val area: Float get() = (right - left).coerceAtLeast(0f) * (bottom - top).coerceAtLeast(0f)
}

/**
 * Scores how well [spoken] names a control labelled [label]: exact, contained either way, or the
 * same sound (pinyin) — the recognizer often writes a name with sound-alike characters.
 */
fun matchScore(spoken: String, label: String, normalize: (String) -> String, pinyin: (String) -> String?): Int {
    val a = normalize(spoken)
    val b = normalize(label)
    if (a.isEmpty() || b.isEmpty()) return 0
    if (a == b) return 100
    if (a.length >= 2 && b.contains(a)) return 85 - (b.length - a.length).coerceAtMost(20)
    val pa = pinyin(a)?.replace(" ", "")
    val pb = pinyin(b)?.replace(" ", "")
    if (pa != null && pb != null && pa.isNotEmpty()) {
        if (pa == pb) return 75
        if (pa.length >= 4 && pb.contains(pa)) return 60 - ((pb.length - pa.length) / 3).coerceAtMost(15)
    }
    // The sentence holds the label: only if the label is most of it. "我喜好歌曲" must not press
    // the "歌曲" tab just because both end in 歌曲.
    if (b.length >= 2 && a.contains(b) && b.length * 10 >= a.length * 6) return 50
    return 0
}

/** The best clickable control for [spoken], or null if none is a reasonable match. */
fun bestTarget(
    spoken: String,
    targets: List<ScreenTarget>,
    normalize: (String) -> String,
    pinyin: (String) -> String?,
    exactPrimary: Boolean = false,
): ScreenTarget? = targets.asSequence()
    .filter { it.clickable && it.label.isNotBlank() }
    .map {
        val score = if (!exactPrimary) matchScore(spoken, it.label, normalize, pinyin)
        else {
            val left = normalize(spoken)
            val right = normalize(it.primary)
            val sound = pinyin(left)?.replace(" ", "")?.takeIf { value -> value.isNotBlank() }
            when {
                left.isEmpty() || right.isEmpty() -> 0
                left == right -> 100
                sound != null && sound == pinyin(right)?.replace(" ", "") -> 75
                else -> 0
            }
        }
        it to score
    }
    .filter { it.second >= 50 }
    .maxWithOrNull(compareBy<Pair<ScreenTarget, Int>> { it.second }.thenByDescending { it.first.label.length })
    ?.first

/**
 * The list to scroll: for "热门歌曲向右滑" the horizontal list just below the heading that
 * matches [section]; without a section, the largest list scrolling on that axis.
 */
fun scrollTarget(
    section: String?,
    horizontal: Boolean,
    targets: List<ScreenTarget>,
    normalize: (String) -> String,
    pinyin: (String) -> String?,
): ScreenTarget? {
    val lists = targets.filter { if (horizontal) it.scrollsHorizontally else it.scrollsVertically }
    if (section.isNullOrBlank()) return lists.maxByOrNull { it.area }
    val heading = targets.filter { !it.clickable || it.label.length <= 12 }
        .map { it to matchScore(section, it.label, normalize, pinyin) }
        .filter { it.second >= 50 }
        .maxByOrNull { it.second }?.first ?: return lists.maxByOrNull { it.area }
    return lists.filter { it.top >= heading.top - 4f }
        .minByOrNull { (it.top - heading.bottom).let { gap -> if (gap < 0) -gap * 4 else gap } }
}
