package app.wanki.ui.notebooks

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize

/** How a page drag in the grid ends. */
internal sealed interface PageDragEnd {
    /** The finger lifted with the page at a new place: store the move. */
    data class Move(val from: Int, val to: Int) : PageDragEnd
    /** The finger lifted where it started: nothing to store. */
    data object Stay : PageDragEnd
    /** The system cancelled the gesture (ACTION_CANCEL): store nothing and show the stored order again. */
    data object Restore : PageDragEnd
}

/**
 * One drag of the page that was at [from]. The reorderable handle reports a lifted finger and a cancelled gesture
 * the same way (`onDragStopped`), so the grid tells them apart itself: [lift] comes from [onPointersLifted], which
 * sees the real release before the handle does; a cancelled gesture sends no release.
 */
internal class PageDrag(val from: Int) {
    var lifted = false
        private set

    fun lift() { lifted = true }

    /** The drag stopped with the page now at [to] (−1 when it is gone from the list). */
    fun end(to: Int): PageDragEnd = when {
        !lifted -> PageDragEnd.Restore
        to < 0 || to == from -> PageDragEnd.Stay
        else -> PageDragEnd.Move(from, to)
    }
}

/**
 * Calls [onLift] when the last pointer of a gesture lifts, before any other handler sees the release (initial pass).
 * Does nothing on a cancelled gesture: Compose turns ACTION_CANCEL into [PointerInputModifierNode.onCancelPointerInput],
 * not into a release event. Only watches: nothing is consumed.
 */
internal fun Modifier.onPointersLifted(onLift: () -> Unit): Modifier = this then LiftElement(onLift)

private data class LiftElement(val onLift: () -> Unit) : ModifierNodeElement<LiftNode>() {
    override fun create() = LiftNode(onLift)
    override fun update(node: LiftNode) { node.onLift = onLift }
    override fun InspectorInfo.inspectableProperties() { name = "onPointersLifted" }
}

private class LiftNode(var onLift: () -> Unit) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass == PointerEventPass.Initial && pointerEvent.type == PointerEventType.Release &&
            pointerEvent.changes.none { it.pressed }
        ) onLift()
    }

    override fun onCancelPointerInput() {}
}
