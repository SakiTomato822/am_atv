package dev.amatv.integration

internal enum class GaplessPath(val label: String) {
    Native("native"), Fallback("fallback"), Manual("manual"),
}

internal enum class GaplessStage(val label: String) {
    Selected("selected"), Resolved("resolved"), Append("append"), Added("onPlaybackQueueItemsAdded"),
    Ended("ended"), Requested("requested"), Rebuild("rebuild"), Rebuilt("rebuilt"),
    Prepare("prepare"), Play("play"), ItemChanged("onCurrentItemChanged"),
    BufferStart("bufferStart"), BufferEnd("bufferEnd"), Advanced("firstAdvance"),
    Retry("retry"),
}

internal data class GaplessEnd(
    val generation: Long, val catalogId: String?, val atMs: Long, val source: String, val reason: String? = null,
)

/** One diagnostic record, passed explicitly through the app and SDK requests. */
class GaplessTransition internal constructor(
    internal val sourceGeneration: Long,
    internal val entryId: Long?,
    private val id: String,
    selectedAtMs: Long,
) {
    private val times = mutableMapOf(GaplessStage.Selected to selectedAtMs)
    private var path = GaplessPath.Manual
    private var end: GaplessEnd? = null
    private var fadeOut: String? = null
    private var fadeIn: String? = null

    @Synchronized
    internal fun mark(stage: GaplessStage, atMs: Long) {
        times.putIfAbsent(stage, atMs)
    }

    @Synchronized
    internal fun buffering(buffering: Boolean, atMs: Long) {
        if (buffering) mark(GaplessStage.BufferStart, atMs)
        else if (GaplessStage.BufferStart in times) mark(GaplessStage.BufferEnd, atMs)
    }

    @Synchronized
    internal fun has(stage: GaplessStage): Boolean = stage in times

    @Synchronized
    internal fun timeOf(stage: GaplessStage): Long? = times[stage]

    @Synchronized
    internal fun matchesPreviousEnd(generation: Long, catalogId: String?): Boolean =
        path == GaplessPath.Native && end?.generation == generation && end?.catalogId == catalogId

    @Synchronized
    internal fun ended(event: GaplessEnd) {
        if (end == null || end?.source != "onItemEnded") {
            end = event
            times[GaplessStage.Ended] = event.atMs
        }
    }

    @Synchronized
    internal fun start(path: GaplessPath, atMs: Long, end: GaplessEnd? = null) {
        this.path = path
        mark(GaplessStage.Requested, atMs)
        end?.let(::ended)
    }

    @Synchronized
    internal fun fade(outgoing: Boolean, status: String) {
        if (outgoing) fadeOut = status else fadeIn = status
    }

    @Synchronized
    internal fun finish(
        atMs: Long, catalogId: String, route: String, session: Int, fade: String, loudnessDb: Float = 0f,
    ): String {
        mark(GaplessStage.Advanced, atMs)
        val timing = summarizeGaplessTiming(path, times)
        return "id=$id path=${path.label} catalog=$catalogId endSource=${end?.source ?: "-"} " +
            "reason=${end?.reason ?: "-"} $timing route=$route session=${session.takeIf { it > 0 } ?: "unknown"} " +
            "fade=$fade fadeOut=${fadeOut ?: "-"} fadeIn=${fadeIn ?: "-"} loudnessDb=$loudnessDb"
    }
}

/** All timestamps use the same monotonic clock. Missing stages stay missing. */
internal fun summarizeGaplessTiming(path: GaplessPath, times: Map<GaplessStage, Long>): String {
    fun duration(from: GaplessStage, to: GaplessStage): String {
        val start = times[from] ?: return "-"
        val end = times[to] ?: return "-"
        return (end - start).coerceAtLeast(0).toString()
    }

    val selected = times.getValue(GaplessStage.Selected)
    val gapStart = if (path == GaplessPath.Manual) GaplessStage.Requested else GaplessStage.Ended
    return buildString {
        append("gapMs=${duration(gapStart, GaplessStage.Advanced)} ")
        append("resolveMs=${duration(GaplessStage.Selected, GaplessStage.Resolved)} ")
        append("appendMs=${duration(GaplessStage.Append, GaplessStage.Added)} ")
        append("rebuildMs=${duration(GaplessStage.Rebuild, GaplessStage.Rebuilt)} ")
        append("bufferMs=${duration(GaplessStage.BufferStart, GaplessStage.BufferEnd)} ")
        append("endToRequestMs=${duration(GaplessStage.Ended, GaplessStage.Requested)} ")
        append("requestToPrepareMs=${duration(GaplessStage.Requested, GaplessStage.Prepare)} ")
        append("prepareToPlayMs=${duration(GaplessStage.Prepare, GaplessStage.Play)} ")
        append("playToAdvanceMs=${duration(GaplessStage.Play, GaplessStage.Advanced)} ")
        append("selectedElapsedMs=$selected offsetsMs[")
        append(GaplessStage.entries.joinToString(" ") { stage ->
            "${stage.label}=${times[stage]?.minus(selected) ?: "-"}"
        })
        append(']')
    }
}
