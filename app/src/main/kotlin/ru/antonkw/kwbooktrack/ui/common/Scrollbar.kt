package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Состояние полосы прокрутки, общее для списка и самой полосы.
 *
 * Жест (скраб и прыжок по тапу) обрабатывает **список**, а не полоса: полоса
 * лежит поверх списка, а в Compose верхний брат перехватывает hit-test в
 * своей полосе — карточка под ней перестала бы открываться. Поэтому
 * обработчик живёт в модификаторе списка ([scrollbarGestures]) и работает
 * в Initial-проходе: событие там приходит раньше, чем его увидит скролл
 * списка, и consume в этом проходе отменяет перетаскивание списка, не
 * забирая тапы по карточкам.
 *
 * Полоса при этом только рисует ([VerticalListScrollbar]) — поэтому её
 * зона захвата может быть шире, чем сама полоса, и не мешать контенту.
 */
@Stable
class ScrollbarController internal constructor(
    val listState: LazyListState,
    private val density: Density,
) {
    private val estimate = AvgItemHeight()

    /**
     * Метрики скролла. Производное состояние: пересчитывается, только
     * когда список реально двигается.
     */
    internal val metrics: State<ScrollMetrics> = derivedStateOf {
        val info = listState.layoutInfo
        val viewportPx = (info.viewportEndOffset - info.viewportStartOffset -
            info.beforeContentPadding - info.afterContentPadding).toFloat()
        computeScrollMetrics(
            firstVisibleItemIndex = listState.firstVisibleItemIndex,
            firstVisibleItemScrollOffset = listState.firstVisibleItemScrollOffset,
            avgItemPx = estimate.update(info, viewportPx),
            totalItemsCount = info.totalItemsCount,
            viewportPx = viewportPx,
            // Оба значения — из snapshot-состояния, поэтому производное
            // пересчитывается на каждом тике скролла, но перерисовывает
            // только полосу — композиция не трогается.
            canScrollBackward = listState.canScrollBackward,
            canScrollForward = listState.canScrollForward,
        )
    }

    /** Высота дорожки; полоса выставляет её по факту измерения. */
    var trackHeightPx by mutableIntStateOf(0)

    /** Смещение ползунка во время скраба; null — ползунок следует за списком. */
    var dragOffsetPx: Int? by mutableStateOf(null)

    /** Идёт ли скраб — ползунок рисуется активным. */
    var isDragging by mutableStateOf(false)

    /**
     * Геометрия ползунка в пикселях дорожки.
     *
     * Пока идёт скраб, смещение задаёт палец: scrollFraction отстаёт от
     * dispatchRawDelta (он усредняет высоты элементов), и без этого ползунок
     * дёргался бы назад на место посреди перетаскивания.
     */
    internal val thumb: State<ThumbGeometry> = derivedStateOf {
        val m = metrics.value
        val track = trackHeightPx
        // Одно правило минимума, а не два. Порог 0.08 в
        // [computeScrollMetrics] обычно и выигрывает; жёсткие 24dp нужны
        // только на дорожках короче 24dp, где они не должны превышать
        // саму дорожку.
        val minThumbPx = with(density) { 24.dp.toPx() }.roundToInt().coerceAtMost(track)
        val thumbHeightPx = (m.thumbFraction * track).roundToInt().coerceAtLeast(minThumbPx)
        val scrollableTrackPx = (track - thumbHeightPx).coerceAtLeast(1)
        // roundToInt, а не toInt: усечение заставляло ползунок на пару
        // кадров стоять, а потом прыгать на пиксель при медленной прокрутке.
        val offsetPx = dragOffsetPx
            ?: (m.scrollFraction * scrollableTrackPx).roundToInt().coerceIn(0, scrollableTrackPx)
        ThumbGeometry(thumbHeightPx, scrollableTrackPx, offsetPx)
    }
}

/**
 * Создать [ScrollbarController] для списка.
 *
 * Один контроллер на список: и жесты, и отрисовка полосы читают его.
 */
@Composable
fun rememberScrollbarController(listState: LazyListState): ScrollbarController {
    val density = LocalDensity.current
    return remember(listState, density) { ScrollbarController(listState, density) }
}

