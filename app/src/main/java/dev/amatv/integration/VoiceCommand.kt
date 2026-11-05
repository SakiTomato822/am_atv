package dev.amatv.integration

/** One thing a spoken sentence asks for; a sentence can hold several (see [parseVoiceSteps]). */
sealed interface VoiceCommand {
    data object Pause : VoiceCommand
    data object Cancel : VoiceCommand
    data object Resume : VoiceCommand
    data object Next : VoiceCommand
    data object Previous : VoiceCommand
    /** Move within the song by [seconds] (negative: back). */
    data class Seek(val seconds: Int) : VoiceCommand
    /** "往前一点": a seek of unclear direction; the app asks for 快进 or 快退. */
    data object SeekDirectionUnclear : VoiceCommand
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
    data class Play(
        val query: String,
        val kind: Kind = Kind.Any,
        val title: String? = null,
        val artist: String? = null,
        val album: String? = null,
    ) : VoiceCommand
    data class Search(val query: String) : VoiceCommand
    data object Nothing : VoiceCommand

    enum class Page { Home, Browse, Library, Search }
    enum class Direction { Up, Down, Left, Right }
    enum class Kind { Any, Song, Album, Artist, Playlist, Station }
}

/**
 * Splits a sentence into steps: "进资料库看专辑" -> [Go(Library), Open(专辑)], "播放喜好歌曲里的第三首"
 * -> [Open(喜好歌曲), Pick(3)]. Each step is parsed by [parseVoiceCommand].
 */
fun parseVoiceSteps(spoken: String): List<VoiceCommand> {
    val text = spoken.replace(PUNCTUATION, " ").replace(SPACES, " ").trim()
    if (text.isEmpty()) return listOf(VoiceCommand.Nothing)
    val parts = mutableListOf<String>()
    val pieces = if (FIRST_STEP.containsMatchIn(text)) {
        text.removePrefix("先").split(THEN_AFTER_FIRST)
    } else text.split(THEN)
    for (rawPiece in pieces) {
        val piece = rawPiece.trim()
        val inside = INSIDE.find(piece)
        if (inside != null && inside.range.first > 0 && inside.range.last < piece.lastIndex) {
            val container = parseVoiceCommand(piece.substring(0, inside.range.first))
            parts += when (container) {
                is VoiceCommand.Play -> "打开" + container.query
                else -> piece.substring(0, inside.range.first)
            }
            parts += piece.substring(inside.range.last + 1)
            continue
        }
        // "进资料库看专辑": go somewhere, then look at something there.
        val go = GO_THEN_LOOK.matchEntire(piece.replace(" ", ""))
        if (go != null) {
            parts += go.groupValues[1]
            parts += "打开" + go.groupValues[2]
            continue
        }
        parts += piece
    }
    val cleaned = parts.map { it.trim() }.filter { it.isNotEmpty() }
    val steps = cleaned.flatMap { step ->
        val command = parseVoiceCommand(step)
        if (step.startsWith("随机播放") && command is VoiceCommand.Play && command.kind == VoiceCommand.Kind.Artist) {
            listOf(command, VoiceCommand.Shuffle)
        } else listOf(command)
    }.filter { it != VoiceCommand.Nothing }
    return steps.ifEmpty { listOf(VoiceCommand.Nothing) }
}

