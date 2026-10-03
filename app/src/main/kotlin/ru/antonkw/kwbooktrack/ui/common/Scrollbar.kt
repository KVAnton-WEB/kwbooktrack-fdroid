package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt

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
 * - Scroll position = `avgItemPx * firstVisibleItemIndex + firstVisibleItemScrollOffset`
 *   (the formula `LazyListState.calculateScrollOffset` uses internally for
 *   its own ScrollIndicatorState).
 * - `info.viewportStartOffset` is *not* the scroll position in Foundation
 *   1.7.x — it is hard-coded to `-beforeContentPadding` and only changes when
 *   padding does. Use `firstVisibleItemIndex` / `firstVisibleItemScrollOffset`.
 * - LazyListState exposes no total content height, so it has to be estimated.
 *   See the `ponytail:` note on the frozen estimate in the composable.
 *
 * Interaction — the whole [touchWidth] lane is **one** gesture surface:
 * - Release without crossing touch slop → jump, so the thumb's centre lands
 *   on the tap (M3 page-jump).
 * - Drag past touch slop → scrub, thumb moving 1:1 with the finger.
 *
 * Both paths scroll through `dispatchRawDelta` rather than
 * `requestScrollToItem`, so the target is a pixel offset instead of an item
 * index: a tap below a tall last item used to snap to that item's start
 * instead of the end, and a scrub landed on whichever item boundary the
 * index happened to name.
 *
 * Known limitation, unchanged by that swap: `ScrollableState.dispatchRawDelta`
 * is a default method that wraps the call in `scroll { }`, so it needs the
 * scroll mutex. While a fling holds it, a tap queues until the fling ends and
 * a scrub is dropped mid-gesture. `requestScrollToItem` went through the same
 * mutex, so this is a pre-existing ceiling, not something the pixel deltas
 * introduced. Composition has no mutex-free scroll entry point to swap in.
 *
 * ponytail: Compose Foundation's `rememberScrollbarState` /
 * `Modifier.verticalScrollbar` API didn't ship in our Foundation 1.7.2 —
 * verified: the 1.7.2 `foundation` artifact contains zero `Scrollbar`
 * classes. The next-best standard option is `androidx.compose.material` (M1)
 * which we deliberately don't pull in. So the scrollbar lives here.
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

    // Read the list state straight out of the composable body. Every field we
    // need is snapshot-backed in Foundation 1.7.x — `layoutInfo` reads
    // `layoutInfoState.value`, `firstVisibleItemIndex` /
    // `firstVisibleItemScrollOffset` are `MutableIntState` delegates on
    // LazyListScrollPosition — so these plain property reads register normal
    // recomposition subscriptions and the thumb lands in the SAME frame the
    // list scrolls in. The previous `snapshotFlow { … } + collectAsState`
    // pair emitted from a snapshot-apply observer, i.e. one frame late, plus
    // a coroutine hop: during flings that read as the thumb sliding behind
    // the content.
    val info = listState.layoutInfo
    val viewportPx = (info.viewportEndOffset - info.viewportStartOffset -
        info.beforeContentPadding - info.afterContentPadding).toFloat()

    // ponytail: LazyListState 1.7.x has no total-content-height, so we
    // estimate it from the mean of the *visible* items — and FREEZE that
    // estimate. Re-deriving it per frame (the previous behaviour) made the
    // thumb visibly breathe as the visible mix changed, made the scrub ratio
    // drift mid-gesture, and could even flip `hasOverflow` so the bar
    // vanished mid-scroll. Re-measuring only when the list's shape changes
    // trades a slightly stale ratio for a stable bar. The tallest visible
    // item is part of the key because BookDetailScreen packs every note into
    // one item: its height changes as notes are added or filtered without
    // `totalItemsCount` moving, and the mean would otherwise stay frozen at
    // the value it had on the first frame.
    val avgItemPx = remember(
        info.totalItemsCount,
        viewportPx.roundToInt(),
        info.visibleItemsInfo.maxOfOrNull { it.size },
    ) {
        val visible = info.visibleItemsInfo
        if (visible.isEmpty()) 0f
        else visible.sumOf { it.size }.toFloat() / visible.size
    }
    val metrics = computeScrollMetrics(
        firstVisibleItemIndex = listState.firstVisibleItemIndex,
        firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
        avgItemPx = avgItemPx,
        totalItemsCount = info.totalItemsCount,
        viewportPx = viewportPx,
        // Both are derived from snapshot-backed state (item index + layoutInfo),
        // so reading them in composition re-subscribes on every scroll tick.
        canScrollBackward = listState.canScrollBackward,
        canScrollForward = listState.canScrollForward,
    )

    // All three must be remembered *above* the early return: `hasOverflow` is
    // derived from an estimate, so a flip mid-gesture used to dispose the
    // whole scrollbar subtree — drag state and all — and rebuild it from
    // scratch.
    var trackHeightPx by remember { mutableIntStateOf(0) }
    var dragOffsetPx by remember { mutableStateOf<Int?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    // One minimum rule, not two. The 0.08 floor in [computeScrollMetrics] is
    // the usual winner on a phone-sized track; the hard 24dp only binds on
    // tracks shorter than that, where it must not exceed the track itself.
    val minThumbPx = with(density) { 24.dp.toPx() }.roundToInt()
        .coerceAtMost(trackHeightPx)
    val thumbHeightPx = (metrics.thumbFraction * trackHeightPx)
        .roundToInt()
        .coerceAtLeast(minThumbPx)
    val scrollableTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(1)
    // roundToInt, not toInt: truncation made the thumb sit still for a few
    // frames then jump a pixel on every slow scroll.
    val thumbOffsetPx = (metrics.scrollFraction * scrollableTrackPx)
        .roundToInt()
        .coerceIn(0, scrollableTrackPx)

    // If the content fits the viewport there's nothing to scroll.
    if (!metrics.hasOverflow) return

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
    // While not scrubbing the offset comes straight from the metrics, so the
    // thumb tracks the list. During a scrub the finger-driven offset wins:
    // scrollFraction lags behind dispatchRawDelta and is approximate (it
    // averages item heights), so letting it overwrite the in-progress visual
    // position makes the thumb snap back to a stale spot. The drag offset is
    // cleared on release — otherwise the finger's last position wins forever
    // and the thumb detaches from the list for the rest of the session.
    val effectiveOffsetPx = dragOffsetPx ?: thumbOffsetPx
    val thumbOffsetY = with(density) { effectiveOffsetPx.toDp() }

    // Geometry read from inside the gesture loop. Keying `pointerInput` on
    // these is what used to restart the gesture detectors: avgItemPx is the mean
    // height of the *visible* items, so crossing one item boundary moves
    // thumbFraction → thumbHeightPx → scrollableTrackPx, and the detector was
    // torn down and rebuilt dozens of times per fling. Keyed on
    // rememberUpdatedState instead, so the detector is built once.
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
            // The list already announces its own scroll position; a scrollbar
            // node on top of it is duplicate noise for TalkBack.
            .clearAndSetSemantics { }
            .pointerInput(listState) {
                // One gesture surface for the whole lane. The previous version
                // split this across two nodes — detectTapGestures here,
                // detectDragGestures on the thumb — so a tap was racing the
                // thumb's drag handler and there was no single place where a
                // release could be classified.
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val downY = down.position.y
                    // Thumb position at grab time; the scrub is 1:1 from there,
                    // so the finger's offset within the thumb doesn't matter.
                    val grabOffsetPx = geo.offsetPx
                    var lastY = downY
                    var totalDeltaY = 0f

                    try {
                        while (true) {
                            val change = awaitPointerEvent()
                                .changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            val delta = change.position.y - lastY
                            if (delta == 0f) continue
                            lastY = change.position.y
                            totalDeltaY += delta
                            // Stay unconsumed until the finger commits to a
                            // scrub, so sub-slop jitter still reads as a tap.
                            if (abs(totalDeltaY) <= viewConfiguration.touchSlop) continue

                            change.consume()
                            isDragging = true
                            // ponytail: the thumb is clamped to the track but
                            // the list is only scrolled by the raw delta, so a
                            // scrub past either end leaves the thumb pinned
                            // while the list stops — on release the re-sync
                            // snaps it back. Feels like a rubber band; making
                            // it exact needs the consumed delta, which
                            // dispatchRawDelta does report — wire that up if
                            // the dead zone at the ends ever bothers anyone.
                            dragOffsetPx = (grabOffsetPx + totalDeltaY)
                                .roundToInt()
                                .coerceIn(0, geo.scrollableTrackPx)
                            val totalScrollable = (geo.metrics.totalScrollPx -
                                geo.metrics.viewportPx).coerceAtLeast(0f)
                            val pxPerTrackPx = totalScrollable / geo.scrollableTrackPx
                            if (pxPerTrackPx > 0f) {
                                listState.dispatchRawDelta(delta * pxPerTrackPx)
                            }
                        }
                    } finally {
                        isDragging = false
                        // Back to following the list, so the thumb can't stay
                        // stranded where the finger left it.
                        dragOffsetPx = null
                    }

                    // Released below touch slop → treat as a tap and put the
                    // thumb's centre under the finger. Taps on the thumb itself
                    // are deliberately not special-cased: the thumb is
                    // proportional to the content, so on a short list it covers
                    // most of the track, and swallowing those taps is what made
                    // the bar feel dead exactly where people reach for it.
                    if (abs(totalDeltaY) <= viewConfiguration.touchSlop) {
                        val ratio = ((downY - geo.thumbHeightPx / 2f) / geo.scrollableTrackPx)
                            .coerceIn(0f, 1f)
                        val targetScrollPx = ratio *
                            (geo.metrics.totalScrollPx - geo.metrics.viewportPx)
                                .coerceAtLeast(0f)
                        listState.dispatchRawDelta(targetScrollPx - geo.metrics.scrollPx)
                    }
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
        // Thumb. Purely visual: gestures are handled by the lane above, so it
        // needs no pointerInput of its own.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(y = thumbOffsetY)
                .width(width)
                .height(with(density) { thumbHeightPx.toDp() })
                .background(
                    if (isDragging) MaterialTheme.colorScheme.primary else thumbColor,
                    RoundedCornerShape(width / 2),
                ),
        )
    }
}

