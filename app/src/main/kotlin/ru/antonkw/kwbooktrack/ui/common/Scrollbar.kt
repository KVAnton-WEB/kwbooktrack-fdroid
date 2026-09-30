package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Metrics describing where the thumb of a [VerticalDraggableScrollbar] should sit.
 *
 * Sizes are in raw pixels (LazyListState works in pixels). [thumbFraction] is the
 * proportion of the track the thumb occupies (clamped to a sensible minimum so
 * the user can still grab it). [scrollFraction] is the proportion of the total
 * scrollable content that has been scrolled past — `0` at the top, `1` at the
 * bottom. Both fractions are `0` when the list doesn't overflow.
 */
data class ScrollbarMetrics(
    val scrollFraction: Float,
    val thumbFraction: Float,
)

/**
 * Compute the [ScrollbarMetrics] from a [LazyListState]. Uses the average of the
 * currently-visible items' sizes to estimate the total scrollable height —
 * accurate enough for a thumb; cheap (no extra layout pass).
 */
fun LazyListState.scrollMetrics(): ScrollbarMetrics {
    val info = layoutInfo
    val total = info.totalItemsCount
    if (total == 0) return ScrollbarMetrics(0f, 1f)
    val visible = info.visibleItemsInfo
    val viewportSize = (info.viewportEndOffset - info.viewportStartOffset).toFloat()
    if (visible.isEmpty() || viewportSize <= 0f) return ScrollbarMetrics(0f, 1f)

    val avgSize = visible.sumOf { it.size }.toFloat() / visible.size
    val totalSize = avgSize * total
    if (totalSize <= viewportSize) return ScrollbarMetrics(0f, 1f)

    val first = visible.first()
    val scrolledInFirst = -first.offset + info.viewportStartOffset + firstVisibleItemScrollOffset
    val scrollOffset = first.index * avgSize + scrolledInFirst
    val fraction = (scrollOffset / (totalSize - viewportSize)).coerceIn(0f, 1f)
    // Thumb takes at least 8% of the track so the user can grab it on long lists.
    val thumb = (viewportSize / totalSize).coerceIn(0.08f, 1f)
    return ScrollbarMetrics(fraction, thumb)
}

/**
 * Vertical scrollbar with a draggable thumb. Place it as a sibling of the
 * [androidx.compose.foundation.lazy.LazyColumn] inside a `Box(fillMaxSize)` and
 * align it to [Alignment.CenterEnd]. Drag the thumb with a finger; tap the
 * track to jump the list to that position.
 */
@Composable
fun VerticalDraggableScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
    thumbColor: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
    thumbWidth: androidx.compose.ui.unit.Dp = 6.dp,
) {
    val metrics by remember(state) { derivedStateOf { state.scrollMetrics() } }
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableStateOf(1f) }
    val scope = rememberCoroutineScope()
    val itemCount = state.layoutInfo.totalItemsCount
    val visibleInfo = state.layoutInfo
    val avgItemPx = remember(visibleInfo, itemCount) {
        if (itemCount == 0 || visibleInfo.visibleItemsInfo.isEmpty()) 1f
        else visibleInfo.visibleItemsInfo.sumOf { it.size }.toFloat() / visibleInfo.visibleItemsInfo.size
    }
    val minThumbPx = with(density) { 24.dp.toPx() }

    val thumbHeightPx = (metrics.thumbFraction * trackHeightPx).coerceAtLeast(minThumbPx)
    val scrollableTrackPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
    val thumbOffsetPx = (metrics.scrollFraction * scrollableTrackPx).coerceIn(0f, scrollableTrackPx)

    if (itemCount == 0) return

    // Convert a touch Y on the track (0..trackHeightPx) to a target scroll
    // offset in the LazyList (0..avgItemPx * (itemCount - 1)).
    fun jumpToTouchY(touchY: Float) {
        if (scrollableTrackPx <= 0f || itemCount == 0) return
        val ratio = (touchY / scrollableTrackPx).coerceIn(0f, 1f)
        val newScrollPx = ratio * avgItemPx * (itemCount - 1)
        val newIndex = (newScrollPx / avgItemPx).toInt().coerceIn(0, itemCount - 1)
        val offsetInItem = (newScrollPx - newIndex * avgItemPx).toInt()
        scope.launch {
            state.scrollToItem(newIndex, offsetInItem)
        }
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .widthIn(min = 24.dp)
            .width(thumbWidth + 8.dp)
            .onSizeChanged { trackHeightPx = it.height.toFloat() },
    ) {
        // Track background.
        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 4.dp)
                .width(thumbWidth)
                .fillMaxHeight()
                .background(trackColor, RoundedCornerShape(thumbWidth / 2)),
        )
        // Thumb — draggable, full click target on the right edge so a finger
        // doesn't have to land precisely on the thin pill.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 4.dp)
                .fillMaxWidth()
                .offset { IntOffset(0, thumbOffsetPx.roundToInt()) }
                .pointerInput(state, itemCount, avgItemPx, scrollableTrackPx) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            // Re-anchor the thumb to where the finger landed
                            // so a grab anywhere on the column works.
                            jumpToTouchY(offset.y)
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            jumpToTouchY(change.position.y)
                        },
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .background(thumbColor, RoundedCornerShape(thumbWidth / 2)),
            )
        }
    }
}