/**
 * Жесты полосы прокрутки — вешаются на сам список.
 *
 * Зона захвата — [grabWidth] справа: полоса 8dp, а поймать её пальцем
 * можно за [grabWidth]. Тап в свободном поле списка ([marginWidth] справа,
 * там, где контента нет) прыгает по странице; тап по карточке остаётся
 * карточке. Перетаскивание скрабит ползунок, но только если палец начал
 * рядом с ним — жест в стороне от ползунка остаётся обычной прокруткой
 * списка.
 */
fun Modifier.scrollbarGestures(
    controller: ScrollbarController,
    grabWidth: Dp = 40.dp,
    marginWidth: Dp = 16.dp,
): Modifier = pointerInput(controller) {
    // Зона захвата: grabWidth справа. Сама полоса — 8dp, но поймать её
    // пальцем можно за всю зону.
    val marginPx = marginWidth.toPx()
    val listWidthPx = size.width.toFloat()
    // Насколько выше/ниже ползунка жест ещё считается «захватом ползунка».
    val tolerancePx = 8.dp.toPx()

    coroutineScope {
        // Воркер скролла. Он держит мьютекс списка на всё время скраба и
        // применяет дельты по порядку.
        //
        // Почему воркер, а не scrollBy прямо из обработчика жеста:
        // awaitPointerEventScope — restricted scope, там нельзя suspend-вызовы.
        // А по одному dispatchRawDelta на дельту мьютекс брать нельзя: пока
        // его держит флинг или другое перетаскивание, вызов виснет (замерено
        // на устройстве: 14 → 472 мс на вызов) — список отставал от ползунка,
        // скраб дёргался. Один scroll{} на жест держит мьютекс и сразу
        // отменяет флинг (PreventUserInput).
        // CONFLATED: в очереди остаётся только последняя цель. Команда
        // несёт НАКОПЛЕННУЮ дельту с начала скраба, поэтому воркер докручивает
        // до неё, отбрасывая промежуточные значения. Очередь не растёт, и
        // главный поток не забивается работой, которую список всё равно не
        // успевает: контент по полосе едет в ~79 раз быстрее пальца.
        val commands = Channel<ScrubCommand>(Channel.CONFLATED)
        launch {
            while (true) {
                val first = commands.receive()
                if (first !is ScrubCommand.Start) continue
                controller.listState.scroll(MutatePriority.PreventUserInput) {
                    var applied = 0f
                    var cmd: ScrubCommand = first
                    while (true) {
                        when (cmd) {
                            is ScrubCommand.Delta -> {
                                // Шаг ограничен одним элементом: список
                                // успевает составить примерно одну карточку
                                // за кадр, а scrollBy на десятки тысяч
                                // пикселей заставляет перемерить их все
                                // (замерено 0.4 с на шаг).
                                val maxStep = controller.metrics.value.avgItemPx
                                    .coerceAtLeast(1f)
                                val step = (cmd.px - applied).coerceIn(-maxStep, maxStep)
                                if (step != 0f) {
                                    scrollBy(step)
                                    applied += step
                                }
                            }
                            is ScrubCommand.Stop -> {
                                // Финальная цель: докручиваем до неё (обычно
                                // мелкий шаг — список успевал идти вровень) и
                                // отпускаем мьютекс. Без этого ползунок
                                // откатывался бы назад при отпускании.
                                val rest = cmd.px - applied
                                if (rest != 0f) scrollBy(rest)
                                return@scroll
                            }
                            is ScrubCommand.Start -> Unit
                        }
                        cmd = commands.receive()
                    }
                }
            }
        }

        awaitPointerEventScope {
            while (true) {
                val event0 = awaitPointerEvent(PointerEventPass.Initial)
                val down = event0.changes.firstOrNull { it.changedToDown() } ?: continue
                val downX = down.position.x
                val downY = down.position.y
                val inMargin = downX >= listWidthPx - marginPx
                val g0 = controller.thumb.value
                val nearThumb = downY >= g0.offsetPx - tolerancePx &&
                    downY <= g0.offsetPx + g0.thumbHeightPx + tolerancePx
                val mayScrub = inMargin || nearThumb
                // Положение ползунка в момент касания; скраб идёт 1:1 от него,
                // поэтому смещение пальца внутри ползунка не важно.
                val grabOffsetPx = g0.offsetPx
                var lastY = downY
                var totalDeltaY = 0f
                var scrubbed = false
                var lastTargetPx = 0f

                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val delta = change.position.y - lastY
                        if (delta == 0f) continue
                        lastY = change.position.y
                        totalDeltaY += delta
                        // До срабатывания touch slop жест не перехватываем:
                        // мелкая дрожь пальца должна читаться как тап.
                        if (abs(totalDeltaY) <= viewConfiguration.touchSlop) continue
                        // Не рядом с ползунком — жест остаётся списку.
                        if (!mayScrub) continue
                        // consume в Initial-проходе отменяет перетаскивание
                        // списка: событие увидит и скролл, но уже «съеденным».
                        change.consume()
                        if (!scrubbed) {
                            // Старт скраба: воркер берёт мьютекс и сразу
                            // отменяет флинг списка.
                            scrubbed = true
                            commands.trySend(ScrubCommand.Start)
                        }
                        controller.isDragging = true
                        // ponytail: ползунок упирается в дорожку, а список
                        // прокручивается только на сырое смещение, поэтому за
                        // край ползунок замирает, а список стоит — при отпускании
                        // он догоняет список. Похоже на резинку; чтобы сделать
                        // точно, нужен съеденный дельтой scrollBy.
                        controller.dragOffsetPx = (grabOffsetPx + totalDeltaY)
                            .roundToInt()
                            .coerceIn(0, controller.thumb.value.scrollableTrackPx)
                        val m = controller.metrics.value
                        val totalScrollable = (m.totalScrollPx - m.viewportPx).coerceAtLeast(0f)
                        val pxPerTrackPx = totalScrollable / controller.thumb.value.scrollableTrackPx
                        if (pxPerTrackPx > 0f) {
                            // Накопленная дельта с начала скраба — цель, к
                            // которой воркер докручивает.
                            lastTargetPx = totalDeltaY * pxPerTrackPx
                            commands.trySend(ScrubCommand.Delta(lastTargetPx))
                        }
                    }
                } finally {
                    controller.isDragging = false
                    if (scrubbed) {
                        commands.trySend(ScrubCommand.Stop(lastTargetPx))
                    }
                    // Ползунок снова следует за списком — иначе он навсегда
                    // остался бы там, где палец его отпустил.
                    controller.dragOffsetPx = null
                }

                // Тап без перетаскивания: ставим ползунок под палец. Только в
                // свободном поле — тап по карточке должен открыть карточку, а не
                // прыгнуть по списку.
                if (!scrubbed && abs(totalDeltaY) <= viewConfiguration.touchSlop && inMargin) {
                    val g = controller.thumb.value
                    val m = controller.metrics.value
                    val ratio = ((downY - g.thumbHeightPx / 2f) / g.scrollableTrackPx)
                        .coerceIn(0f, 1f)
                    // Прыгаем к элементу, а не на пиксели: scrollBy на всю
                    // высоту контента (десятки тысяч пикселей) заставляет
                    // LazyColumn перемерить сотни элементов — замерено 3.8 с
                    // на прыжок. requestScrollToItem прыгает сразу и ещё и
                    // отменяет флинг списка.
                    val total = m.totalItemsCount
                    val targetIndex = (ratio * total).toInt().coerceIn(0, (total - 1).coerceAtLeast(0))
                    controller.listState.requestScrollToItem(targetIndex)
                }
            }
        }
    }
}

