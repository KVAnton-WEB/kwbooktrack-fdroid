package ru.antonkw.kwbooktrack.ui.common

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ScrollbarMetricsTest {

    private val avgItemPx = 80f
    private val itemCount = 100
    private val viewportPx = 800f

    private fun metrics(
        index: Int = 0,
        offset: Int = 0,
        avg: Float = avgItemPx,
        count: Int = itemCount,
        viewport: Float = viewportPx,
        visibleContentPx: Float = 0f,
        visibleCount: Int = 0,
        minContentPx: Float = 0f,
        canBackward: Boolean = true,
        canForward: Boolean = true,
    ) = computeScrollMetrics(
        firstVisibleItemIndex = index,
        firstVisibleItemScrollOffset = offset,
        avgItemPx = avg,
        totalItemsCount = count,
        viewportPx = viewport,
        visibleContentPx = visibleContentPx,
        visibleCount = visibleCount,
        minContentPx = minContentPx,
        canScrollBackward = canBackward,
        canScrollForward = canForward,
    )

    @Test
    fun `top of list — fraction is 0`() {
        val m = metrics()
        assertThat(m.scrollFraction).isEqualTo(0f)
        assertThat(m.hasOverflow).isTrue()
    }

    @Test
    fun `scrolled halfway — fraction near 0_5`() {
        // 100 items * 80px = 8000. Viewport 800. Scrollable range = 7200.
        // index=45 → scrollPx = 3600 → fraction = 3600/7200 = 0.5
        val m = metrics(index = 45)
        assertThat(m.scrollFraction).isWithin(1e-4f).of(0.5f)
    }

    @Test
    fun `partial scroll within first item moves the thumb`() {
        // Regression from v0.5.2: viewportStartOffset (the broken signal) does
        // not change between these two states — both have firstVisibleItem=0 —
        // but firstVisibleItemScrollOffset does.
        val noScroll = metrics()
        val scrolled30 = metrics(offset = 30)
        assertThat(scrolled30.scrollFraction).isGreaterThan(noScroll.scrollFraction)
        assertThat(scrolled30.scrollFraction).isWithin(1e-4f).of(30f / 7200f)
    }

    @Test
    fun `bottom of list — fraction is 1`() {
        // 100 items * 80px = 8000. Viewport 800. Max scroll = 7200.
        // firstVisibleItemIndex = 90 → scrollPx = 7200 → fraction = 1.
        val m = metrics(index = 90)
        assertThat(m.scrollFraction).isEqualTo(1f)
    }

    @Test
    fun `empty list — no overflow, fraction 0`() {
        val m = metrics(count = 0)
        assertThat(m.hasOverflow).isFalse()
        assertThat(m.scrollFraction).isEqualTo(0f)
    }

    @Test
    fun `content fits in viewport — no overflow`() {
        // 3 items of 80px each = 240px content, viewport 800px. A list that fits
        // reports no scrollability in either direction, which is what
        // hasOverflow is read from — the item-count arithmetic can't tell a
        // fitting list from one whose single item happens to measure small.
        val m = metrics(count = 3, canBackward = false, canForward = false)
        assertThat(m.hasOverflow).isFalse()
        assertThat(m.scrollFraction).isEqualTo(0f)
    }

    @Test
    fun `one item past the viewport shows the bar even when the estimate fits`() {
        // BookDetailScreen packs every note into a single item, so its height
        // can grow past the viewport while totalItemsCount and the frozen mean
        // both stay exactly where they were. Deriving hasOverflow from the
        // estimate said "fits" here and the bar was never drawn at all.
        val m = metrics(count = 2, avg = 400f, viewport = 800f)
        assertThat(m.totalScrollPx).isEqualTo(800f) // the estimate's wrong answer
        assertThat(m.hasOverflow).isTrue() // the list's answer
    }

    @Test
    fun `unmeasured average — no overflow, bar stays hidden`() {
        // First composition: layout not measured yet, so the frozen estimate is
        // 0. Must report no overflow rather than dividing by zero.
        val m = metrics(avg = 0f)
        assertThat(m.hasOverflow).isFalse()
        assertThat(m.scrollFraction).isEqualTo(0f)
        assertThat(m.thumbFraction).isEqualTo(1f)
    }

    @Test
    fun `thumb fraction is clamped to 8 percent minimum`() {
        // 1000 items * 80px = 80_000. viewportPx=800. Raw fraction = 800/80000 = 0.01.
        val m = metrics(count = 1000)
        assertThat(m.thumbFraction).isAtLeast(0.08f)
    }

    @Test
    fun `short list — thumb fills most of the track`() {
        // The notes-list case: 8 rows in a 2000px viewport. avgItemPx=400 →
        // totalScrollPx=3200, thumbFraction=0.625. There *is* scrollable track
        // left, so taps must work — an earlier version special-cased taps on
        // the thumb and silently swallowed them, which made a short list feel
        // like a dead bar.
        val m = metrics(avg = 400f, count = 8, viewport = 2000f)
        assertThat(m.hasOverflow).isTrue()
        assertThat(m.thumbFraction).isWithin(1e-4f).of(2000f / 3200f)
        assertThat(m.thumbFraction).isLessThan(1f)
    }

    @Test
    fun `heterogeneous item sizes — fraction comes from the frozen average`() {
        // BookDetailScreen packs every note into one item, so the visible mix
        // is wildly uneven. Position must depend only on the frozen avg, never
        // on which items happen to be on screen — otherwise the thumb breathes.
        val atIndex10 = metrics(index = 10, avg = 500f, count = 20, viewport = 1500f)
        assertThat(atIndex10.hasOverflow).isTrue()
        assertThat(atIndex10.totalScrollPx).isEqualTo(10_000f)
        assertThat(atIndex10.scrollFraction).isWithin(1e-4f).of(5000f / 8500f)
    }

    @Test
    fun `tall visible item raises the estimated content height`() {
        // BookDetailScreen: все заметки лежат в одном item'е высотой 4000px.
        // Оценка «среднее × количество» считала контент короче экрана
        // (4 × 400 = 1600 при viewport 800 — то есть почти впритык), и
        // ползунок занимал всю дорожку, не двигаясь при прокрутке.
        val m = metrics(
            count = 4,
            avg = 400f,
            viewport = 800f,
            visibleContentPx = 4000f,
            visibleCount = 1,
        )
        assertThat(m.totalScrollPx).isEqualTo(4000f + 3 * 400f)
        assertThat(m.thumbFraction).isLessThan(1f)
    }

    @Test
    fun `content height is never below the viewport`() {
        // Замерено на устройстве: на странице книги оценка «среднее ×
        // количество» дала 1681 px при экране 2019 px — контент «короче
        // экрана», ползунок занимал всю дорожку и не двигался. Нижняя
        // граница — экран и измеренный самый высокий элемент.
        val m = metrics(
            count = 8,
            avg = 210f,
            viewport = 2019f,
            minContentPx = 2019f,
        )
        assertThat(m.totalScrollPx).isAtLeast(2019f)
        assertThat(m.thumbFraction).isAtMost(1f)
    }

    @Test
    fun `estimate is unchanged when visible items are ordinary`() {
        // Обычный список: сумма видимых элементов меньше оценки
        // «среднее × количество» — считаем как раньше.
        val m = metrics(count = 100, avg = 80f, viewport = 800f, visibleContentPx = 5 * 80f, visibleCount = 5)
        assertThat(m.totalScrollPx).isEqualTo(8000f)
    }

    @Test
    fun `scrollPx is exposed so a tap can dispatch a delta`() {
        // The tap path computes (targetScrollPx - scrollPx) and hands that to
        // dispatchRawDelta. If scrollPx were missing the delta would be
        // absolute-from-zero and every tap above the thumb would scroll the
        // wrong way — which is the "thumb doesn't rise" symptom.
        assertThat(metrics(index = 45).scrollPx).isEqualTo(3600f)
        assertThat(metrics(index = 45, offset = 30).scrollPx).isEqualTo(3630f)
        assertThat(metrics().scrollPx).isEqualTo(0f)
    }

    @Test
    fun `at the very top the thumb pins to 0 even if the estimate says otherwise`() {
        // A stale (over-estimating) avg would otherwise put the thumb partway
        // down the track on the first frame.
        val m = metrics(index = 0, canBackward = false, canForward = true)
        assertThat(m.scrollFraction).isEqualTo(0f)
    }

    @Test
    fun `at the very end the thumb pins to 1 even if the estimate undershoots`() {
        // The frozen average can undershoot the real content height — worst on
        // BookDetailScreen, where all notes share one item. Without the pin the
        // thumb could never reach the bottom of the track.
        val m = metrics(index = 50, canBackward = true, canForward = false)
        assertThat(m.hasOverflow).isTrue()
        assertThat(m.scrollFraction).isEqualTo(1f)
    }

    @Test
    fun `tapping above the thumb from the end produces a negative delta`() {
        // Regression for "scroll to the end, then tap higher: nothing happens".
        // The tap dispatches (targetScrollPx - scrollPx), so the current
        // position has to be exposed on the metrics at all.
        val atEnd = metrics(index = 90, canForward = false)
        val mid = metrics(index = 45)
        val delta = 0.5f * (mid.totalScrollPx - mid.viewportPx) - atEnd.scrollPx
        assertThat(atEnd.scrollPx).isEqualTo(7200f)
        assertThat(delta).isLessThan(0f)
    }

    private fun thumbMap(
        avg: Float = avgItemPx,
        count: Int = itemCount,
        viewport: Float = viewportPx,
        track: Int = 1000,
    ): ThumbMap {
        val m = metrics(avg = avg, count = count, viewport = viewport)
        return ThumbMap(
            avgItemPx = m.avgItemPx,
            contentRangePx = m.totalScrollPx - m.viewportPx,
            scrollableTrackPx = track,
            totalItemsCount = m.totalItemsCount,
        )
    }

    /** Позиция списка в пикселях контента после прыжка в [target]. */
    private fun ThumbMap.contentPx(target: ScrollTarget): Float =
        avgItemPx * target.index + target.offset

    @Test
    fun `thumb at the top — the list starts at the first item`() {
        val target = thumbMap().target(0)
        assertThat(target.index).isEqualTo(0)
        assertThat(target.offset).isEqualTo(0)
    }

    @Test
    fun `thumb at the bottom — the list reaches its last screenful`() {
        // 100 items * 80px = 8000, viewport 800 → 7200 scrollable px.
        // The last screenful starts at item 90.
        val target = thumbMap().target(1000)
        assertThat(target.index).isEqualTo(90)
        assertThat(target.offset).isEqualTo(0)
    }

    @Test
    fun `a pixel of the thumb moves the list by the whole track ratio`() {
        // The core of the drag: the list follows the finger, not a fixed
        // number of cards per event. 7200 scrollable px over a 1000px track
        // = 7.2px of content per pixel of the thumb.
        val map = thumbMap()
        val a = map.target(100)
        val b = map.target(101)
        assertThat(map.contentPx(b) - map.contentPx(a)).isWithin(0.5f).of(7.2f)
    }

    @Test
    fun `the list stays where the thumb was dropped`() {
        // Round trip: the position the jump produces must map back to the
        // very same thumb offset, otherwise the thumb snaps back to the
        // list's position when the finger is released.
        val map = thumbMap()
        for (offsetPx in listOf(0, 137, 500, 861, 1000)) {
            val target = map.target(offsetPx)
            val back = map.contentPx(target) / map.contentRangePx * map.scrollableTrackPx
            assertThat(back).isWithin(1f).of(offsetPx.toFloat())
        }
    }

    @Test
    fun `the target never leaves the list`() {
        val map = thumbMap()
        assertThat(map.target(-500).index).isAtLeast(0)
        assertThat(map.target(9999).index).isAtMost(itemCount - 1)
        assertThat(map.target(9999).offset).isAtLeast(0)
    }
}
