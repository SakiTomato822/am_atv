package dev.amatv.ui

import dev.amatv.integration.AutoplaySource

/** Stable semantic actions: a bootstrap-tail index must never address the full timeline. */
internal sealed interface StartupQueueAction {
    data object Next : StartupQueueAction
    data object Previous : StartupQueueAction
    data class Select(val entryId: Long) : StartupQueueAction
    data object ClearUpcoming : StartupQueueAction
    data object CycleRepeat : StartupQueueAction
    data object ToggleShuffle : StartupQueueAction
    data object ToggleInfinite : StartupQueueAction
    data object ToggleSource : StartupQueueAction
    data class SetSource(val source: AutoplaySource) : StartupQueueAction

    val changesTimeline: Boolean
        get() = this == Next || this == Previous || this is Select || this == ClearUpcoming
}

/** Input stays bounded even if a remote keeps repeating while storage is unavailable. */
internal class StartupQueueActions(private val capacity: Int = 128) {
    private val pending = ArrayDeque<StartupQueueAction>()

    fun enqueue(action: StartupQueueAction): Boolean {
        if (pending.size >= capacity) return false
        pending.addLast(action)
        return true
    }

    fun drain(): List<StartupQueueAction> = pending.toList().also { pending.clear() }

    fun preferencesForNewContext(): List<StartupQueueAction> = drain().filterNot { it.changesTimeline }

    /** Hydration replays only metadata, then resumes the latest explicit Play/Pause choice once. */
    fun replayHydrated(
        requestedPlayIntent: Boolean,
        setPlayIntent: (Boolean) -> Unit,
        apply: (StartupQueueAction) -> Boolean,
    ) {
        var lastSelectionAccepted: Boolean? = null
        setPlayIntent(false)
        try {
            drain().forEach { action ->
                val accepted = apply(action)
                if (action is StartupQueueAction.Select) lastSelectionAccepted = accepted
            }
        } finally {
            // A missing stable entry must not unexpectedly play another restored item.
            setPlayIntent(requestedPlayIntent && lastSelectionAccepted != false)
        }
    }
}

/** A reserve-prefetch error is not a playback failure unless this exact entry is awaiting Next. */
internal fun waitingRecommendationError(
    waitingEntryId: Long?,
    currentEntryId: Long?,
    awaitingNext: Boolean,
    errorMessage: String?,
): String? = errorMessage?.takeIf {
    waitingEntryId != null && waitingEntryId == currentEntryId && !awaitingNext
}
