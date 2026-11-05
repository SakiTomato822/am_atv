package dev.amatv.integration

/** One thing a spoken sentence asks for; a sentence can hold several (see [parseVoiceSteps]). */
sealed interface VoiceCommand {
    data object Pause : VoiceCommand
    data object Resume : VoiceCommand
    data object Next : VoiceCommand
    data object Previous : VoiceCommand
    /** Move within the song by [seconds] (negative: back). */
    data class Seek(val seconds: Int) : VoiceCommand
    /** Now Playing with its lyrics. */
    data object NowPlaying : VoiceCommand
    data class Go(val page: Page) : VoiceCommand
    /** The playing song's artist page / complete album. */
    data object CurrentArtist : VoiceCommand
    data object CurrentAlbum : VoiceCommand
    /** Add the playing song to Favorite Songs. */
    data object Favorite : VoiceCommand
    /** Press a visible shuffle button, or shuffle the queue. */
    data object Shuffle : VoiceCommand
    /** Scroll the list under [section] (or the page) toward [direction]. */
    data class Scroll(val direction: Direction, val section: String?) : VoiceCommand
    /** The listener's personal station. */
    data object PersonalStation : VoiceCommand
    /** The [number]th item (from 1) of the list on screen. */
    data class Pick(val number: Int) : VoiceCommand
    /** Press the control on screen named [label]; failing that, search and play it. */
    data class Open(val label: String) : VoiceCommand
    /** Play [query]: a visible item with that name, else a catalog search. */
    data class Play(val query: String) : VoiceCommand
    data object Nothing : VoiceCommand

    enum class Page { Home, Browse, Library, Search }
    enum class Direction { Up, Down, Left, Right }
}

/**
 * Splits a sentence into steps: "进资料库看专辑" -> [Go(Library), Open(专辑)], "播放喜好歌曲里的第三首"
 * -> [Open(喜好歌曲), Pick(3)]. Each step is parsed by [parseVoiceCommand].
 */
fun parseVoiceSteps(spoken: String): List<VoiceCommand> {
    val text = spoken.replace(PUNCTUATION, " ").replace(SPACES, " ").trim()
    if (text.isEmpty()) return listOf(VoiceCommand.Nothing)
    val parts = mutableListOf<String>()
    for (piece in text.split(THEN)) {
        val inside = INSIDE.find(piece)
        if (inside != null && inside.range.first > 0 && inside.range.last < piece.lastIndex) {
            parts += piece.substring(0, inside.range.first)
            parts += piece.substring(inside.range.last + 1)
            continue
        }
        // "进资料库看专辑": go somewhere, then look at something there.
        val go = GO_THEN_LOOK.matchEntire(piece.replace(" ", ""))
        if (go != null) {
            parts += go.groupValues[1]
            parts += go.groupValues[2]
            continue
        }
        parts += piece
    }
    val cleaned = parts.map { it.trim() }.filter { it.isNotEmpty() }
    val steps = cleaned.map { step ->
        when (val command = parseVoiceCommand(step)) {
            // The first half of "X里的第三首" opens X rather than playing a search for it.
            is VoiceCommand.Play -> if (cleaned.size > 1) VoiceCommand.Open(command.query) else command
            else -> command
        }
    }.filter { it != VoiceCommand.Nothing }
    return steps.ifEmpty { listOf(VoiceCommand.Nothing) }
}

