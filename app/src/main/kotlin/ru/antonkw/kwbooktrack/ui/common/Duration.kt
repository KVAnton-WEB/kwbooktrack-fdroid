package ru.antonkw.kwbooktrack.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ru.antonkw.kwbooktrack.R

/** Как показана длительность: только минуты, только часы или и то и другое. */
enum class DurationKind {
    MINUTES_ONLY,
    HOURS_ONLY,
    HOURS_AND_MINUTES,
}

/** Длительность, разложенная на часы и минуты. */
data class DurationParts(
    val hours: Int,
    val minutes: Int,
    val kind: DurationKind,
)

/**
 * Минуты → часы и минуты: 132 → 2 ч 12 мин.
 *
 * Обратная деление: целые часы и остаток минут.
 */
fun splitDuration(totalMinutes: Int): DurationParts {
    val safe = totalMinutes.coerceAtLeast(0)
    val hours = safe / 60
    val minutes = safe % 60
    val kind = when {
        hours <= 0 -> DurationKind.MINUTES_ONLY
        minutes <= 0 -> DurationKind.HOURS_ONLY
        else -> DurationKind.HOURS_AND_MINUTES
    }
    return DurationParts(hours, minutes, kind)
}

/**
 * Длительность фильма человекочитаемо: «2 ч 12 мин» вместо «132».
 *
 * Склейка — строкой ресурса: так её переводят.
 */
@Composable
fun formatDuration(minutes: Int): String {
    val parts = splitDuration(minutes)
    return when (parts.kind) {
        DurationKind.MINUTES_ONLY -> stringResource(R.string.duration_minutes, parts.minutes)
        DurationKind.HOURS_ONLY -> stringResource(R.string.duration_hours, parts.hours)
        DurationKind.HOURS_AND_MINUTES ->
            stringResource(R.string.duration_hours_minutes, parts.hours, parts.minutes)
    }
}

/**
 * То же коротко — для узких ячеек календаря: «2ч12м».
 *
 * Полная форма в ячейку календаря не влезает: там седьмая часть экрана.
 */
@Composable
fun formatDurationCompact(minutes: Int): String {
    val parts = splitDuration(minutes)
    return when (parts.kind) {
        DurationKind.MINUTES_ONLY -> stringResource(R.string.duration_minutes_compact, parts.minutes)
        DurationKind.HOURS_ONLY -> stringResource(R.string.duration_hours_compact, parts.hours)
        DurationKind.HOURS_AND_MINUTES ->
            stringResource(R.string.duration_hours_minutes_compact, parts.hours, parts.minutes)
    }
}