/** Команды воркеру скролла. */
private sealed interface ScrubCommand {
    /** Начало скраба: воркер берёт мьютекс и отменяет флинг. */
    data object Start : ScrubCommand
    /** Сдвиг контента на [px]. */
    data class Delta(val px: Float) : ScrubCommand
    /** Конец скраба: [px] — финальная цель, до неё воркер докручивает. */
    data class Stop(val px: Float) : ScrubCommand
}

/**
 * Material 3-стилизованная вертикальная полоса прокрутки.
 *
 * Рисует полосу в правом поле экрана: сама полоса — [width] (8dp), зона
 * захвата шире и живёт в модификаторе списка ([scrollbarGestures]), так что
 * полоса не залезает на контент и поля списка остаются симметричными.
 *
 * Геометрия берётся из [LazyListState]:
 * - Позиция скролла = `avgItemPx * firstVisibleItemIndex + firstVisibleItemScrollOffset`
 *   (формула, которую `LazyListState.calculateScrollOffset` использует для
 *   своего ScrollIndicatorState).
 * - `info.viewportStartOffset` в Foundation 1.7.x — *не* позиция скролла:
 *   он жёстко равен `-beforeContentPadding`. Нужны `firstVisibleItemIndex` /
 *   `firstVisibleItemScrollOffset`.
 * - Общей высоты контента у LazyListState нет, её приходится оценивать;
 *   см. `ponytail:` в [AvgItemHeight].
 *
 * Производительность: покадровые величины живут в производном состоянии,
 * которое читается **только из draw-фазы** (ползунок рисуется через
 * `drawBehind` и двигается через `graphicsLayer`). Прокрутка перерисовывает
 * полосу, не вызывая ни рекомпозиции, ни перевёрстки. Раньше те же данные
 * читались в теле composable, и каждый тик скролла инвалидировал
 * композицию с layout.
 *
 * ponytail: `rememberScrollbarState` / `Modifier.verticalScrollbar` из
 * Compose Foundation в нашей версии 1.7.2 ещё нет — проверено: в артефакте
 * `foundation` 1.7.2 нет ни одного класса `Scrollbar`. Следующий за этим
 * стандартный вариант — `androidx.compose.material` (M1), который мы
 * сознательно не тянем. Поэтому полоса живёт здесь.
 */