/** One step: fixed Chinese phrasings, no language model. Anything else is a name to open or play. */
fun parseVoiceCommand(spoken: String): VoiceCommand {
    val text = spoken.replace(PUNCTUATION, " ").replace(SPACES, " ").trim().lowercase()
    if (text.isEmpty()) return VoiceCommand.Nothing
    val compact = text.replace(" ", "").removeSuffix("吧").removeSuffix("呗")
    if (compact == "取消" || NEGATED_CONTROL.containsMatchIn(compact)) return VoiceCommand.Cancel
    PICK.find(compact)?.let { match ->
        val number = match.groupValues[1].let { it.toIntOrNull() ?: chineseNumber(it) }
        if (number != null && number > 0) return VoiceCommand.Pick(number)
    }
    if (UNCLEAR_SEEK.matches(compact)) return VoiceCommand.SeekDirectionUnclear
    SEEK.matchEntire(compact)?.takeUnless { it.groupValues[1].length == 1 && it.groupValues[2].isEmpty() }?.let { match ->
        val back = match.groupValues[1] in BACKWARD
        val amount = match.groupValues[2]
        val seconds = when (amount) {
            "半分钟" -> 30
            "", "一点", "一下", "一些" -> 10
            else -> {
                val minutes = MINUTES.containsMatchIn(amount)
                val number = amount.replace(UNIT, "")
                (number.toIntOrNull() ?: chineseNumber(number) ?: 10) * (if (minutes) 60 else 1)
            }
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
        PREVIOUS.matches(compact) -> return VoiceCommand.Previous
        NEXT_PAGE.matches(compact) -> return VoiceCommand.Scroll(VoiceCommand.Direction.Down, null)
        NEXT.matches(compact) -> return VoiceCommand.Next
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
        UNSUPPORTED.containsMatchIn(compact) -> return VoiceCommand.Nothing
    }
    SEARCH_PREFIX.find(text)?.let { prefix ->
        val target = text.substring(prefix.range.last + 1).trim()
        val query = target.replace(SEARCH_SONG_LEAD, "").trim().ifEmpty { target }
        if (query.isNotEmpty()) return VoiceCommand.Search(query)
    }
    OPEN_PREFIX.find(compact)?.let { prefix ->
        val label = compact.substring(prefix.range.last + 1).trimLabel()
        if (label.isNotEmpty()) return VoiceCommand.Open(label)
    }
    var query = text.removePrefix("随机播放")
    PLAY_PREFIX.find(query)?.let { query = query.substring(it.range.last + 1) }
    query = query.trim()
    val namedTarget = query.replace(REQUEST_END, "").trim()
    if (FAVORITE_LIST.matches(namedTarget.replace(" ", ""))) {
        return VoiceCommand.Play("喜好歌曲", VoiceCommand.Kind.Playlist)
    }
    if (namedTarget.endsWith("电台") || namedTarget.endsWith("電台")) {
        val title = namedTarget.removePrefix("我的")
        return VoiceCommand.Play(title, VoiceCommand.Kind.Station, title = title)
    }
    ALBUM_TARGET.matchEntire(namedTarget)?.let { match ->
        val artist = match.groupValues[1].trim().ifEmpty { null }
        val album = match.groupValues[2].ifEmpty { match.groupValues[3] }.trim()
        if (album.isNotEmpty()) {
            return VoiceCommand.Play(listOfNotNull(artist, album).joinToString(" "), VoiceCommand.Kind.Album,
                artist = artist, album = album)
        }
    }
    EXPLICIT_SONG.matchEntire(namedTarget)?.let { match ->
        val artist = match.groupValues[1].trim()
        val title = match.groupValues[2].trim()
        return VoiceCommand.Play("$artist $title", VoiceCommand.Kind.Song, title = title, artist = artist)
    }
    ARTIST_TARGET.matchEntire(namedTarget)?.let { match ->
        val artist = match.groupValues[1].ifEmpty { match.groupValues[2] }.trim()
        if (artist.isNotEmpty()) return VoiceCommand.Play(artist, VoiceCommand.Kind.Artist, artist = artist)
    }
    while (true) {
        val withoutSuffix = query.replace(QUERY_SUFFIX, "").trim()
        val withoutLead = withoutSuffix.replace(QUERY_LEAD, "").trim()
        val trimmed = if (withoutLead.length >= 2) withoutLead else withoutSuffix
        if (trimmed == query) break
        query = trimmed
    }
    // "我的喜好歌曲" names the list "喜好歌曲" (but "我的天空" is a song title: keep it).
    if (query.startsWith("我的") && LIST_WORDS.containsMatchIn(query)) query = query.removePrefix("我的")
    if (!query.startsWith("我的")) {
        SONG_TARGET.matchEntire(query)?.let { match ->
            val artist = match.groupValues[1].trim()
            val title = match.groupValues[2].trim()
            if (artist.isNotEmpty() && title.isNotEmpty()) {
                return VoiceCommand.Play("$artist $title", VoiceCommand.Kind.Song, title = title, artist = artist)
            }
        }
    }
    return if (query.length >= 2 || query.singleOrNull()?.isDigit() == true || query.any { it.code > 0x2E80 }) {
        VoiceCommand.Play(query, title = query)
    } else VoiceCommand.Nothing
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

// 往前/向前 can mean either direction, so they are not seeks at all (the user says 快进/快退).
private val BACKWARD = setOf("快退", "后退", "倒退", "退回", "倒回", "退", "回退", "往后退", "向后退", "往回退")
private val NEGATED_CONTROL = Regex("(?:不要|别)(?:切歌|换歌|换一首|下一首|上一首|暂停|播放|停止)")
private val UNCLEAR_SEEK = Regex("^(?:请|帮我)?(往前|向前)(?:拖|跳|走)?(一点|一下|一些|[0-9]{1,3}(?:秒钟|秒|s)|[一二两三四五六七八九十]{1,3}(?:秒钟|秒))?$")
private val UNIT = Regex("(秒钟|秒|s|sec|分钟|分|min)$")
private val MINUTES = Regex("(分钟|分|min)$")

// "第三个" / "播放第2首" / "选第十二项"; the whole step must be about the position.
private val PICK = Regex("^(?:请|帮我)?(?:播放|放|选|选择|点|点击|打开|听)?(?:搜索结果(?:的)?)?第([0-9]{1,2}|[一二两三四五六七八九十]{1,3})(?:个|首|项|条|张|位)?(?:歌|歌曲|专辑|歌单)?$")
// "快进30秒" / "退10s" / "往后一点" / "回退一分钟"; a lone 退/进 needs an amount ("进资料库" is not a seek).
private val SEEK = Regex("^(?:请|帮我)?(快进|前进|往后退|向后退|往回退|往后|向后|快退|后退|倒退|退回|回退|倒回|退|进)(?:了|拖|个)?(一点|一下|一些|半分钟|[0-9]{1,3}(?:秒钟|秒|s|sec)|[0-9]{1,2}(?:分钟|分|min)|[一二两三四五六七八九十]{1,3}(?:秒钟|秒|分钟|分))?$")
private val SCROLL = Regex("^(.*?)(?:向|往)?(上|下|左|右)(?:滑|翻|滚|拉|划)(?:动)?(?:一点|一些|一下)?$")
private val NEXT_PAGE = Regex("下一页|翻页|往下看")
private val THEN = Regex("然后|接着|并且")
private const val STEP_ACTION = "播放|放|暂停|继续|切|换|打开|搜索|搜|找|快进|快退|下一|上一"
private val FIRST_STEP = Regex("^先(?=\\s*(?:$STEP_ACTION))")
private val THEN_AFTER_FIRST = Regex("然后|接着|并且|再(?=\\s*(?:$STEP_ACTION))")
private val INSIDE = Regex("里的|中的|里面的|里边的")
private val GO_THEN_LOOK = Regex("^(?:进|去|进入|打开|到)(资料库|主页|首页|最新发行|搜索)(?:里|里面)?(?:看|找|打开|进)(.+)$")
private val PUNCTUATION = Regex("[，。！？、,.!?;；:：\"“”‘()（）]|(?<![a-zA-Z])['’]|['’](?![a-zA-Z])")
private val SPACES = Regex("\\s+")
private val PERSONAL_STATION = Regex("我的电台|个人电台|私人电台|专属电台|我的广播|为我推荐的电台")
private const val CONTROL_PREFIX = "(?:请|帮我|给我)?(?:播放|放|切到|来)?"
private const val CONTROL_SUFFIX = "(?:吧|呗|一下)?"
private val PREVIOUS = Regex("${CONTROL_PREFIX}(?:上一首|上一曲|上首|前一首|上一个)$CONTROL_SUFFIX")
private val NEXT = Regex("${CONTROL_PREFIX}(?:下一首|下一曲|下首|下一个|切歌|换一首歌?|换首歌?|换歌|跳过)$CONTROL_SUFFIX")
private val PAUSE = Regex("${CONTROL_PREFIX}(?:暂停|停止|停一下|停下|别放了|不要放了|不听了|关掉音乐|关闭音乐|停)(?:播放|音乐)?$CONTROL_SUFFIX")
private val RESUME = Regex("(请|帮我)?(继续播放|继续|接着放|接着播|接着听|恢复播放|开始播放|播放音乐|播放|放歌)")
private val LYRICS = Regex("(打开|进入|看|显示|去)?(歌词|歌词页|歌词页面|播放页|播放页面|正在播放|播放界面)")
private val HOME = Regex("(回|回到|去|打开|进入|返回)?(主页|首页|主界面|现在收听)")
private val LIBRARY = Regex("(去|打开|进入|进|回到|看)?(资料库|我的音乐|音乐库)")
private val BROWSE = Regex("(去|打开|进入|进|看)?(最新发行|新歌|浏览)")
private val SEARCH_PAGE = Regex("(去|打开|进入)?(搜索页|搜索页面|搜索)")
private val ARTIST = Regex("(看|打开|进|进入|去)?(歌手主页|歌手页|艺人主页|艺人页|这个歌手|这位歌手|歌手的主页)")
private val ALBUM = Regex("(看|打开|进|进入|去|播放)?(整张专辑|完整专辑|这张专辑|当前专辑|现在这张专辑|专辑页|所在专辑|这首歌的专辑)")
private val FAVORITE = Regex("(?:请|帮我)?(?:(?:收藏|喜欢|加入喜好|加到喜好|添加到喜好|加入收藏|点赞)(?:这首|这首歌|这首歌曲|歌曲|它)?|(?:把)?(?:这首歌?)?(?:加到|加入|添加到|收藏到|放进)(?:我的)?(?:喜好|收藏|喜欢)(?:歌曲|的歌)?)")
private val SHUFFLE = Regex("(打开|开启|开始)?(随机播放|随机|乱序播放|乱序|打乱)")
private val OPEN_PREFIX = Regex("^(?:请|帮我)?(打开|进入|进|去|点|点击|看看|看|选择|选)")
private val SEARCH_PREFIX = Regex("^(?:请|麻烦)?(?:帮我|给我)?(?:搜索|搜一下|搜|找一下|找)")
private val SEARCH_SONG_LEAD = Regex("^(?:一首|这首|首)?歌曲?")
private val UNSUPPORTED = Regex("^(?:请|帮我|给我|把)?(?:调大|调小|(?:把)?音量|(?:把)?声音调|关闭|取消|返回|(?:我要)?回家|导航|空调|(?:今天天气|天气)|(?:现在)?几点|这首歌叫什么|叫什么名字|我?不喜欢(?:这首|这个|这歌)?)")
private val PLAY_PREFIX = Regex(
    "^(请|麻烦)?(帮我|给我|替我)?(播放|放一首|放首|放一下|放点|放|来一首|来首|来点|来个|我想听|我要听|想听|听一下|听听|听)",
)
private val REQUEST_END = Regex("(?:吧|呗|一下|好吗|可以吗)$")
private val FAVORITE_LIST = Regex("(?:我的?)?(?:喜好歌曲|喜欢的歌(?:曲)?|收藏的歌(?:曲)?)")
private val ALBUM_TARGET = Regex("(?:(.+?)的\\s*)?(?:(?:整[张張](?:专辑|專輯)?|专辑|專輯)\\s*(.+)|(.+?)\\s*(?:专辑|專輯))")
private val ARTIST_TARGET = Regex("(?:(.+?)(?:的所有歌曲|所有歌曲|的歌曲|的歌|的音乐)|歌手\\s*(.+))")
private val EXPLICIT_SONG = Regex("歌手\\s*(.+?)的\\s*(?:歌曲|歌)?\\s*(.+)")
private val SONG_TARGET = Regex("(.+?)(?:唱的|来一首|的)(.+)")
private val LIST_WORDS = Regex("喜好|收藏|喜欢|歌单|播放列表|列表|电台|资料库")
private val QUERY_LEAD = Regex("^(一首|一下|点|一些|些|个)")
private val QUERY_SUFFIX = Regex("(的歌曲|的歌|的音乐|这首歌|这首|的|吧|呗|一下|好吗|可以吗)$")
