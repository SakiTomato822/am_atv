package dev.amatv.integration.voice

import dev.amatv.integration.VoiceCommand
import dev.amatv.integration.chineseNumber
import dev.amatv.integration.parseVoiceCommand

/** Pure preparation module. No player, network, UI or clock access; not wired into the app yet. */
object IntentRouter {
    enum class Repeat { Off, One, All }
    enum class StartSource { Queue, PersonalStation }
    enum class PickScope { Candidates, Collection }
    enum class Failure { Unrecognized, MissingContext, CollectionUnavailable, InvalidOrdinal, ClarificationExhausted, TooLong }
    data class Collection(val id: String, val orderSnapshot: String, val loaded: Boolean)
    data class Context(
        val followUp: Boolean = false,
        val hasQueue: Boolean = false,
        val candidateIds: List<String> = emptyList(),
        val candidatePage: Int = 0,
        val pageSize: Int = 3,
        val awaitingConfirmation: Boolean = false,
        val awaitingMood: Boolean = false,
        val clarificationUsed: Boolean = false,
        val pendingMood: String? = null,
        val currentCollection: Collection? = null,
        val favorites: Collection? = null,
        val correctionWindowOpen: Boolean = false,
    )
    sealed interface Action {
        /** Feed these typed fields into R3 retrieval; never treat the query as a catalog ID. */
        data class PlayNamed(val target: VoiceCommand.Play) : Action
        /** Null ID/snapshot means resolve the collection, not use the current screen's list. */
        data class PlayFromCollection(val collectionId: String?, val ordinal: Int, val orderSnapshot: String?, val ready: Boolean) : Action
        data class Pick(val ordinal: Int, val scope: PickScope, val entityId: String?, val orderSnapshot: String?) : Action
        data class SetShuffle(val enabled: Boolean) : Action
        data class SetRepeat(val mode: Repeat) : Action
        data class StartMusic(val source: StartSource) : Action
        /** Execution must prefer matching library playlists, then real Apple playlists/stations. */
        data class Recommend(val mood: String? = null, val scene: String? = null, val energy: String? = null) : Action
        data class Control(val command: VoiceCommand) : Action
        data class CandidatePage(val delta: Int) : Action
        data object Confirm : Action
        data object Deny : Action
        data class Clarify(val question: String) : Action
        enum class Domain { Other, VolumeRequest }
        data class OutOfDomain(val reason: Domain, val reply: String?) : Action
        data class NotUnderstood(val reply: String?) : Action
    }
    data class Candidate(val action: Action, val confidence: Double, val evidence: String)
    data class Decision(val action: Action, val evidence: String, val failure: Failure? = null, val alternatives: List<Candidate> = emptyList())
    /** Caller stores nextContext only if this request is still current. No execution occurs here. */
    data class Result(val steps: List<Decision>, val nextContext: Context)

    fun route(spoken: String, context: Context = Context()): Result {
        if (spoken.length > 512) return Result(listOf(Decision(unheard(context), "input-limit", Failure.TooLong)), context)
        val parts = split(spoken.trim())
        var state = context
        val steps = mutableListOf<Decision>()
        for (part in parts) {
            val result = one(part, state)
            steps += result.steps
            state = result.nextContext
            // A question/unresolved dependency must not allow later steps to run prematurely.
            if (result.steps.any { it.action is Action.Clarify || it.failure != null }) break
        }
        return Result(steps, state)
    }

    private fun unheard(ctx: Context) = Action.NotUnderstood(if (ctx.followUp || ctx.awaitingMood || ctx.awaitingConfirmation || ctx.candidateIds.isNotEmpty()) null else "没听清，请再说一次")

