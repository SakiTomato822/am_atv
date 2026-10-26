package dev.amatv.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

internal data class ShelfFocusPosition(val shelf: Int, val item: Int)

/** Bounds use the row viewport's screen-relative x coordinates, independent of item index. */
internal data class ShelfCardBounds(val index: Int, val left: Float, val width: Float) {
    val centerX: Float get() = left + width / 2f
    val right: Float get() = left + width
}
internal data class ShelfRowBounds(val viewportWidth: Float, val cards: List<ShelfCardBounds>)

/** Stay inside the destination row's existing viewport; never reveal an offscreen matching index. */
internal fun nearestVisibleShelfCard(anchorX: Float?, row: ShelfRowBounds): Int {
    if (!row.viewportWidth.isFinite() || row.viewportWidth <= 0f) return 0
    val visible = row.cards.filter {
        it.left.isFinite() && it.width.isFinite() && it.width > 0f && it.right > 0f && it.left < row.viewportWidth
    }
    val fullyVisible = visible.filter { it.left >= 0f && it.right <= row.viewportWidth }
    val candidates = fullyVisible.ifEmpty { visible }
    val anchor = anchorX?.takeIf(Float::isFinite)
    return if (anchor == null) candidates.minByOrNull { it.left }?.index ?: 0
        else candidates.minWithOrNull(compareBy<ShelfCardBounds> { abs(it.centerX - anchor) }.thenBy { it.index })?.index ?: 0
}

/** A lazy row retains its scroll cursor even before its next composition has measured children. */
internal fun estimateShelfRowBounds(
    itemCount: Int,
    firstVisibleIndex: Int,
    firstVisibleScrollOffset: Int,
    cardWidth: Float,
    spacing: Float,
    beforePadding: Float,
    viewportWidth: Float,
): ShelfRowBounds {
    val stride = cardWidth + spacing
    if (itemCount <= 0 || !viewportWidth.isFinite() || viewportWidth <= 0f ||
        !cardWidth.isFinite() || cardWidth <= 0f || !stride.isFinite() || stride <= 0f
    ) return ShelfRowBounds(viewportWidth, emptyList())
    val first = firstVisibleIndex.coerceIn(0, itemCount - 1)
    val left = beforePadding - firstVisibleScrollOffset.coerceAtLeast(0)
    val start = (first + floor((-cardWidth - left) / stride).toInt()).coerceAtLeast(0)
    val end = (first + ceil((viewportWidth - left) / stride).toInt()).coerceAtMost(itemCount - 1)
    return ShelfRowBounds(viewportWidth, if (end < start) emptyList() else (start..end).map { index ->
        ShelfCardBounds(index, left + (index - first) * stride, cardWidth)
    })
}

/** A unique token lets a cancelled scroll finish without clearing a newer request. */
internal class ShelfFocusRequest(position: ShelfFocusPosition, val anchorX: Float?) {
    var position = position
        internal set
}

/** Rapid vertical input carries the original screen x while destination rows are being revealed. */
internal class ShelfNavigationCursor(private var shelfSizes: List<Int>) {
    var focused = ShelfFocusPosition(0, 0)
        private set
    private var pending: ShelfFocusRequest? = null

    val requestedPosition: ShelfFocusPosition
        get() = pending?.position ?: focused

    /** Appending a library page must not reset the user's existing row/focus cursor. */
    fun updateShelfSizes(sizes: List<Int>) {
        shelfSizes = sizes
        fun ShelfFocusPosition.clamped(): ShelfFocusPosition {
            if (sizes.isEmpty()) return ShelfFocusPosition(0, 0)
            val row = shelf.coerceIn(sizes.indices)
            return ShelfFocusPosition(row, item.coerceIn(0, (sizes[row] - 1).coerceAtLeast(0)))
        }
        focused = focused.clamped()
        pending?.let { it.position = it.position.clamped() }
    }

    fun onFocused(shelf: Int, item: Int) {
        focused = ShelfFocusPosition(shelf, item)
        // Keep the anchor until the scroll job completes. A second key during
        // that job must not drift to the incoming row's different card centers.
    }

    fun request(
        direction: Int,
        sourceCenterX: Float? = null,
        targetItem: (shelf: Int, anchorX: Float?) -> Int = { _, _ -> 0 },
    ): ShelfFocusRequest? {
        require(direction == -1 || direction == 1)
        val from = requestedPosition
        var shelf = from.shelf + direction
        while (shelf in shelfSizes.indices && shelfSizes[shelf] <= 0) shelf += direction
        if (shelf !in shelfSizes.indices) return null
        val anchor = pending?.anchorX ?: sourceCenterX?.takeIf(Float::isFinite)
        val item = targetItem(shelf, anchor).coerceIn(0, shelfSizes[shelf] - 1)
        return ShelfFocusRequest(ShelfFocusPosition(shelf, item), anchor).also { pending = it }
    }

    fun refine(request: ShelfFocusRequest, item: Int): Boolean {
        if (pending !== request) return false
        val shelf = request.position.shelf
        request.position = ShelfFocusPosition(shelf, item.coerceIn(0, shelfSizes[shelf] - 1))
        return true
    }

    fun isCurrent(request: ShelfFocusRequest): Boolean = pending === request

    fun complete(request: ShelfFocusRequest) {
        if (pending === request) pending = null
    }

    fun cancel() { pending = null }
}
