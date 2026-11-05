package dev.amatv.ui

import android.view.View
import androidx.compose.ui.node.RootForTest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.amatv.integration.voice.ScreenTarget

/**
 * What the app shows right now, read from Compose semantics (the same tree accessibility uses):
 * every labelled clickable control and every scrollable list, with window bounds. Voice uses it
 * to press "资料库" or scroll "热门歌曲" like a finger would. Only this app's own window.
 */
internal class VoiceScreen(private val view: View) {
    private var nodes: List<SemanticsNode> = emptyList()

    fun read(): List<ScreenTarget> {
        val root = (view as? RootForTest)?.semanticsOwner?.rootSemanticsNode ?: return emptyList()
        val width = view.width.toFloat()
        val height = view.height.toFloat()
        val found = mutableListOf<SemanticsNode>()
        fun visit(node: SemanticsNode) {
            val b = node.boundsInWindow
            val onScreen = b.width > 1f && b.height > 1f && b.right > 0f && b.bottom > 0f && b.left < width && b.top < height
            if (onScreen) found += node
            node.children.forEach(::visit)
        }
        visit(root)
        nodes = found
        return found.mapIndexed { index, node ->
            val config = node.config
            val label = buildList {
                config.getOrNull(SemanticsProperties.Text)?.forEach { add(it.text) }
                config.getOrNull(SemanticsProperties.ContentDescription)?.forEach { add(it) }
            }.joinToString(" ").trim()
            val canScroll = config.getOrNull(SemanticsActions.ScrollBy) != null
            val b = node.boundsInWindow
            ScreenTarget(
                label = label,
                left = b.left, top = b.top, right = b.right, bottom = b.bottom,
                clickable = config.getOrNull(SemanticsActions.OnClick) != null,
                scrollsHorizontally = canScroll && config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null,
                scrollsVertically = canScroll && config.getOrNull(SemanticsProperties.VerticalScrollAxisRange) != null,
                id = index,
            )
        }
    }

    fun click(target: ScreenTarget): Boolean =
        nodes.getOrNull(target.id)?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() == true

    /** Scrolls [target] by most of its own size toward ([dx], [dy]) in -1..1. */
    fun scroll(target: ScreenTarget, dx: Float, dy: Float): Boolean {
        val node = nodes.getOrNull(target.id) ?: return false
        val b = node.boundsInWindow
        return node.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(dx * b.width * 0.8f, dy * b.height * 0.8f) == true
    }
}