    private fun one(raw: String, ctx: Context): Result {
        val text = raw.trim().trimEnd('。', '！', '？', '!', '?', '吧', '呗').replace(polite, "").trim()
        val compact = text.replace(" ", "").removeSuffix("吧").removeSuffix("呗")
        fun result(action: Action, evidence: String, failure: Failure? = null, state: Context = ctx) =
            Result(listOf(Decision(action, evidence, failure)), state)
        fun newTask(action: Action, evidence: String, failure: Failure? = null) = result(action, evidence, failure,
            ctx.copy(candidateIds = emptyList(), awaitingConfirmation = false, awaitingMood = false,
                pendingMood = null, clarificationUsed = false, correctionWindowOpen = false))
        fun clarify(question: String, failure: Failure, mood: String? = null): Result =
            if (ctx.clarificationUsed) result(unheard(ctx), "clarification-budget", Failure.ClarificationExhausted,
                ctx.copy(awaitingMood = false, awaitingConfirmation = false))
            else result(Action.Clarify(question), "missing-or-ambiguous-slot", failure,
                ctx.copy(clarificationUsed = true, awaitingMood = mood != null, pendingMood = mood))

        fun uncertain(first: Candidate, second: Candidate): Result {
            val answer = clarify("你想怎么播放？", Failure.MissingContext)
            return answer.copy(steps = answer.steps.map { it.copy(alternatives = listOf(first, second)) })
        }
        val target = request.find(text)?.let { text.substring(it.range.last + 1).trim() }
        val start = Action.StartMusic(if (ctx.hasQueue) StartSource.Queue else StartSource.PersonalStation)

        // Protect quoted names before interpreting control words or splitting compound actions.
        quoted.matchEntire(text)?.let {
            val name = it.groupValues[1]
            return newTask(Action.PlayNamed(VoiceCommand.Play(name, VoiceCommand.Kind.Song, title = name, kindExplicit = true)), "quoted-name")
        }
        namedTitle.matchEntire(text)?.let {
            val name = it.groupValues[1].trim()
            return newTask(Action.PlayNamed(VoiceCommand.Play(name, VoiceCommand.Kind.Song, title = name, kindExplicit = true)), "explicit-title")
        }
        val domain = when {
            volume.containsMatchIn(compact) -> Action.Domain.VolumeRequest
            outside.containsMatchIn(compact) -> Action.Domain.Other
            else -> null
        }
        if (domain != null) {
            val reply = if (unheard(ctx).reply == null) null else when (domain) {
                Action.Domain.VolumeRequest -> "请用方向盘或屏幕上的音量键调整"
                Action.Domain.Other -> "我只能帮你放音乐"
            }
            return result(Action.OutOfDomain(domain, reply), "domain-slot", Failure.Unrecognized)
        }
        if (compact in affirmations && (compact != "播" || ctx.awaitingConfirmation)) return if (ctx.awaitingConfirmation) {
            result(Action.Confirm, "pending-confirmation", state = ctx.copy(awaitingConfirmation = false))
        } else clarify("你想播放什么？", Failure.MissingContext)
        if (compact in denials || compact == "换一首" && ctx.correctionWindowOpen) {
            return if (ctx.awaitingConfirmation || ctx.candidateIds.isNotEmpty() || ctx.correctionWindowOpen) {
                result(Action.Deny, "pending-choice-or-recent-play", state = ctx.copy(awaitingConfirmation = false))
            } else clarify("你想换成什么？", Failure.MissingContext)
        }
        if (ctx.awaitingMood && compact in energyAnswers) return result(
            Action.Recommend(mood = ctx.pendingMood, energy = energyAnswers.getValue(compact)), "mood-answer",
            state = ctx.copy(awaitingMood = false, pendingMood = null))
        val forward = nextPage.matches(compact)
        if (forward || previousPage.matches(compact)) return if (ctx.candidateIds.isNotEmpty()) {
            val delta = if (forward) 1 else -1
            val size = ctx.pageSize.coerceAtLeast(1)
            val page = (ctx.candidatePage + delta).coerceIn(0, (ctx.candidateIds.size - 1) / size)
            result(Action.CandidatePage(page - ctx.candidatePage), "candidate-pagination", state = ctx.copy(candidatePage = page))
        } else newTask(Action.Control(VoiceCommand.Scroll(if (forward) VoiceCommand.Direction.Down else VoiceCommand.Direction.Up, null)), "page-control")
        collection.matchEntire(compact)?.let {
            val ordinal = number(it.groupValues[1])
            if (ordinal == null || ordinal <= 0) return clarify("请选择有效的歌曲序号", Failure.InvalidOrdinal)
            val source = ctx.favorites
            return newTask(Action.PlayFromCollection(source?.id, ordinal, source?.orderSnapshot, source?.loaded == true),
                "favorites-ordinal", if (source?.loaded == true) null else Failure.CollectionUnavailable)
        }
        pick.matchEntire(compact)?.let {
            val ordinal = number(it.groupValues[1])
            if (ordinal == null || ordinal <= 0) return clarify("请选择有效的歌曲序号", Failure.InvalidOrdinal)
            if (ctx.candidateIds.isNotEmpty()) {
                val size = ctx.pageSize.coerceAtLeast(1)
                val id = ctx.candidateIds.getOrNull(ctx.candidatePage * size + ordinal - 1)
                if (ordinal > size || id == null) return clarify("请选择当前页的序号", Failure.InvalidOrdinal)
                return result(Action.Pick(ordinal, PickScope.Candidates, id, null), "candidate-ordinal")
            }
            ctx.currentCollection?.let { source ->
                return result(Action.Pick(ordinal, PickScope.Collection, source.id, source.orderSnapshot), "collection-ordinal",
                    if (source.loaded) null else Failure.CollectionUnavailable)
            }
            return clarify("要播放哪个列表里的歌曲？", Failure.MissingContext)
        }
        val modeInput = if (modeToken.replace(compact, "").isEmpty()) compact else target?.replace(" ", "") ?: compact
        val words = modeToken.findAll(modeInput).map { it.value }.toSet()
        if (modeToken.replace(modeInput, "").isEmpty()) {
            val negative = words.any { it in negation }
            val ordered = words.any { it in orderWords }
            val random = words.any { it in randomWords }
            val loop = words.any { it in loopWords }
            val single = words.any { it in singleWords }
            val repeat = Action.SetRepeat(if (negative) Repeat.Off else if (single) Repeat.One else Repeat.All)
            val shuffle = Action.SetShuffle(!negative && random)
            if (ordered && random || loop && (ordered || random)) return uncertain(
                Candidate(if (loop) repeat else Action.SetShuffle(false), .58, "mode-slot"),
                Candidate(shuffle, .52, "competing-mode-slot"))
            if (loop) return newTask(repeat, "repeat-slots")
            if (ordered || random) return newTask(Action.SetShuffle(if (ordered) negative else !negative), "shuffle-slots")
        }
        val trackInput = target?.replace(" ", "")?.takeIf(track::matches) ?: compact
        if (track.matches(trackInput)) return newTask(Action.Control(
            if (trackInput.startsWith("下")) VoiceCommand.Next else VoiceCommand.Previous), "relative-track")
        if (resume.matches(compact)) return newTask(Action.Control(VoiceCommand.Resume), "resume-slots")
        if (target != null && generic.matches(target.replace(" ", ""))) return newTask(start, "generic-object")
        val moodMatch = moodLead.find(compact)
        val rest = moodMatch?.let { compact.substring(it.range.last + 1).trimStart('，', ',').replace(polite, "") }
        if (moodMatch != null && rest != null && request.containsMatchIn(rest) && generic.matches(request.replace(rest, ""))) {
            val value = moods.getValue(moodMatch.groupValues[1])
            if (value in ambiguousMoods) {
                // This is a new request unless replying to the existing one-question clarification.
                val state = if (ctx.awaitingMood) ctx else ctx.copy(clarificationUsed = false)
                return if (state.clarificationUsed) result(unheard(ctx), "clarification-budget", Failure.ClarificationExhausted,
                    state.copy(awaitingMood = false)) else result(Action.Clarify("想听舒缓的，还是提提精神的？"), "ambiguous-mood", state =
                    state.copy(clarificationUsed = true, awaitingMood = true, pendingMood = value,
                        candidateIds = emptyList(), awaitingConfirmation = false, correctionWindowOpen = false))
            }
            return newTask(Action.Recommend(mood = value), "mood-slot")
        }
        val descriptor = (target ?: text).replace(" ", "")
        energy.matchEntire(descriptor)?.let {
            return newTask(Action.Recommend(energy = it.groupValues[1]), "energy-object")
        }
        (explicitScene.matchEntire(descriptor) ?: timedScene.matchEntire(descriptor)
            ?: knownScene.matchEntire(descriptor))?.let {
            return newTask(Action.Recommend(scene = it.groupValues[1]), "scene-object")
        }
        val legacy = parseVoiceCommand(text)
        if (legacy !is VoiceCommand.Play && legacy !is VoiceCommand.Open && legacy != VoiceCommand.Nothing) {
            return newTask(Action.Control(legacy), "recognized-control")
        }
        if (!target.isNullOrBlank() && !reservedTarget.containsMatchIn(target)) {
            val named = parseVoiceCommand("播放 $target")
            if (named is VoiceCommand.Play) {
                if (descriptive.matches(target.replace(" ", ""))) return uncertain(
                    Candidate(Action.Recommend(scene = target.substringBeforeLast("的")), .57, "descriptive-object"),
                    Candidate(Action.PlayNamed(named), .48, "explicit-play-verb"))
                return newTask(Action.PlayNamed(named), "verb-and-name")
            }
        }
        return result(unheard(ctx), "no-supported-intent", Failure.Unrecognized)
    }

