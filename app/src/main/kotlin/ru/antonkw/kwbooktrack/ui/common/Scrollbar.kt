package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Material 3-styled vertical scrollbar for a [LazyListState].
 *
 * Layout: the bar lives in its own column on the right edge — pair it
 * with `LazyColumn(modifier = …padding(end = width))` so content doesn't
 * slide under the thumb. The bar is hidden entirely when the content
 * fits the viewport (no scroll possible).
 *
 * Geometry comes from [LazyListState.layoutInfo]:
 * - `viewportStartOffset` is the exact pixel scroll position of the list.
 * - `avgItemPx` (mean of visible items' heights) estimates the rest.
 * - Thumb position = `viewportStartOffset / (totalSize − viewportSize)`.
 *
 * Interaction:
 * - Tap the track → jump the list so the thumb centres on the tap.
 * - Drag the thumb → 1:1 thumb motion; the list scrolls to match.
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

    val scope = rememberCoroutineScope()

    // Drag anchor: touch Y in world origin (0 == top of track) captured at
    // dragStart, plus the thumb's track-relative offset at that moment.
    // Subtracting them from the current touch Y gives a clean track-relative
    // delta independent of where on the thumb the drag began.
    var dragAnchor by remember { mutableStateOf<DragAnchor?>(null) }
    val isDragging = dragAnchor != null
    val effectiveThumbOffset = dragAnchor?.let { anchor ->
        (anchor.thumbOffsetAtStart + (anchor.touchYCurrent - anchor.touchYAtStart)).toInt()
            .coerceIn(0, scrollableTrackPx)
    } ?: thumbOffsetPx

    fun jumpToTrackY(trackY: Float) {
        val ratio = ((trackY - thumbHeightPx / 2f) / scrollableTrackPx).coerceIn(0f, 1f)
        val totalScrollable = (metrics.totalScrollPx - metrics.viewportPx).coerceAtLeast(0f)
        val targetScrollPx = ratio * totalScrollable
        val info = listState.layoutInfo
        val avgItemPx = metrics.avgItemPx
        val newIndex = (targetScrollPx / avgItemPx)
            .toInt()
            .coerceIn(0, (info.totalItemsCount - 1).coerceAtLeast(0))
        // Don't let offsetInItem push past the next item's start.
        val targetItem = info.visibleItemsInfo.firstOrNull { it.index == newIndex }
            ?: info.visibleItemsInfo.firstOrNull()
        val itemSize = targetItem?.size ?: avgItemPx.toInt()
        val maxOffset = (itemSize - 1).coerceAtLeast(0)
        val offsetInItem = (targetScrollPx - newIndex * avgItemPx).toInt()
            .coerceIn(0, maxOffset)
        scope.launch { listState.scrollToItem(newIndex, offsetInItem) }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .onSizeChanged { trackHeightPx = it.height }
            .background(trackColor, RoundedCornerShape(width / 2))
            .pointerInput(listState) {
                // Tap on the track (outside the thumb) jumps the list.
                detectTapGestures { offset ->
                    jumpToTrackY(offset.y)
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 1.dp)
                .offset(y = with(density) { effectiveThumbOffset.toDp() })
                .height(with(density) { thumbHeightPx.toDp() })
                .background(
                    if (isDragging) MaterialTheme.colorScheme.primary else thumbColor,
                    RoundedCornerShape(width / 2),
                )
                .pointerInput(listState) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            // Translate the thumb-local offset (in this Box's
                            // space) back to track coordinates, then anchor.
                            val touchYTrack = effectiveThumbOffset + offset.y
                            dragAnchor = DragAnchor(
                                touchYAtStart = touchYTrack,
                                touchYCurrent = touchYTrack,
                                thumbOffsetAtStart = effectiveThumbOffset,
                            )
                            // And jump to where the user grabbed, so the
                            // list position matches the new thumb spot.
                            jumpToTrackY(touchYTrack)
                        },
                        onDragEnd = { dragAnchor = null },
                        onDragCancel = { dragAnchor = null },
                        onDrag = { change, _ ->
                            change.consume()
                            dragAnchor = dragAnchor?.copy(touchYCurrent = dragAnchor!!.touchYAtStart + change.position.y)
                        },
                    )
                },
        )
    }
}

private data class DragAnchor(
    val touchYAtStart: Float,
    val touchYCurrent: Float,
    val thumbOffsetAtStart: Int,
)

/** Cached scroll geometry; recomputed only when the list mutates or scrolls. */
private fun LazyListState.scrollMetrics(): ScrollMetrics {
    val info = layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo
    val viewportPx = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (total == 0 || visible.isEmpty() || viewportPx <= 0f) {
        return ScrollMetrics(
            scrollFraction = 0f,
            thumbFraction = 1f,
            avgItemPx = 1f,
            totalScrollPx = 0f,
            viewportPx = viewportPx,
            hasOverflow = false,
        )
    }
    val visibleSum = visible.sumOf { it.size }
    val avgItemPx = visibleSum.toFloat() / visible.size
    val totalScrollPx = avgItemPx * total
    // viewportStartOffset IS the scroll position of the list — use it
    // directly, not firstVisibleItemScrollOffset (which is derived from it
    // and would double-count when combined with firstVisibleItem.index).
    val scrollPx = info.viewportStartOffset.toFloat()
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

private data class ScrollMetrics(
    val scrollFraction: Float,
    val thumbFraction: Float,
    val avgItemPx: Float,
    val totalScrollPx: Float,
    val viewportPx: Float,
    val hasOverflow: Boolean,
)