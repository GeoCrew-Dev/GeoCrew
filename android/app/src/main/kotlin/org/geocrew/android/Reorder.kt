package org.geocrew.android

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Putting rows of a LazyColumn in a new order by dragging their handle (the
 * layer list, ADR 0017). The row follows the finger; a row it passes the middle
 * of makes way, sliding into its new place; held at the top or bottom edge for
 * a moment, the row scrolls the list, faster the longer it stays. Only the
 * screen's own order changes while dragging ([onMove]); [onDrop] saves it once.
 */
@Stable
class ReorderState internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback,
    private val edge: Float,
    private val slow: Float,
    private val fast: Float,
) {
    /** Moves row [key] to where row [to] is; called while dragging, for the screen's order only. */
    var onMove: (key: Any, to: Any) -> Unit = { _, _ -> }

    /** Whether row [key] may take the place of row [to] (rows beside each other, in one group). */
    var canMove: (key: Any, to: Any) -> Boolean = { _, _ -> false }

    /** The rows that go with row [key] (a group's open layers under it): passing their middle passes them all. */
    var blockOf: (key: Any) -> List<Any> = { listOf(it) }

    /** Row [key] was dropped where it now is. */
    var onDrop: (key: Any) -> Unit = {}

    /** The row being dragged. */
    var dragging by mutableStateOf<Any?>(null)
        private set

    // Where the dragged row's top is drawn, in the list's coordinates: under the finger, whatever its place in the list.
    private var top by mutableFloatStateOf(0f)

    // A dropped row sliding into its place.
    private var settling by mutableStateOf<Any?>(null)
    private val settle = Animatable(0f)
    private var edgeJob: Job? = null

    // The layout a move was made in: the next move waits for the list to show it.
    private var movedIn: LazyListLayoutInfo? = null

    /** How far row [key] is drawn from its place in the list. */
    fun offsetOf(key: Any): Float = when (key) {
        dragging -> info(key)?.let { top - it.offset } ?: 0f
        settling -> settle.value
        else -> 0f
    }

    /** Whether row [key] is above the others: dragged, or sliding into place. */
    fun lifted(key: Any): Boolean = key == dragging || key == settling

    private fun info(key: Any): LazyListItemInfo? = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    internal fun start(key: Any) {
        val item = info(key) ?: return
        settling = null
        dragging = key
        top = item.offset.toFloat()
        movedIn = null
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        edgeJob?.cancel()
        edgeJob = scope.launch { scrollAtEdges(key) }
    }

    internal fun drag(dy: Float) {
        val key = dragging ?: return
        val layout = listState.layoutInfo
        val size = info(key)?.size ?: 0
        // The row stays in the list's window; past the edge, the finger only holds it there.
        top = (top + dy).coerceIn(layout.viewportStartOffset.toFloat(), (layout.viewportEndOffset - size).toFloat().coerceAtLeast(0f))
        swap(key)
    }

    internal fun end() {
        val key = dragging ?: return
        val from = offsetOf(key)
        dragging = null
        edgeJob?.cancel()
        settling = key
        scope.launch {
            settle.snapTo(from)
            settle.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow))
            if (settling == key) settling = null
        }
        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
        onDrop(key)
    }

    // The furthest row the dragged one has passed the middle of (of its block), above or below, takes its place.
    private fun swap(key: Any) {
        val layout = listState.layoutInfo
        if (layout === movedIn) return
        val items = layout.visibleItemsInfo
        val me = items.firstOrNull { it.key == key } ?: return
        val bottom = top + me.size
        fun middle(item: LazyListItemInfo): Float? {
            val block = blockOf(item.key).mapNotNull { k -> items.firstOrNull { it.key == k } }
            if (block.isEmpty()) return null
            return (block.minOf { it.offset } + block.maxOf { it.offset + it.size }) / 2f
        }
        val candidates = items.filter { it.key != key && canMove(key, it.key) }
        val target = candidates.filter { it.index > me.index }.lastOrNull { middle(it)?.let { m -> bottom > m } == true }
            ?: candidates.filter { it.index < me.index }.firstOrNull { middle(it)?.let { m -> top < m } == true }
            ?: return
        // The list keeps its first row where it is; moving that row would scroll the list with it.
        val first = listState.firstVisibleItemIndex
        val firstOffset = listState.firstVisibleItemScrollOffset
        movedIn = layout
        onMove(key, target.key)
        if (me.index == first || target.index == first) listState.requestScrollToItem(first, firstOffset)
        haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
    }

    // Held at the top or bottom edge for HOLD_MS, the row scrolls the list: slowly at first, then faster.
    private suspend fun scrollAtEdges(key: Any) {
        var since = -1L
        var last = -1L
        while (dragging == key) {
            val now = withFrameMillis { it }
            val layout = listState.layoutInfo
            val slot = info(key)
            val size = slot?.size ?: 0
            // Never so far that the row's own place leaves the window: its row would go with it.
            val direction = when {
                slot == null -> 0f
                top < layout.viewportStartOffset + edge && listState.canScrollBackward && slot.offset + slot.size < layout.viewportEndOffset -> -1f
                top + size > layout.viewportEndOffset - edge && listState.canScrollForward && slot.offset > layout.viewportStartOffset -> 1f
                else -> 0f
            }
            if (direction == 0f) {
                since = -1L
            } else {
                if (since < 0) since = now
                val held = now - since
                if (held >= HOLD_MS && last >= 0) {
                    val ramp = ((held - HOLD_MS) / RAMP_MS.toFloat()).coerceIn(0f, 1f)
                    listState.scrollBy(direction * (slow + (fast - slow) * ramp) * (now - last) / 1000f)
                    swap(key)
                }
            }
            last = now
        }
    }

    companion object {
        /** How long the row has to stay at an edge before the list scrolls. */
        const val HOLD_MS = 400L

        /** How long the scrolling takes to reach full speed. */
        const val RAMP_MS = 1200L
    }
}

