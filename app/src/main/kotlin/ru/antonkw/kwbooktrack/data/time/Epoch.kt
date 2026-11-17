package ru.antonkw.kwbooktrack.data.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Два разных вида времени в приложении — и путать их нельзя.
 *
 * **Дата** (прочитал, купил, посмотрел) — это не момент времени, а календарный
 * день пользователя. Хранится как epoch-мс в **полночь UTC** (`toDayMs`):
 * полночь локальной зоны съезжала на день при переезде и при смене настроек
 * телефона, а дата «15 марта» должна оставаться 15 марта.
 *
 * **Момент времени** (когда создан снимок, когда началась сессия чтения) —
 * это настоящий instant, он хранится как есть и показывается в зоне
 * устройства (`toLocalDateTime`).
 *
 * Touching a date-bearing column? Use these and nothing else:
 * - дата (без времени) → [toDayMs] / [toDayDate];
 * - момент времени → [toLocalDateTime] / [toLocalDay].
 */
private const val MILLIS_PER_DAY = 86_400_000L

/** Дата → epoch-мс в полночь UTC. Не зависит от часового пояса устройства. */
fun LocalDate.toDayMs(): Long = toEpochDay() * MILLIS_PER_DAY

/** Epoch-мс в полночь UTC → дата. */
fun Long.toDayDate(): LocalDate = LocalDate.ofEpochDay(Math.floorDiv(this, MILLIS_PER_DAY))

/**
 * Начало суток устройства — граница дня для настоящих моментов времени.
 *
 * Только для сравнения «момент попал в этот день»: сессия чтения в 23:30
 * принадлежит этому дню, а не следующему.
 */
fun LocalDate.toLocalDayStartMs(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** День настоящего момента времени — в часовом поясе устройства. */
fun Long.toLocalDay(): LocalDate = toLocalDateTime().toLocalDate()

/** Момент времени в зоне устройства. */
fun Long.toLocalDateTime(): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDateTime()
