package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Material 3-styled vertical scrollbar for a [LazyListState].
 *
 * Layout: the bar lives in its own column on the right edge — pair it
 * with `LazyColumn(modifier = …padding(end = width))` so content doesn't
 * slide under the thumb. The bar is hidden entirely when the content
 * fits the viewport (no scroll possible).
 *
 * Geometry comes from [LazyListState]:
 * - `avgItemPx` (mean of visible items' heights) estimates per-item size.
 * - scroll position = `avgItemPx * firstVisibleItemIndex + firstVisibleItemScrollOffset`
 *   (the same formula `LazyListState.calculateScrollOffset` uses internally
 *   for `ScrollIndicatorState`).
 * - Note: `info.viewportStartOffset` is *not* the scroll position in Foundation
 *   1.7.x — it is hard-coded to `-beforeContentPadding` and only changes when
 *   padding does. Use `firstVisibleItemIndex` / `firstVisibleItemScrollOffset`
 *   instead, both of which are snapshot-backed (`scrollPosition.index` and
 *   `scrollPosition.scrollOffset` are `mutableIntStateOf` delegates), so the
 *   thumb tracks every scroll tick.
 * - Thumb position = `scrollPx / (totalSize − viewportSize)`.
 *
 * Interaction:
 * - Tap the track → `scrollToItem` to jump so the thumb lands on the tap.
 * - Drag the thumb → 1:1 thumb motion; the list scrolls via `dispatchRawDelta`
 *   on every pointer event (light path; no full remeasure of the LazyColumn
 *   between ticks, no coroutine mutual-exclusion queue piling up).
 *
 * ponytail: Compose Foundation's `rememberScrollbarState` /
 * `Modifier.verticalScrollbar` API didn't ship in our Foundation 1.7.2;
 * the next-best standard option is `androidx.compose.material` (M1) which
 * we deliberately don't pull in. So the scrollbar lives here.
 */
@Composable
fun VerticalListScrollbar(
    listState: LazyListState,
    modifier: Modifier = Modifier,
    width: Dp = 8.dp,
    touchWidth: Dp = 40.dp,
) {
    val density = LocalDensity.current
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)

    // snapshotFlow listens to every snapshot apply against listState. This is
    // the most reliable trigger — deriveStateOf wouldn't fire on plain
    // Int property updates because Compose can't track them.
    val metricsFlow = remember(listState) {
        snapshotFlow { listState.scrollMetrics() }.distinctUntilChanged()
    }
    val metrics by metricsFlow.collectAsState(initial = listState.scrollMetrics())

    // If the content fits the viewport there's nothing to scroll.
    if (!metrics.hasOverflow) return

    var trackHeightPx by remember { mutableIntStateOf(0) }
    val minThumbPx = with(density) { 24.dp.toPx().toInt() }
    val thumbHeightPx = (metrics.thumbFraction * trackHeightPx)
        .toInt()
        .coerceAtLeast(minThumbPx)
    val scrollableTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1)
    val thumbOffsetPx = (metrics.scrollFraction * scrollableTrackPx).toInt()
        .coerceIn(0, scrollableTrackPx)

    // Visual thumb position. We use a concrete `.offset(y = …)` (not the
    // lambda overload `.offset { IntOffset(...) }`) because Compose Foundation
    // 1.7.x's deferred-state-read offset modifier does not reliably subscribe
    // to the state — the lambda re-runs on layout but the layout phase doesn't
    // always pick up fresh state writes that happened inside a pointer event
    // callback, so the thumb visually stayed put during drags. Reading the
    // State<T> directly in the composable body registers a normal recomposition
    // subscription, which the snapshot system drives correctly on every
    // intValue = … write (recomposition + relayout + redraw in the same frame).
    //
    // LaunchedEffect re-syncs from the metrics-driven [thumbOffsetPx] *only*
    // when not dragging, for the same reason as v0.5.6: scrollFraction lags
    // behind dispatchRawDelta and is approximate (averages item heights), so
    // letting it overwrite the in-progress visual position during a drag makes
    // the thumb snap back to a stale spot.
    val thumbOffsetPxState = remember { mutableIntStateOf(thumbOffsetPx) }
    var isDragging by remember { mutableStateOf(false) }
    LaunchedEffect(thumbOffsetPx, isDragging) {
        if (!isDragging) {
            thumbOffsetPxState.intValue = thumbOffsetPx
        }
    }
    // The actual modifier offset, recomposed when the State changes:
    val thumbOffsetY = with(density) { thumbOffsetPxState.intValue.toDp() }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .onSizeChanged { trackHeightPx = it.height }
            .background(trackColor, RoundedCornerShape(width / 2))
            .pointerInput(listState, metrics.thumbFraction, thumbHeightPx, scrollableTrackPx) {
                // Tap on the track (outside the thumb) jumps the list.
                // Re-keyed on the geometry inputs so the captured
                // [metrics] / [scrollableTrackPx] / [thumbHeightPx] inside
                // the lambda are always current — the pointerInput block
                // re-runs when any of them change.
                detectTapGestures { offset ->
                    val ratio = ((offset.y - thumbHeightPx / 2f) / scrollableTrackPx)
                        .coerceIn(0f, 1f)
                    val totalScrollable = (metrics.totalScrollPx - metrics.viewportPx)
                        .coerceAtLeast(0f)
                    val targetScrollPx = ratio * totalScrollable
                    val info = listState.layoutInfo
                    val avgItemPx = metrics.avgItemPx
                    val newIndex = (targetScrollPx / avgItemPx).toInt()
                        .coerceIn(0, (info.totalItemsCount - 1).coerceAtLeast(0))
                    val targetItem = info.visibleItemsInfo
                        .firstOrNull { it.index == newIndex }
                        ?: info.visibleItemsInfo.firstOrNull()
                    val itemSize = targetItem?.size ?: avgItemPx.toInt()
                    val maxOffset = (itemSize - 1).coerceAtLeast(0)
                    val offsetInItem = (targetScrollPx - newIndex * avgItemPx)
                        .toInt()
                        .coerceIn(0, maxOffset)
                    // requestScrollToItem is the non-suspend entry: it updates
                    // the scroll position and schedules a remeasure for the
                    // next frame. Perfect for a one-shot tap; the drag path
                    // below uses dispatchRawDelta instead because it has to
                    // scroll on every single pointer event without remeasure.
                    requestScrollToItemIndex(listState, newIndex, offsetInItem)
                }
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        Box(
            modifier = Modifier
                .width(touchWidth)
                .align(Alignment.TopEnd)
                .offset(y = thumbOffsetY)
                .height(with(density) { thumbHeightPx.toDp() })
                .pointerInput(listState) {
                    var anchor: DragAnchor? = null
                    detectDragGestures(
                        onDragStart = { offset ->
                            // touchYAtStart = thumb top + finger-to-thumb-top,
                            // in track coordinates.
                            anchor = DragAnchor(
                                touchYAtStart = thumbOffsetPxState.intValue + offset.y,
                                thumbOffsetAtStart = thumbOffsetPxState.intValue,
                            )
                            isDragging = true
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            val a = anchor ?: return@detectDragGestures
                            // newTouchY in track coords = grab point + accumulated delta.
                            // dragAmount is the finger's delta in this Box's local
                            // coords; the thumb's local origin doesn't move with the
                            // thumb (it's at the Box's top-left, which only moves
                            // when we move the offset modifier — and we move it
                            // synchronously to follow the finger below), so
                            // dragAmount == finger delta in track coords for the
                            // duration of the drag.
                            val newTouchY = a.touchYAtStart + dragAmount.y
                            val newOffset = (a.thumbOffsetAtStart +
                                (newTouchY - a.touchYAtStart)).toInt()
                                .coerceIn(0, scrollableTrackPx)

                            // 1) Visual update — synchronous, on the same frame
                            // as the pointer event (Modifier.offset { ... } is a
                            // deferred state read; no round-trip through full
                            // recomposition needed).
                            thumbOffsetPxState.intValue = newOffset

                            // 2) List scroll — use dispatchRawDelta (the same
                            // path LazyColumn takes when the user drags on the
                            // content itself: copyWithScrollDeltaWithoutRemeasure
                            // + re-placement, no full remeasure, no coroutine
                            // mutual-exclusion queue). This is what was making
                            // the previous version feel like "the thumb chases
                            // the finger": we were launching a new
                            // scope.launch { scrollToItem(...) } per pointer tick,
                            // and ScrollableState.scroll { } queues them via
                            // mutual exclusion, so by the time the user released
                            // a queue of stale scrolls was still catching up.
                            val info = listState.layoutInfo
                            val visible = info.visibleItemsInfo
                            val viewportPx = (info.viewportEndOffset -
                                info.viewportStartOffset -
                                info.beforeContentPadding -
                                info.afterContentPadding).toFloat()
                            val avgPx = if (visible.isEmpty()) 1f else
                                visible.sumOf { it.size }.toFloat() / visible.size
                            val totalPx = avgPx * info.totalItemsCount
                            val scrollablePx = (totalPx - viewportPx).coerceAtLeast(0f)
                            val pxPerTrackPx = scrollablePx / scrollableTrackPx
                            if (pxPerTrackPx > 0f) {
                                listState.dispatchRawDelta(dragAmount.y * pxPerTrackPx)
                            }
                        },
                        onDragEnd = {
                            anchor = null
                            isDragging = false
                        },
                        onDragCancel = {
                            anchor = null
                            isDragging = false
                        },
                    )
                },
        ) {
            // Visual thumb: the same thin rectangle as v0.5.x. Stays [width]
            // wide and fills the touch-Box vertically so the rounded shape
            // matches the touch target's bounds. The touch-Box itself stays
            // transparent — only the inner Box paints, keeping the visual a
            // slim 8dp indicator while the user gets a 24dp hit-test lane.
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(width)
                    .fillMaxHeight()
                    .background(
                        if (isDragging) MaterialTheme.colorScheme.primary else thumbColor,
                        RoundedCornerShape(width / 2),
                    ),
            )
        }
    }
}