    private fun number(value: String) = value.toIntOrNull() ?: chineseNumber(value)
    private fun split(text: String): List<String> {
        val result = mutableListOf<String>()
        var depth = 0
        var start = if (text.startsWith("先")) 1 else 0
        var index = start
        while (index < text.length) {
            if (text[index] == '《') depth++
            if (text[index] == '》') depth--
            val separator = if (depth == 0 && index > start) separators.find { text.startsWith(it, index) } else null
            val next = separator?.let { text.substring(index + it.length).trimStart() }
            if (separator != null && next != null && actionLead.containsMatchIn(next)) {
                result += text.substring(start, index).trim().trimEnd('，', ',')
                index += separator.length
                start = index
            } else index++
        }
        result += text.substring(start).trim()
        return result
    }
    private fun vocabulary(words: kotlin.collections.Collection<String>) =
        words.sortedByDescending { it.length }.joinToString("|") { Regex.escape(it) }

    private val polite = Regex("^(?:请|麻烦|能不能)?\\s*(?:帮我|给我|替我)?\\s*")
    private val verbs = setOf("播放", "放", "播", "来", "听", "开始", "听听", "想听", "我想听", "我要听")
    private val verb = vocabulary(verbs)
    private val quantity = "(?:一首|首|一点儿?|点儿?|一些|些|几首|一下|听)?"
    private val request = Regex("^(?:$verb)$quantity\\s*")
    private val quoted = Regex("(?:(?:$verb)$quantity\\s*)?《(.+)》")
    private val namedTitle = Regex("(?:(?:$verb)$quantity\\s*)?(?:歌名叫|歌曲叫)(.+)")
    private val affirmations = setOf("对", "是", "是的", "嗯", "播", "没错", "好", "好啊", "好的", "可以", "行", "就这个", "就是它", "对的", "可以的")
    private val denials = setOf("不是", "不对", "不是这个", "不是这首", "不是这首歌", "错了", "播错了", "换一个", "不", "不要", "不要这个", "不要这首", "不是它", "错", "换", "换个")
    private val energyAnswers = mapOf("舒缓的" to "舒缓", "舒缓" to "舒缓", "提提精神" to "提神", "提神的" to "提神", "提神" to "提神", "放松" to "舒缓", "轻松一点" to "舒缓")
    private const val NUM = "([0-9]+|[一二两三四五六七八九十]+)"
    private val unit = "(?:首(?:歌曲?)?|个(?:歌单|结果)?|项|张(?:专辑)?)"
    private val select = "(?:(?:$verb)$quantity|选择|选中|选|点开|点击|点|打开)?"
    private val collection = Regex("$select(?:我(?:的)?)?(?:喜好|喜欢|收藏|最爱)(?:的)?(?:歌曲?|歌单|播放列表|列表|夹)?(?:里面|里边|当中|中|里|内)(?:的)?第$NUM$unit")
    private val pick = Regex("$select(?:候选|结果|搜索结果)?(?:的)?第$NUM$unit")
    private val nextPage = Regex("(?:下(?:一)?页|(?:再|向下|往下)?翻(?:一页|页)|往后翻(?:一页)?)")
    private val previousPage = Regex("(?:上(?:一)?页|(?:往上|向上|往前)翻(?:一页)?|翻回(?:一页|上一页))")
    private val negation = setOf("不要", "别", "不", "关掉", "关闭", "取消", "停止", "禁用")
    private val orderWords = setOf("顺序", "按顺序", "依序", "依次")
    private val randomWords = setOf("随机", "打乱", "乱序")
    private val loopWords = setOf("循环", "重复")
    private val singleWords = setOf("单曲", "这首歌", "这首", "当前歌曲")
    private val modeToken = Regex(vocabulary(negation + orderWords + randomWords + loopWords + singleWords +
        setOf("列表", "歌单", "全部", "整张", "改成", "改为", "切到", "切换到", "切换成", "设置为", "设成", "设为",
            "开启", "打开", "启用", "模式", "把", "了", "地", "这张", "歌曲") + verbs))
    private val generic = Regex("(?:音乐|歌曲?|曲子|歌儿|曲儿)?")
    private val track = Regex("(?:上|前|下)(?:一)?(?:首|曲)(?:歌曲?)?")
    private val resume = Regex("(?:继续|接着|恢复)(?:播放|放|播|听)(?:音乐|歌曲?)?")
    private const val MUSIC = "(?:音乐|歌曲?|曲子)"
    private val explicitScene = Regex("适合(.+?)(?:氛围|时候|时)?的?$MUSIC")
    private val timedScene = Regex("(.+?)(?:(?:时候|时)(?:听|放|播)?的?|听的)$MUSIC")
    private val knownScene = Regex("(夜间开车|开车|深夜|健身|学习|睡前|工作|聚会)的$MUSIC")
    private val descriptive = Regex(".+的$MUSIC")
    private val moods = mapOf("难过" to "难过", "伤心" to "难过", "沮丧" to "难过", "烦" to "烦", "烦躁" to "烦",
        "心烦" to "烦", "累" to "累", "疲惫" to "疲惫", "开心" to "开心", "高兴" to "开心",
        "兴奋" to "兴奋", "孤独" to "孤独")
    private val moodWords = vocabulary(moods.keys)
    private val moodLead = Regex("^(?:我)?(?:现在|今天|刚刚)?(?:心情)?(?:感觉|觉得)?(?:有点|特别|非常|挺|很|有些)?($moodWords)")
    private val ambiguousMoods = setOf("难过", "烦")
    private val energy = Regex("(舒缓|提神|轻松|安静)(?:点|一点)?的?$MUSIC")
    private val volume = Regex("音量|(?:大声|小声)(?:一)?点|静音|声音(?:调大|调小)|(?:调大|调小)(?:声音)?$")
    private val outside = Regex("天气|导航|空调|温度|电话|短信|车窗|座椅|打给|拨打|发消息|聊天|讲(?:个)?笑话|随便聊|几点|你好|你是谁")
    private val reservedTarget = Regex("^(?:第[0-9一二三四五六七八九十]|不要|别|关闭|取消|不懂$|不清楚$)|适合|氛围|感觉|来点|天气|音量")
    private val separators = listOf("然后", "接着", "并且", "再")
    private val actionLead = Regex("^(?:$verb|暂停|继续|改成|改为|开启|关闭|切换到|设置为|设成|切到)")
}