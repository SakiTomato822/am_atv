package dev.amatv.integration.voice

import dev.amatv.integration.VoiceCommand
import dev.amatv.integration.parseVoiceCommand
import dev.amatv.integration.parseVoiceSteps
import dev.amatv.integration.voice.IntentRouter.Action
import dev.amatv.integration.voice.IntentRouter.Context
import dev.amatv.integration.voice.IntentRouter.Collection
import dev.amatv.integration.voice.IntentRouter.Failure
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class IntentRouterTest {
    private data class Signature(val intent: String, val slots: String)
    private fun signature(actions: List<Action>): Signature = Signature(
        actions.joinToString("+") { it.javaClass.simpleName }, actions.joinToString("+") { action ->
            when (action) {
                is Action.PlayNamed -> named(action.target)
                is Action.PlayFromCollection -> "${action.collectionId ?: "?"}:${action.ordinal}:${action.orderSnapshot ?: "?"}:${action.ready}"
                is Action.Pick -> "${action.scope}:${action.ordinal}:${action.entityId ?: "?"}:${action.orderSnapshot ?: "?"}"
                is Action.SetShuffle -> action.enabled.toString()
                is Action.SetRepeat -> action.mode.name
                is Action.StartMusic -> action.source.name
                is Action.Recommend -> "mood=${action.mood.orEmpty()};scene=${action.scene.orEmpty()};energy=${action.energy.orEmpty()}"
                is Action.Control -> action.command.toString()
                is Action.CandidatePage -> action.delta.toString()
                else -> "-"
            }
        })
    private fun named(p: VoiceCommand.Play) = "title=${p.title.orEmpty()};artist=${p.artist.orEmpty()};album=${p.album.orEmpty()};kind=${p.kind}"
    /** No context/ID inference added to baseline. Missing capabilities remain visible in the metrics. */
    private fun baseline(commands: List<VoiceCommand>): Signature = Signature(
        commands.joinToString("+") { when (it) {
            is VoiceCommand.Play -> "PlayNamed"
            is VoiceCommand.Pick -> "Pick"
            VoiceCommand.Nothing -> "Unknown"
            else -> "Control"
        } }, commands.joinToString("+") { when (it) {
            is VoiceCommand.Play -> named(it)
            is VoiceCommand.Pick -> "unscoped:${it.number}"
            VoiceCommand.Nothing -> "-"
            else -> it.toString()
        } })
    private fun context(name: String): Context = when (name) {
        "empty" -> Context()
        "queue" -> Context(hasQueue = true)
        "favorites" -> Context(favorites = Collection("fav", "v1", true))
        "loading" -> Context(favorites = Collection("fav", "v1", false))
        "list" -> Context(currentCollection = Collection("list", "v7", true))
        "candidates", "page2" -> Context(candidateIds = (1..6).map { "c$it" }, candidatePage = if (name == "page2") 1 else 0)
        "confirm" -> Context(candidateIds = listOf("c1"), awaitingConfirmation = true)
        "recent" -> Context(hasQueue = true, correctionWindowOpen = true)
        "mood" -> Context(awaitingMood = true, pendingMood = "难过", clarificationUsed = true)
        else -> error("Unknown matrix state $name")
    }
    private fun matrix(): File = generateSequence(File(requireNotNull(System.getProperty("user.dir")))) { it.parentFile }
        .map { File(it, "work/voice-eval/intent_cases.tsv") }.first { it.isFile }

    @Test fun taskStateMatrixAndBaselineComparison() {
        // Columns count exact intent sequence and exact slots (not merely a substring or top-level class).
        val totals = linkedMapOf<String, IntArray>()
        val failures = mutableListOf<String>()
        for (line in matrix().readLines(Charsets.UTF_8).drop(1).filter { it.isNotBlank() }) {
            val c = line.split('\t')
            assertEquals("TSV columns: $line", 7, c.size)
            val expected = Signature(c[4], c[5])
            val accepted = listOf(expected) + if (c[6] == "-") emptyList() else c[6].split("||").map {
                val fields = it.split("::", limit = 2)
                require(fields.size == 2) { "Alternative must be Intent::slots: $it" }
                Signature(fields[0], fields[1])
            }
            val routed = IntentRouter.route(c[2], context(c[3]))
            assertTrue(routed.steps.all { it.evidence.isNotBlank() })
            val actual = signature(routed.steps.map { it.action })
            val oldSingle = baseline(listOf(parseVoiceCommand(c[2])))
            val oldSteps = baseline(parseVoiceSteps(c[2]))
            val scores = totals.getOrPut(c[1]) { IntArray(7) }
            scores[0]++
            listOf(oldSingle, oldSteps, actual).forEachIndexed { index, value ->
                if (accepted.any { it.intent == value.intent }) scores[1 + index * 2]++
                if (value in accepted) scores[2 + index * 2]++
            }
            println("INTENT_CASE ${c[0]} ${c[2]} single=$oldSingle steps=$oldSteps router=$actual")
            if (actual !in accepted) failures += "${c[0]} ${c[2]} expected=$expected actual=$actual"
        }
        println("INTENT_METRICS category total singleIntent singleExact stepsIntent stepsExact routerIntent routerExact")
        totals.forEach { (category, counts) ->
            println("INTENT_METRICS $category ${counts.joinToString(" ")}")
            println("INTENT_RATES $category " + counts.drop(1).joinToString(" ") {
                java.lang.String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 * it / counts[0])
            })
        }
        val all = IntArray(7) { col -> totals.values.sumOf { it[col] } }
        println("INTENT_METRICS TOTAL ${all.joinToString(" ")}")
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test fun dialogueTransitionsAndInterruption() {
        val question = IntentRouter.route("我感觉难过，给我来点歌")
        assertTrue(question.nextContext.awaitingMood)
        val answer = IntentRouter.route("舒缓的", question.nextContext)
        assertEquals(Action.Recommend(mood = "难过", energy = "舒缓"), answer.steps.single().action)
        assertFalse(answer.nextContext.awaitingMood)
        val failed = IntentRouter.route("听不懂", question.nextContext)
        assertEquals(Action.Unknown, failed.steps.single().action)
        assertEquals(Failure.ClarificationExhausted, failed.steps.single().failure)
        assertFalse(failed.nextContext.awaitingMood)
        val interrupted = IntentRouter.route("播放我的天空", question.nextContext)
        assertTrue(interrupted.steps.single().action is Action.PlayNamed)
        assertFalse(interrupted.nextContext.awaitingMood)
        val candidates = context("candidates")
        val next = IntentRouter.route("下一页", candidates)
        assertEquals(1, next.nextContext.candidatePage)
        assertEquals("c5", (IntentRouter.route("第二个", next.nextContext).steps.single().action as Action.Pick).entityId)
        assertTrue(IntentRouter.route("播放晴天", candidates).nextContext.candidateIds.isEmpty())
    }

    @Test fun idempotenceAndUnresolvedCollectionNeverUseAnotherList() {
        val first = IntentRouter.route("改成顺序播放", context("queue"))
        assertEquals(first.steps, IntentRouter.route("改成顺序播放", first.nextContext).steps)
        val unresolved = IntentRouter.route("喜好列表里的第三首歌", context("list"))
        val action = unresolved.steps.single().action as Action.PlayFromCollection
        assertNull(action.collectionId)
        assertFalse(action.ready)
        assertEquals(Failure.CollectionUnavailable, unresolved.steps.single().failure)
        val interrupted = IntentRouter.route("先播放喜好列表里的第三首再改成顺序播放", context("loading"))
        assertEquals(1, interrupted.steps.size)
    }

    @Test fun boundedInputAndRoutingBenchmark() {
        assertEquals(Failure.TooLong, IntentRouter.route("歌".repeat(513)).steps.single().failure)
        val cases = matrix().readLines().drop(1).filter { it.isNotBlank() }.map { it.split('\t') }
        repeat(10) { cases.forEach { IntentRouter.route(it[2], context(it[3])) } }
        val nanos = ArrayList<Long>()
        repeat(10) { cases.forEach {
            val state = context(it[3])
            val start = System.nanoTime()
            IntentRouter.route(it[2], state)
            nanos += System.nanoTime() - start
        } }
        nanos.sort()
        val p95 = nanos[(nanos.size * .95).toInt()] / 1_000_000.0
        println("INTENT_BENCH samples=${nanos.size} warm=true p95_ms=$p95 excludes_asr_network_ui=true")
        assertTrue("JVM router P95 $p95 ms > 10 ms", p95 <= 10.0)
    }
}