private data class DragAnchor(
    val touchYAtStart: Float,
    val thumbOffsetAtStart: Int,
)

/**
 * Non-suspend wrapper around [LazyListState.requestScrollToItem] so we can
 * call it from a tap-gesture lambda (which isn't a coroutine body). Mirrors
 * the public API minus the `IntRange` annotation, which Compose's KSP
 * generates and we don't need here.
 */
private fun requestScrollToItemIndex(
    listState: LazyListState,
    index: Int,
    scrollOffset: Int,
) {
    listState.requestScrollToItem(index, scrollOffset)
}

/** Cached scroll geometry; recomputed only when the list mutates or scrolls. */
private fun LazyListState.scrollMetrics(): ScrollMetrics {
    val info = layoutInfo
    val visible = info.visibleItemsInfo
    return computeScrollMetrics(
        firstVisibleItemIndex = firstVisibleItemIndex,
        firstVisibleItemScrollOffset = firstVisibleItemScrollOffset,
        visibleItemSizes = visible.map { it.size },
        totalItemsCount = info.totalItemsCount,
        // viewportEndOffset - viewportStartOffset = itemsViewport + beforeContentPadding
        // + afterContentPadding (see LazyListMeasureResult). Subtract both paddings
        // so thumbFraction/scale use the actual items area.
        // ponytail: tight to Foundation 1.7.x; revisit if viewportStartOffset stops
        // being `-beforeContentPadding`.
        viewportPx = (info.viewportEndOffset - info.viewportStartOffset -
            info.beforeContentPadding - info.afterContentPadding).toFloat(),
    )
}

