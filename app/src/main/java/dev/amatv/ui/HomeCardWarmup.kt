package dev.amatv.ui

import kotlinx.coroutines.channels.Channel

/**
 * One feed-wide budget for expensive card content. Entries exist only while their
 * lazy card slots are attached; mounted and disposed cards leave no queued work.
 */
internal class HomeCardWarmup {
    internal class Ticket(
        val sequence: Long,
        var priority: (() -> Int)?,
        var mount: (() -> Unit)?,
    )

    private val pending = LinkedHashMap<Long, Ticket>()
    private val wakeup = Channel<Unit>(Channel.CONFLATED)
    private var nextSequence = 0L
    private var lastMountFrame = Long.MIN_VALUE
    private var closed = false

    val pendingCount: Int get() = pending.size

    fun request(priority: () -> Int, mount: () -> Unit): Ticket {
        val ticket = Ticket(nextSequence++, priority, mount)
        if (closed) {
            release(ticket)
        } else {
            pending[ticket.sequence] = ticket
            wakeup.trySend(Unit)
        }
        return ticket
    }

    fun cancel(ticket: Ticket) {
        val wasPending = pending.remove(ticket.sequence) != null
        release(ticket)
        if (wasPending) notifyPrioritiesChanged()
    }

    /** Visibility and focus can change without registering a new card. */
    fun notifyPrioritiesChanged() {
        if (!closed) wakeup.trySend(Unit)
    }

    suspend fun awaitPending(): Boolean {
        while (!closed && nextEligible() == null) {
            if (wakeup.receiveCatching().isClosed) return false
        }
        return !closed
    }

    /** Dynamic priority lets a new remote destination overtake visible siblings. */
    fun mountNext(frameNanos: Long): Boolean {
        if (closed || frameNanos == lastMountFrame) return false
        val ticket = nextEligible() ?: return false
        pending.remove(ticket.sequence)
        val mount = ticket.mount
        release(ticket)
        if (mount == null) return false
        lastMountFrame = frameNanos
        mount()
        return true
    }

    private fun nextEligible(): Ticket? {
        var best: Ticket? = null
        var bestPriority = Int.MAX_VALUE
        for (ticket in pending.values) {
            val priority = ticket.priority?.invoke() ?: continue
            // An inactive prefetched composition may not consume its permit
            // until a later measure. Do not accumulate ready offscreen cards.
            if (priority <= 1 && priority < bestPriority) {
                best = ticket
                bestPriority = priority
            }
        }
        return best
    }

    fun close() {
        closed = true
        pending.values.forEach(::release)
        pending.clear()
        wakeup.close()
    }

    private fun release(ticket: Ticket) {
        ticket.priority = null
        ticket.mount = null
    }
}
