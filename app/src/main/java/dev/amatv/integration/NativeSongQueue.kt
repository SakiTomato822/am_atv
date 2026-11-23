package dev.amatv.integration

import kotlin.math.abs

/** Tracks slots seen on the current controller when MusicKit returns an empty queue list. */
internal class NativeSongQueue {
    data class Append(
        val generation: Long, val index: Int, val catalogId: String, val timing: GaplessTransition?,
    ) {
        var callbackReceived = false
            private set

        fun acknowledge(generation: Long, count: Int): Boolean {
            if (this.generation != generation || count != 1 || callbackReceived) return false
            callbackReceived = true
            return true
        }

        fun isVisible(generation: Long, currentIndex: Int, count: Int, currentCatalogId: String?): Boolean =
            this.generation == generation && callbackReceived && count == index + 1 &&
                (currentIndex == index - 1 || (currentIndex == index && currentCatalogId == catalogId))

        fun matchesPlan(catalogId: String?, entryId: Long?): Boolean =
            this.catalogId == catalogId && timing?.entryId == entryId
    }

    private data class Item(val catalogId: String, val queueId: Long?)
    private val items = mutableMapOf<Int, Item>()
    var activeIndex: Int? = null
    var pendingAppend: Append? = null
        private set

    fun hasAdvanced(index: Int?): Boolean =
        index != null && activeIndex?.let { index > it } == true

    fun beginAppend(generation: Long, index: Int, catalogId: String, timing: GaplessTransition?): Append? {
        if (pendingAppend != null || activeIndex != index) return null
        return Append(generation, index + 1, catalogId, timing).also { pendingAppend = it }
    }

    fun clearAppend() {
        pendingAppend = null
    }

    fun confirmAppend(generation: Long, index: Int, count: Int, catalogId: String?): Append? {
        val append = pendingAppend ?: return null
        if (!append.isVisible(generation, index, count, catalogId)) return null
        clearAppend()
        record(append.index, append.catalogId, null)
        return append
    }

    sealed interface Command {
        val index: Int

        data class Restart(override val index: Int) : Command
        data class Next(override val index: Int) : Command
        data class ById(override val index: Int, val queueId: Long) : Command
    }

    fun record(index: Int, catalogId: String, queueId: Long?) {
        items[index] = Item(catalogId, queueId)
    }

    fun forgetFrom(index: Int) {
        items.keys.removeAll { it >= index }
    }

    fun matches(index: Int, catalogId: String?) = items[index]?.catalogId == catalogId && catalogId != null

    fun command(
        catalogId: String,
        currentIndex: Int,
        count: Int,
        canSeek: Boolean,
        canSkipNext: Boolean,
        canSkipById: Boolean,
    ): Command? {
        if (matches(currentIndex, catalogId) && canSeek) return Command.Restart(currentIndex)
        val byId = if (canSkipById) items.entries.asSequence()
            .filter { (index, item) -> index in 0 until count && index != currentIndex && item.catalogId == catalogId }
            .sortedBy { (index, _) -> abs(index - currentIndex) }
            .mapNotNull { (index, item) -> item.queueId?.let { Command.ById(index, it) } }
            .firstOrNull() else null
        if (byId != null) return byId
        val nextIndex = currentIndex + 1
        if (canSkipNext && nextIndex < count && matches(nextIndex, catalogId)) return Command.Next(nextIndex)
        return null
    }
}
