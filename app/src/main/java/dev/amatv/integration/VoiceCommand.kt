package dev.amatv.integration

/** What a spoken sentence asks the player to do. */
sealed interface VoiceCommand {
    data object Pause : VoiceCommand
    data object Resume : VoiceCommand
    data object Next : VoiceCommand
    data object Previous : VoiceCommand
    /** The listener's personal station. */
    data object PersonalStation : VoiceCommand
    /** The [number]th item (from 1) of the list on screen. */
    data class Pick(val number: Int) : VoiceCommand
    /** Search the catalog for [query] and play the best song. */
    data class Play(val query: String) : VoiceCommand
    data object Nothing : VoiceCommand
}

/**
 * Turns recognized speech into a [VoiceCommand] with a few fixed Chinese phrasings, no language
 * model: "暂停", "继续", "下一首", "上一首", and "播放 / 我想听 / 来一首 …" with an artist and/or
 * song ("周杰伦的晴天" searches "周杰伦 晴天"). Anything else is taken as a song or artist name.
 */
fun parseVoiceCommand(spoken: String): VoiceCommand {
    val text = spoken.replace(PUNCTUATION, " ").replace(SPACES, " ").trim().lowercase()
    if (text.isEmpty()) return VoiceCommand.Nothing
    val compact = text.replace(" ", "").removeSuffix("吧").removeSuffix("呗")
    PICK.find(compact)?.let { match ->
        val number = match.groupValues[1].let { it.toIntOrNull() ?: chineseNumber(it) }
        if (number != null && number > 0) return VoiceCommand.Pick(number)
    }
    when {
        PERSONAL_STATION.containsMatchIn(compact) -> return VoiceCommand.PersonalStation
        PREVIOUS.containsMatchIn(compact) -> return VoiceCommand.Previous
        NEXT.containsMatchIn(compact) -> return VoiceCommand.Next
        PAUSE.matches(compact) -> return VoiceCommand.Pause
        RESUME.matches(compact) -> return VoiceCommand.Resume
    }
    var query = text
    PLAY_PREFIX.find(query)?.let { query = query.substring(it.range.last + 1) }
    query = query.trim()
    while (true) {
        val trimmed = query.replace(QUERY_SUFFIX, "").replace(QUERY_LEAD, "").trim()
        if (trimmed == query) break
        query = trimmed
    }
    // "周杰伦的晴天" -> "周杰伦 晴天"; a 的 at either end was already dropped.
    query = query.replaceFirst(Regex("(?<=\\S)的(?=\\S)"), " ").trim()
    return if (query.length >= 2 || query.any { it.code > 0x2E80 }) VoiceCommand.Play(query) else VoiceCommand.Nothing
}

/** 一 … 九十九; null for anything else. */
internal fun chineseNumber(text: String): Int? {
    val digits = mapOf('一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
    if (text.isEmpty() || text.any { it != '十' && it !in digits }) return null
    val ten = text.indexOf('十')
    if (ten < 0) return if (text.length == 1) digits[text[0]] else null
    if (text.count { it == '十' } > 1 || ten > 1 || text.length - ten > 2) return null
    val tens = if (ten == 0) 1 else digits.getValue(text[0])
    val ones = if (ten == text.lastIndex) 0 else digits.getValue(text[ten + 1])
    return tens * 10 + ones
}

// "第三个" / "播放第2首" / "选第十二项"; the whole sentence must be about the position.
private val PICK = Regex("^(?:请|帮我)?(?:播放|放|选|选择|点|点击|打开|听)?第([0-9]{1,2}|[一二两三四五六七八九十]{1,3})(?:个|首|项|条|张|位)?(?:歌|歌曲)?$")

private val PUNCTUATION = Regex("[，。！？、,.!?;；:：\"“”'‘’()（）]")
private val SPACES = Regex("\\s+")
private val PERSONAL_STATION = Regex("我的电台|个人电台|私人电台|专属电台|我的广播|为我推荐的电台")
private val PREVIOUS = Regex("上一首|上一曲|上首|前一首|上一个")
private val NEXT = Regex("下一首|下一曲|下首|下一个|切歌|换一首|换首|换歌|跳过")
private val PAUSE = Regex("(请|帮我)?(暂停|停止|停一下|停下|别放了|不要放了|不听了|关掉音乐|停)(播放|音乐|一下)?")
private val RESUME = Regex("(请|帮我)?(继续播放|继续|接着放|接着播|接着听|恢复播放|开始播放|播放音乐|播放|放歌)")
private val PLAY_PREFIX = Regex(
    "^(请|麻烦)?(帮我|给我|替我)?(播放|放一首|放首|放一下|放点|放|来一首|来首|来点|来个|我想听|我要听|想听|听一下|听听|听|搜索|搜一下|搜|找一下|找)",
)
private val QUERY_LEAD = Regex("^(一首|一下|首|点|一些|些|个)")
private val QUERY_SUFFIX = Regex("(的歌曲|的歌|的音乐|的专辑|这首歌|这首|的|吧|呗|一下|好吗|可以吗)$")