@Composable
fun VerticalListScrollbar(
    controller: ScrollbarController,
    modifier: Modifier = Modifier,
    width: Dp = 8.dp,
) {
    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)
    val thumbColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
    val dragColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(width)
            .onSizeChanged { controller.trackHeightPx = it.height }
            // Список уже объявляет свою позицию скролла; лишний узел
            // полосы для TalkBack — шум.
            .clearAndSetSemantics { },
    ) {
        // Дорожка — видимая полоса 8dp в правом поле экрана.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(trackColor, RoundedCornerShape(width / 2)),
        )
        // Ползунок. Чисто рисуется: жестами занимается список.
        //
        // Положение и размер применяются в DRAW-фазе: `graphicsLayer`
        // двигает бокс, `drawBehind` рисует скруглённую полосу той высоты,
        // которую дают метрики. Здесь ничего не меняет layout, поэтому
        // прокрутка стоит одну перерисовку узла и ни одной рекомпозиции.
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .width(width)
                .fillMaxHeight()
                .graphicsLayer { translationY = controller.thumb.value.offsetPx.toFloat() }
                .drawBehind {
                    val g = controller.thumb.value
                    drawRoundRect(
                        color = if (controller.isDragging) dragColor else thumbColor,
                        topLeft = Offset.Zero,
                        size = Size(size.width, g.thumbHeightPx.toFloat()),
                        cornerRadius = CornerRadius(size.width / 2f),
                    )
                },
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
            totalItemsCount = totalItemsCount,
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
        totalItemsCount = totalItemsCount,
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
    val totalItemsCount: Int,
    val hasOverflow: Boolean,
)

/** Pixel geometry of the thumb for the current track size. */
internal data class ThumbGeometry(
    val thumbHeightPx: Int,
    val scrollableTrackPx: Int,
    val offsetPx: Int,
)

/**
 * Mean height of the visible items, frozen between shape changes.
 *
 * The key is (item count, viewport height, tallest visible item) — the same
 * triple the previous `remember` call used. The value is re-derived only when
 * that key changes, so the thumb doesn't breathe while the visible mix
 * shifts under a fling.
 */
private class AvgItemHeight {
    private var key: Triple<Int, Int, Int?>? = null
    private var value = 0f

    fun update(info: LazyListLayoutInfo, viewportPx: Float): Float {
        val newKey = Triple(
            info.totalItemsCount,
            viewportPx.roundToInt(),
            info.visibleItemsInfo.maxOfOrNull { it.size },
        )
        if (newKey != key) {
            key = newKey
            val visible = info.visibleItemsInfo
            val measured = if (visible.isEmpty()) {
                0f
            } else {
                visible.sumOf { it.size }.toFloat() / visible.size
            }
            // Пустой список виденмых элементов — это кадр между
            // перестановками, а не «элементы нулевой высоты»: обнуление
            // оценки растягивало ползунок на всю дорожку.
            if (measured > 0f || value == 0f) value = measured
        }
        return value
    }
}
