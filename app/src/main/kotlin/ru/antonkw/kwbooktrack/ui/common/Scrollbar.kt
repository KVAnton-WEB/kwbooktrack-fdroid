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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Material 3-styled vertical scrollbar for a [LazyListState].
 *
 * Layout: the bar occupies [touchWidth] on the right edge — pair it with
 * `LazyColumn(modifier = …padding(end = touchWidth))` so content doesn't
 * sit under the touch lane (the bar is the later sibling in the Box, so it
 * wins hit-test in that strip and the content there would be untappable).
 * Only the inner [width]-wide rectangle is painted, so the visual stays
 * slim while the finger gets a full [touchWidth] lane. The bar is hidden
 * entirely when the content fits the viewport (no scroll possible).
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
 * - Tap the track (not on the thumb) → `requestScrollToItem` to jump so the
 *   thumb lands on the tap. A tap *on* the thumb is ignored — that region
 *   belongs to the drag handler, and jumping there would shift the list
 *   under a stationary finger.
 * - Drag the thumb → 1:1 thumb motion from wherever it was grabbed; the list
 *   scrolls via `dispatchRawDelta` on every pointer event (light path; no
 *   full remeasure of the LazyColumn between ticks, no coroutine
 *   mutual-exclusion queue piling up).
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
    touchWidth: Dp = 24.dp,
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
    // write (recomposition + relayout + redraw in the same frame).
    //
    // While not dragging the offset comes straight from the metrics, so the
    // thumb tracks the list. During a drag the finger-driven offset wins:
    // scrollFraction lags behind dispatchRawDelta and is approximate (it
    // averages item heights), so letting it overwrite the in-progress visual
    // position makes the thumb snap back to a stale spot.
    //
    // ponytail: a null dragOffsetPx means "follow the list". The previous
    // version re-synced via `LaunchedEffect(thumbOffsetPx, isDragging)` — but
    // thumbOffsetPx changes on every frame, so that cancelled and relaunched a
    // coroutine 60-120x/second purely to assign one int. Plain composition
    // gives the same result for free.
    var dragOffsetPx by remember { mutableStateOf<Int?>(null) }
    val effectiveOffsetPx = dragOffsetPx ?: thumbOffsetPx
    var isDragging by remember { mutableStateOf(false) }
    val thumbOffsetY = with(density) { effectiveOffsetPx.toDp() }

    // Geometry read from inside the gesture lambdas. Keying `pointerInput` on
    // these is what used to restart the gesture detectors: avgItemPx is the mean
    // height of the *visible* items, so crossing one item boundary moves
    // thumbFraction → thumbHeightPx → scrollableTrackPx, and the tap detector
    // was torn down and rebuilt dozens of times per fling. Keyed on
    // rememberUpdatedState instead, so the detectors are built once.
    val geo by rememberUpdatedState(
        ScrollbarGeo(
            metrics = metrics,
            thumbHeightPx = thumbHeightPx,
            scrollableTrackPx = scrollableTrackPx,
            offsetPx = effectiveOffsetPx,
        ),
    )

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(touchWidth)
            .onSizeChanged { trackHeightPx = it.height }
            .pointerInput(listState) {
                // Tap on the track (outside the thumb) jumps the list. All the
                // geometry is read through [geo] so the block below never has
                // to be re-keyed.
                detectTapGestures { offset ->
                    // The drag handler owns the thumb's lane (24dp, inside this
                    // 24dp-wide box); a tap there is a grab, not a jump.
                    val thumbTop = geo.offsetPx.toFloat()
                    if (offset.y in thumbTop..(thumbTop + geo.thumbHeightPx)) {
                        return@detectTapGestures
                    }
                    val ratio = ((offset.y - geo.thumbHeightPx / 2f) / geo.scrollableTrackPx)
                        .coerceIn(0f, 1f)
                    val totalScrollable = (geo.metrics.totalScrollPx - geo.metrics.viewportPx)
                        .coerceAtLeast(0f)
                    val targetScrollPx = ratio * totalScrollable
                    val info = listState.layoutInfo
                    val avgItemPx = geo.metrics.avgItemPx
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
                    listState.requestScrollToItem(newIndex, offsetInItem)
                }
            },
        contentAlignment = Alignment.TopEnd,
    ) {
        // Track backdrop — the visible 8dp bar. Kept as its own child so the
        // box itself can be `touchWidth` wide (the touch lane) without
        // painting a 24dp bar.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(width)
                .fillMaxHeight()
                .background(trackColor, RoundedCornerShape(width / 2)),
        )
        Box(
            modifier = Modifier
                // A Box child without a size modifier wraps its content — the
                // only child is the 8dp thumb — so this must fill the lane.
                .fillMaxWidth()
                .align(Alignment.TopEnd)
                .offset(y = thumbOffsetY)
                .height(with(density) { thumbHeightPx.toDp() })
                .pointerInput(listState) {
                    // Thumb top at grab time; the drag is 1:1 from there, so the
                    // finger's offset within the thumb doesn't matter.
                    var thumbOffsetAtStart = 0f
                    // dragAmount in Compose's detectDragGestures is the
                    // *per-event* delta (positionChange = position - previousPosition),
                    // not the cumulative finger motion from the start of the drag.
                    // The list scroll below uses it correctly per-event; but the
                    // thumb position needs cumulative motion — otherwise the
                    // thumb only moves by the last event's delta and snaps back
                    // to the start each tick (v0.5.7 bug: thumb jumps on release).
                    var accumulatedDeltaY = 0f
                    detectDragGestures(
                        onDragStart = {
                            accumulatedDeltaY = 0f
                            thumbOffsetAtStart = geo.offsetPx.toFloat()
                            isDragging = true
                        },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            // The thumb's local origin is the touchBox's top-left
                            // (the touchBox only moves via the offset modifier, which
                            // we move synchronously below), so dragAmount == finger
                            // delta in track coords per event. Accumulate across
                            // events to get the thumb's 1:1 position under the finger.
                            accumulatedDeltaY += dragAmount.y
                            // ponytail: the thumb is clamped to the track but the
                            // list is only scrolled by the raw delta, so dragging
                            // past either end leaves the thumb pinned while the
                            // list stops — on release the isDragging=false re-sync
                            // snaps it back to the real position. Feels like a
                            // rubber band; making it exact needs the consumed
                            // delta, which dispatchRawDelta doesn't report.
                            val newOffset = (thumbOffsetAtStart + accumulatedDeltaY)
                                .toInt()
                                .coerceIn(0, geo.scrollableTrackPx)

                            // 1) Visual update — synchronous, on the same frame
                            // as the pointer event (a State write, so the snapshot
                            // system drives recomposition+relayout+redraw in the
                            // same frame; see the offset comment above for the
                            // reason we don't use `.offset { IntOffset(...) }`).
                            dragOffsetPx = newOffset

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
                            //
                            // ponytail: pxPerTrackPx is an average-item
                            // estimate, so it drifts from the true
                            // scroll-per-thumb-pixel ratio on lists whose items
                            // have varying heights. Exact ratio would need a
                            // LazyListState scroll-offset lookup per tick;
                            // upgrade only if that drift is visible in use.
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
                            val pxPerTrackPx = scrollablePx / geo.scrollableTrackPx
                            if (pxPerTrackPx > 0f) {
                                listState.dispatchRawDelta(dragAmount.y * pxPerTrackPx)
                            }
                        },
                        onDragEnd = { isDragging = false },
                        onDragCancel = { isDragging = false },
                    )
                },
        ) {
            // Visual thumb: the same thin rectangle as v0.5.x, right-aligned
            // inside the wider touch lane. The touch-Box itself stays
            // transparent — only the inner Box paints, keeping the visual a
            // slim 8dp indicator while the user gets a 24dp hit-test lane.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
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

/**
 * Everything the gesture lambdas need that changes while scrolling. Held in a
 * [rememberUpdatedState] so the `pointerInput` blocks can be keyed on
 * `listState` alone.
 */
private class ScrollbarGeo(
    val metrics: ScrollMetrics,
    val thumbHeightPx: Int,
    val scrollableTrackPx: Int,
    val offsetPx: Int,
)