/** A [ReorderState] for the list in [listState]. */
@Composable
fun rememberReorderState(listState: LazyListState): ReorderState {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    return remember(listState) {
        with(density) { ReorderState(listState, scope, haptics, edge = 24.dp.toPx(), slow = 320.dp.toPx(), fast = 1600.dp.toPx()) }
    }
}

/** The ≡ that drags row [key]; a tap on it does nothing. */
@Composable
fun DragHandle(state: ReorderState, key: Any, onStart: () -> Unit = {}) {
    // A row that leaves the list mid-drag is dropped where it is.
    DisposableEffect(key, state) {
        onDispose { if (state.dragging == key) state.end() }
    }
    Box(
        Modifier
            .size(48.dp)
            .pointerInput(key) { detectTapGestures { } }
            .pointerInput(key, state) {
                detectDragGestures(
                    onDragStart = {
                        onStart()
                        state.start(key)
                    },
                    onDragEnd = state::end,
                    onDragCancel = state::end,
                    onDrag = { change, amount ->
                        change.consume()
                        state.drag(amount.y)
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.DragHandle, stringResource(R.string.layer_drag))
    }
}

/**
 * Row [key] of a list [state] reorders: it slides when it makes way, and when
 * dragged it rises above the others, raised and rounded, wherever the finger is.
 */
@Composable
fun LazyItemScope.reorderable(state: ReorderState, key: Any, modifier: Modifier = Modifier): Modifier {
    val lifted = state.lifted(key)
    // Raised while dragged; lowered again, colour and shadow fading, as it slides into place.
    val elevation by animateDpAsState(if (state.dragging == key) LIFT else 0.dp, label = "lift")
    val raised = (elevation / LIFT).coerceIn(0f, 1f)
    val color = MaterialTheme.colorScheme.surfaceContainerHigh
    val shape = RoundedCornerShape(12.dp)
    return modifier
        // The dragged row follows the finger and a dropped one slides by itself; the others glide.
        .animateItem(placementSpec = if (lifted) null else spring(stiffness = Spring.StiffnessMediumLow, visibilityThreshold = IntOffset.VisibilityThreshold))
        .zIndex(if (lifted) 1f else 0f)
        .graphicsLayer {
            translationY = state.offsetOf(key)
            shadowElevation = elevation.toPx()
            this.shape = shape
            clip = raised > 0f
        }
        .background(if (raised > 0f) color.copy(alpha = color.alpha * raised) else Color.Transparent)
}

private val LIFT = 6.dp
