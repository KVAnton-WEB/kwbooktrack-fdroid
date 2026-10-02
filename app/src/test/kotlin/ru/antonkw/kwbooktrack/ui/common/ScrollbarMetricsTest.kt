package ru.antonkw.kwbooktrack.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollbarMetricsTest {

    private val itemHeight = 80f
    private val itemCount = 100
    private val viewportPx = 800f

    @Test
    fun `top of list — fraction is 0`() {
        val m = computeScrollMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            visibleItemSizes = listOf(80, 80, 80, 80, 80, 80, 80, 80, 80, 80),
            totalItemsCount = itemCount,
            viewportPx = viewportPx,
        )
        assertThat(m.scrollFraction).isEqualTo(0f)
        assertThat(m.hasOverflow).isTrue()
    }

    @Test
    fun `scrolled halfway — fraction near 0_5`() {
        // 100 items * 80px = 8000. Viewport 800. Scrollable range = 7200.
        // Halfway scroll: index=5, offset=0 → scrollPx = 5*80 = 400 (too low)
        // Adjust to index=45, offset=0 → scrollPx = 45*80 = 3600 → fraction = 3600/7200 = 0.5
        val m = computeScrollMetrics(
            firstVisibleItemIndex = 45,
            firstVisibleItemScrollOffset = 0,
            visibleItemSizes = listOf(80, 80, 80, 80, 80, 80, 80, 80, 80, 80),
            totalItemsCount = itemCount,
            viewportPx = viewportPx,
        )
        assertThat(m.scrollFraction).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun `partial scroll within first item moves the thumb`() {
        // This is the v0.5.2 regression: viewportStartOffset (the broken signal)
        // does not change between these two states — both have firstVisibleItem=0
        // — but firstVisibleItemScrollOffset does. The bug was reading the wrong
        // field; the fix uses firstVisibleItemScrollOffset.
        val noScroll = computeScrollMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            visibleItemSizes = listOf(80, 80, 80, 80, 80, 80, 80, 80, 80, 80),
            totalItemsCount = itemCount,
            viewportPx = viewportPx,
        )
        val scrolled30 = computeScrollMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 30,
            visibleItemSizes = listOf(80, 80, 80, 80, 80, 80, 80, 80, 80, 80),
            totalItemsCount = itemCount,
            viewportPx = viewportPx,
        )
        assertThat(scrolled30.scrollFraction).isGreaterThan(noScroll.scrollFraction)
        assertThat(scrolled30.scrollFraction).isWithin(1e-4f).of(30f / 7200f)
    }

    @Test
    fun `bottom of list — fraction is 1`() {
        // 100 items * 80px = 8000. Viewport 800. Max scroll = 7200.
        // finalVisibleIndex = 99 - viewportItems + 1. With avg=80 and viewport=800,
        // visible items ≈ 10. firstVisibleItemIndex = 90, offset=0 gives
        // scrollPx = 90*80 = 7200 → fraction = 1.
        val m = computeScrollMetrics(
            firstVisibleItemIndex = 90,
            firstVisibleItemScrollOffset = 0,
            visibleItemSizes = listOf(80, 80, 80, 80, 80, 80, 80, 80, 80, 80),
            totalItemsCount = itemCount,
            viewportPx = viewportPx,
        )
        assertThat(m.scrollFraction).isEqualTo(1f)
    }

    @Test
    fun `empty list — no overflow, fraction 0`() {
        val m = computeScrollMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            visibleItemSizes = emptyList(),
            totalItemsCount = 0,
            viewportPx = viewportPx,
        )
        assertThat(m.hasOverflow).isFalse()
        assertThat(m.scrollFraction).isEqualTo(0f)
    }

    @Test
    fun `content fits in viewport — no overflow`() {
        // 3 items of 80px each = 240px content, viewport 800px → no scroll possible.
        val m = computeScrollMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            visibleItemSizes = listOf(80, 80, 80),
            totalItemsCount = 3,
            viewportPx = viewportPx,
        )
        assertThat(m.hasOverflow).isFalse()
        assertThat(m.scrollFraction).isEqualTo(0f)
    }

    @Test
    fun `thumb fraction is clamped to 8 percent minimum`() {
        // 1000 items * 80px = 80_000. viewportPx=800. Raw fraction = 800/80000 = 0.01.
        // Should be clamped up to 0.08 so the thumb stays grabbable.
        val m = computeScrollMetrics(
            firstVisibleItemIndex = 0,
            firstVisibleItemScrollOffset = 0,
            visibleItemSizes = listOf(80, 80, 80, 80, 80, 80, 80, 80, 80, 80),
            totalItemsCount = 1000,
            viewportPx = viewportPx,
        )
        assertThat(m.thumbFraction).isAtLeast(0.08f)
    }
}