/** One step: fixed Chinese phrasings, no language model. Anything else is a name to open or play. */
fun parseVoiceCommand(spoken: String): VoiceCommand {
    val text = spoken.replace(PUNCTUATION, " ").replace(SPACES, " ").trim().lowercase()
    if (text.isEmpty()) return VoiceCommand.Nothing
    val compact = text.replace(" ", "").removeSuffix("吧").removeSuffix("呗")
    PICK.find(compact)?.let { match ->
        val number = match.groupValues[1].let { it.toIntOrNull() ?: chineseNumber(it) }
        if (number != null && number > 0) return VoiceCommand.Pick(number)
    }
    SEEK.matchEntire(compact)?.let { match ->
        val back = match.groupValues[1] in BACKWARD
        val amount = match.groupValues[2]
        val seconds = if (amount.isEmpty() || amount == "一点" || amount == "一下") 10 else {
            val minutes = amount.endsWith("分钟")
            val number = amount.removeSuffix("分钟").removeSuffix("秒")
            (number.toIntOrNull() ?: chineseNumber(number) ?: 10) * (if (minutes) 60 else 1)
        }
        return VoiceCommand.Seek(if (back) -seconds else seconds)
    }
    SCROLL.matchEntire(compact)?.let { match ->
        val direction = when (match.groupValues[2]) {
            "上" -> VoiceCommand.Direction.Up
            "左" -> VoiceCommand.Direction.Left
            "右" -> VoiceCommand.Direction.Right
            else -> VoiceCommand.Direction.Down
        }
        val section = match.groupValues[1].removeSuffix("的").removeSuffix("那一排").removeSuffix("这一排")
            .removeSuffix("那排").removeSuffix("这排").takeIf { it.isNotEmpty() }
        return VoiceCommand.Scroll(direction, section)
    }
    when {
        PERSONAL_STATION.containsMatchIn(compact) -> return VoiceCommand.PersonalStation
        PREVIOUS.containsMatchIn(compact) -> return VoiceCommand.Previous
        NEXT_PAGE.matches(compact) -> return VoiceCommand.Scroll(VoiceCommand.Direction.Down, null)
        NEXT.containsMatchIn(compact) -> return VoiceCommand.Next
        PAUSE.matches(compact) -> return VoiceCommand.Pause
        RESUME.matches(compact) -> return VoiceCommand.Resume
        LYRICS.matches(compact) -> return VoiceCommand.NowPlaying
        HOME.matches(compact) -> return VoiceCommand.Go(VoiceCommand.Page.Home)
        LIBRARY.matches(compact) -> return VoiceCommand.Go(VoiceCommand.Page.Library)
        BROWSE.matches(compact) -> return VoiceCommand.Go(VoiceCommand.Page.Browse)
        SEARCH_PAGE.matches(compact) -> return VoiceCommand.Go(VoiceCommand.Page.Search)
        ARTIST.matches(compact) -> return VoiceCommand.CurrentArtist
        ALBUM.matches(compact) -> return VoiceCommand.CurrentAlbum
        FAVORITE.matches(compact) -> return VoiceCommand.Favorite
        SHUFFLE.matches(compact) -> return VoiceCommand.Shuffle
    }
    OPEN_PREFIX.find(compact)?.let { prefix ->
        val label = compact.substring(prefix.range.last + 1).trimLabel()
        if (label.isNotEmpty()) return VoiceCommand.Open(label)
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

private fun String.trimLabel(): String = replace(Regex("^(一下|那个|这个)"), "").replace(Regex("(页面|一下|吧)$"), "")

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

private val BACKWARD = setOf("快退", "后退", "倒退", "退回", "往前", "向前", "倒回")

// "第三个" / "播放第2首" / "选第十二项"; the whole step must be about the position.
private val PICK = Regex("^(?:请|帮我)?(?:播放|放|选|选择|点|点击|打开|听)?第([0-9]{1,2}|[一二两三四五六七八九十]{1,3})(?:个|首|项|条|张|位)?(?:歌|歌曲)?$")
private val SEEK = Regex("^(?:请|帮我)?(快进|前进|往后|向后|快退|后退|倒退|退回|往前|向前|倒回)(?:拖)?(一点|一下|[0-9]{1,3}秒|[0-9]{1,2}分钟|[一二两三四五六七八九十]{1,3}秒|[一二两三四五六七八九十]{1,3}分钟)?$")
private val SCROLL = Regex("^(.*?)(?:向|往)?(上|下|左|右)(?:滑|翻|滚|拉|划)(?:动)?(?:一点|一些|一下)?$")
private val NEXT_PAGE = Regex("下一页|翻页|往下看")
private val THEN = Regex("然后|接着|并且")
private val INSIDE = Regex("里的|中的|里面的|里边的")
private val GO_THEN_LOOK = Regex("^(?:进|去|进入|打开|到)(资料库|主页|首页|最新发行|搜索)(?:里|里面)?(?:看|找|打开|进)(.+)$")
private val PUNCTUATION = Regex("[，。！？、,.!?;；:：\"“”'‘’()（）]")
private val SPACES = Regex("\\s+")
private val PERSONAL_STATION = Regex("我的电台|个人电台|私人电台|专属电台|我的广播|为我推荐的电台")
private val PREVIOUS = Regex("上一首|上一曲|上首|前一首|上一个")
private val NEXT = Regex("下一首|下一曲|下首|下一个|切歌|换一首|换首|换歌|跳过")
private val PAUSE = Regex("(请|帮我)?(暂停|停止|停一下|停下|别放了|不要放了|不听了|关掉音乐|停)(播放|音乐|一下)?")
private val RESUME = Regex("(请|帮我)?(继续播放|继续|接着放|接着播|接着听|恢复播放|开始播放|播放音乐|播放|放歌)")
private val LYRICS = Regex("(打开|进入|看|显示|去)?(歌词|歌词页|歌词页面|播放页|播放页面|正在播放|播放界面)")
private val HOME = Regex("(回|回到|去|打开|进入|返回)?(主页|首页|主界面|现在收听)")
private val LIBRARY = Regex("(去|打开|进入|进|回到|看)?(资料库|我的音乐|音乐库)")
private val BROWSE = Regex("(去|打开|进入|进|看)?(最新发行|新歌|浏览)")
private val SEARCH_PAGE = Regex("(去|打开|进入)?(搜索页|搜索页面|搜索)")
private val ARTIST = Regex("(看|打开|进|进入|去)?(歌手主页|歌手页|艺人主页|艺人页|这个歌手|这位歌手|歌手的主页)")
private val ALBUM = Regex("(看|打开|进|进入|去)?(整张专辑|完整专辑|这张专辑|专辑页|所在专辑|这首歌的专辑)")
private val FAVORITE = Regex("(请|帮我)?(收藏|喜欢|加入喜好|加到喜好|添加到喜好|加入收藏|点赞)(这首|这首歌|这首歌曲|歌曲|它)?")
private val SHUFFLE = Regex("(打开|开启|开始)?(随机播放|随机|乱序播放|乱序|打乱)")
private val OPEN_PREFIX = Regex("^(?:请|帮我)?(打开|进入|进|去|点|点击|看看|看|选择|选)")
private val PLAY_PREFIX = Regex(
    "^(请|麻烦)?(帮我|给我|替我)?(播放|放一首|放首|放一下|放点|放|来一首|来首|来点|来个|我想听|我要听|想听|听一下|听听|听|搜索|搜一下|搜|找一下|找)",
)
private val QUERY_LEAD = Regex("^(一首|一下|首|点|一些|些|个)")
private val QUERY_SUFFIX = Regex("(的歌曲|的歌|的音乐|这首歌|这首|的|吧|呗|一下|好吗|可以吗)$")
