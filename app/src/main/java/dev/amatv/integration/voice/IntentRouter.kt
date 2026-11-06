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
        data object Unknown : Action
    }
    data class Decision(val action: Action, val evidence: String, val failure: Failure? = null)
    /** Caller stores nextContext only if this request is still current. No execution occurs here. */
    data class Result(val steps: List<Decision>, val nextContext: Context)

    fun route(spoken: String, context: Context = Context()): Result {
        if (spoken.length > 512) return Result(listOf(Decision(Action.Unknown, "input-limit", Failure.TooLong)), context)
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

    private fun one(raw: String, ctx: Context): Result {
        val text = raw.trim().trimEnd('。', '！', '？', '!', '?').replace(polite, "").trim()
        val compact = text.replace(" ", "").removeSuffix("吧").removeSuffix("呗")
        fun result(action: Action, evidence: String, failure: Failure? = null, state: Context = ctx) =
            Result(listOf(Decision(action, evidence, failure)), state)
        fun newTask(action: Action, evidence: String, failure: Failure? = null) = result(action, evidence, failure,
            ctx.copy(candidateIds = emptyList(), awaitingConfirmation = false, awaitingMood = false,
                pendingMood = null, clarificationUsed = false, correctionWindowOpen = false))
        fun clarify(question: String, failure: Failure, mood: String? = null): Result =
            if (ctx.clarificationUsed) result(Action.Unknown, "clarification-budget", Failure.ClarificationExhausted,
                ctx.copy(awaitingMood = false, awaitingConfirmation = false))
            else result(Action.Clarify(question), "missing-or-ambiguous-slot", failure,
                ctx.copy(clarificationUsed = true, awaitingMood = mood != null, pendingMood = mood))

        // Protect quoted names before interpreting control words or splitting compound actions.
        quoted.matchEntire(text)?.let {
            val name = it.groupValues[1]
            return newTask(Action.PlayNamed(VoiceCommand.Play(name, VoiceCommand.Kind.Song, title = name, kindExplicit = true)), "quoted-name")
        }
        namedTitle.matchEntire(text)?.let {
            val name = it.groupValues[1].trim()
            return newTask(Action.PlayNamed(VoiceCommand.Play(name, VoiceCommand.Kind.Song, title = name, kindExplicit = true)), "explicit-title")
        }
        if (compact in affirmations) return if (ctx.awaitingConfirmation) {
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
        if (compact in setOf("下一页", "上一页")) return if (ctx.candidateIds.isNotEmpty()) {
            val delta = if (compact == "下一页") 1 else -1
            val size = ctx.pageSize.coerceAtLeast(1)
            val page = (ctx.candidatePage + delta).coerceIn(0, (ctx.candidateIds.size - 1) / size)
            result(Action.CandidatePage(page - ctx.candidatePage), "candidate-pagination", state = ctx.copy(candidatePage = page))
        } else newTask(Action.Control(VoiceCommand.Scroll(if (compact == "下一页") VoiceCommand.Direction.Down else VoiceCommand.Direction.Up, null)), "page-control")
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
        if (sequential.matches(compact)) return newTask(Action.SetShuffle(false), "explicit-mode")
        if (shuffle.matches(compact)) return newTask(Action.SetShuffle(true), "explicit-mode")
        repeat.matchEntire(compact)?.let {
            val mode = when (it.groupValues[1]) { "单曲循环" -> Repeat.One; "列表循环" -> Repeat.All; else -> Repeat.Off }
            return newTask(Action.SetRepeat(mode), "explicit-repeat")
        }
        if (compact in startMusic) return newTask(Action.StartMusic(if (ctx.hasQueue) StartSource.Queue else StartSource.PersonalStation), "generic-music-request")
        (explicitScene.matchEntire(compact) ?: scene.matchEntire(compact))?.let {
            return newTask(Action.Recommend(scene = it.groupValues[1]), "scene-slot")
        }
        mood.matchEntire(compact)?.let {
            val value = it.groupValues[1]
            if (value in ambiguousMoods) {
                // This is a new request unless replying to the existing one-question clarification.
                val state = if (ctx.awaitingMood) ctx else ctx.copy(clarificationUsed = false)
                return if (state.clarificationUsed) result(Action.Unknown, "clarification-budget", Failure.ClarificationExhausted,
                    state.copy(awaitingMood = false)) else result(Action.Clarify("想听舒缓的，还是提提精神的？"), "ambiguous-mood", state =
                    state.copy(clarificationUsed = true, awaitingMood = true, pendingMood = value,
                        candidateIds = emptyList(), awaitingConfirmation = false, correctionWindowOpen = false))
            }
            return newTask(Action.Recommend(mood = value), "mood-slot")
        }
        energy.matchEntire(compact)?.let { return newTask(Action.Recommend(energy = it.groupValues[1]), "energy-slot") }
        val legacy = parseVoiceCommand(text)
        if (legacy !is VoiceCommand.Play && legacy !is VoiceCommand.Open && legacy != VoiceCommand.Nothing) {
            return newTask(Action.Control(legacy), "recognized-control")
        }
        // Legacy entity parsing is used only after an explicit naming grammar has passed.
        if (nameRequest.containsMatchIn(text) && legacy is VoiceCommand.Play && !unsupportedTarget.containsMatchIn(compact)) {
            return newTask(Action.PlayNamed(legacy), "explicit-name-request")
        }
        return if (ctx.awaitingMood) result(Action.Unknown, "clarification-budget", Failure.ClarificationExhausted,
            ctx.copy(awaitingMood = false)) else result(Action.Unknown, "no-supported-intent", Failure.Unrecognized)
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
            val separator = if (depth == 0) separators.find { text.startsWith(it, index) } else null
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
    private val polite = Regex("^(?:请|麻烦)?(?:帮我|给我|替我)?")
    private val quoted = Regex("(?:播放|放|来一首|我想听)?《(.+)》")
    private val namedTitle = Regex("(?:播放)?(?:歌名叫|歌曲叫)(.+)")
    private val affirmations = setOf("对", "是", "是的", "嗯", "播吧", "播", "没错")
    private val denials = setOf("不是", "不对", "不是这个", "不是这首", "不是这首歌", "错了", "播错了", "换一个")
    private val energyAnswers = mapOf("舒缓的" to "舒缓", "舒缓" to "舒缓", "提提精神" to "提神", "提神的" to "提神", "提神" to "提神")
    private const val NUM = "([0-9]+|[一二两三四五六七八九十]+)"
    private val collection = Regex("(?:播放|放|听)?(?:我的)?(?:喜好列表|喜好歌曲|喜欢的歌|收藏的歌)(?:里面的|里的|中的|里)第${NUM}(?:首歌?|个|首歌曲)")
    private val pick = Regex("(?:播放|放|选|选择|打开)?第${NUM}(?:个|首歌?|张专辑|个歌单)")
    private val sequential = Regex("(?:改成|切换到|设置为|开启)?顺序播放|(?:关闭|取消)随机播放")
    private val shuffle = Regex("(?:改成|切换到|设置为|开启|打开)?随机播放")
    private val repeat = Regex("(?:改成|切换到|设置为|开启)?(单曲循环|列表循环|关闭循环|取消循环)")
    private val startMusic = setOf("来点音乐", "来点歌", "放点歌", "放点音乐", "播放音乐", "放歌", "播放", "来点儿音乐")
    private val scene = Regex("(?:播放|放点|来点)(?:适合)?(夜间开车|开车|深夜|健身|学习|睡前|工作|聚会)(?:氛围|时候|时)?的音乐")
    private val explicitScene = Regex("(?:播放|放点|来点)适合(.+?)(?:氛围|时候|时)?的音乐")
    private val mood = Regex("我(?:现在)?(?:感觉|有点|很|感觉有点|感觉很)?(难过|烦|累|开心|兴奋|疲惫|孤独)[，, ]*(?:给我|帮我)?(?:来点歌|放音乐|放点歌|来点音乐|播放音乐)")
    private val ambiguousMoods = setOf("难过", "烦")
    private val energy = Regex("(?:播放|来点|放点)(舒缓|提神|轻松|安静)(?:的)?(?:音乐|歌)")
    private val nameRequest = Regex("^(?:播放|放一首|放首|来一首|我想听|我要听|听一下|听听)\\s*.+")
    private val unsupportedTarget = Regex("(?:适合|氛围|感觉|来点|天气|音量)")
    private val separators = listOf("然后", "接着", "并且", "再")
    private val actionLead = Regex("^(?:播放|放|暂停|继续|改成|开启|关闭|来点|切换到|设置为)")
}