/** Pure math over already-measured inputs; see the composable for the wiring. */
internal fun computeScrollMetrics(
    firstVisibleItemIndex: Int,
    firstVisibleItemScrollOffset: Int,
    avgItemPx: Float,
    totalItemsCount: Int,
    viewportPx: Float,
    canScrollBackward: Boolean = true,
    canScrollForward: Boolean = true,
): ScrollMetrics {
    if (totalItemsCount == 0 || avgItemPx <= 0f || viewportPx <= 0f) {
        return ScrollMetrics(
            scrollFraction = 0f,
            thumbFraction = 1f,
            avgItemPx = avgItemPx,
            scrollPx = 0f,
            totalScrollPx = 0f,
            viewportPx = viewportPx,
            hasOverflow = false,
        )
    }
    val totalScrollPx = avgItemPx * totalItemsCount
    // scrollPx = avgItemPx * firstVisibleItemIndex + firstVisibleItemScrollOffset
    // — same formula LazyListState.calculateScrollOffset() uses for its
    // ScrollIndicatorState. viewportStartOffset in Foundation 1.7.x is fixed to
    // -beforeContentPadding (it's the items viewport's content-space start, not
    // the scroll position), so reading it here made scrollFraction stay at 0
    // and the thumb freeze at the top regardless of scroll.
    val scrollPx = avgItemPx * firstVisibleItemIndex + firstVisibleItemScrollOffset
    // Overflow comes from the LIST's own scrollability flags, not from
    // `totalScrollPx > viewportPx`. Those flags are derived by the list from
    // measured layout, so they stay correct when a single item changes size —
    // BookDetailScreen packs every note into one item, so adding a note can
    // push it past the viewport while `totalItemsCount` and the frozen mean
    // both stay put. The estimate said "fits", hasOverflow was false, and the
    // whole bar was never drawn. Either flag is enough: a scrollable list is
    // scrolled-forward at one end and scrolled-backward at the other, and one
    // that fits the viewport has neither.
    val hasOverflow = canScrollBackward || canScrollForward
    // The frozen estimate can undershoot the real content height (most visibly
    // on BookDetailScreen, where every note lives in one item), which would
    // leave the thumb short of the bottom of the track. Trusting
    // canScrollForward/Backward pins it to the true ends instead of the
    // estimated ones.
    val fraction = when {
        !hasOverflow -> 0f
        !canScrollBackward -> 0f
        !canScrollForward -> 1f
        else -> (scrollPx / (totalScrollPx - viewportPx)).coerceIn(0f, 1f)
    }
    // Thumb takes at least 8% of the track so it's grabbable on long lists.
    val thumbFraction = (viewportPx / totalScrollPx).coerceIn(0.08f, 1f)
    return ScrollMetrics(
        scrollFraction = fraction,
        thumbFraction = thumbFraction,
        avgItemPx = avgItemPx,
        scrollPx = scrollPx,
        totalScrollPx = totalScrollPx,
        viewportPx = viewportPx,
        hasOverflow = hasOverflow,
    )
}

internal data class ScrollMetrics(
    val scrollFraction: Float,
    val thumbFraction: Float,
    val avgItemPx: Float,
    val scrollPx: Float,
    val totalScrollPx: Float,
    val viewportPx: Float,
    val hasOverflow: Boolean,
)

/**
 * Everything the gesture loop needs that changes while scrolling. Held in a
 * [rememberUpdatedState] so the `pointerInput` block can be keyed on
 * `listState` alone.
 */
private class ScrollbarGeo(
    val metrics: ScrollMetrics,
    val thumbHeightPx: Int,
    val scrollableTrackPx: Int,
    val offsetPx: Int,
)