/** Pure math; the [LazyListState] bridge above just feeds it real measurements. */
internal fun computeScrollMetrics(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    visibleItemSizes: List<Int>,
    totalItemsCount: Int,
    viewportPx: Float,
): ScrollMetrics {
    if (totalItemsCount == 0 || visibleItemSizes.isEmpty() || viewportPx <= 0f) {
        return ScrollMetrics(
            scrollFraction = 0f,
            thumbFraction = 1f,
            avgItemPx = 1f,
            totalScrollPx = 0f,
            viewportPx = viewportPx,
            hasOverflow = false,
        )
    }
    val visibleSum = visibleItemSizes.sum()
    val avgItemPx = visibleSum.toFloat() / visibleItemSizes.size
    val totalScrollPx = avgItemPx * totalItemsCount
    // scrollPx = avgItemPx * firstVisibleItemIndex + firstVisibleItemScrollOffset
    // — same formula LazyListState.calculateScrollOffset() uses for its
    // ScrollIndicatorState. viewportStartOffset in Foundation 1.7.x is fixed to
    // -beforeContentPadding (it's the items viewport's content-space start, not
    // the scroll position), so reading it here made scrollFraction stay at 0
    // and the thumb freeze at the top regardless of scroll.
    val scrollPx = avgItemPx * firstVisibleItemIndex + firstVisibleItemScrollOffset
    val hasOverflow = totalScrollPx > viewportPx
    val fraction = if (hasOverflow) {
        (scrollPx / (totalScrollPx - viewportPx)).coerceIn(0f, 1f)
    } else 0f
    // Thumb takes at least 8% of the track so it's grabbable on long lists.
    val thumbFraction = (viewportPx / totalScrollPx).coerceIn(0.08f, 1f)
    return ScrollMetrics(
        scrollFraction = fraction,
        thumbFraction = thumbFraction,
        avgItemPx = avgItemPx,
        totalScrollPx = totalScrollPx,
        viewportPx = viewportPx,
        hasOverflow = hasOverflow,
    )
}

internal data class ScrollMetrics(
    val scrollFraction: Float,
    val thumbFraction: Float,
    val avgItemPx: Float,
    val totalScrollPx: Float,
    val viewportPx: Float,
    val hasOverflow: Boolean,
)