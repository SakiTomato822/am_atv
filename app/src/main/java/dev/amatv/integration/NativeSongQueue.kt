package dev.amatv.integration

import kotlin.math.abs

/** Tracks slots seen on the current controller when MusicKit returns an empty queue list. */
internal class NativeSongQueue {
    private data class Item(val catalogId: String, val queueId: Long?)
    private val items = mutableMapOf<Int, Item>()

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
