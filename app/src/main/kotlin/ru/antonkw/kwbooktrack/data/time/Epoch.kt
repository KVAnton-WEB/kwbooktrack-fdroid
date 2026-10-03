package ru.antonkw.kwbooktrack.data.time

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * All date-only fields (started/finished/purchase dates, note timestamps, etc.)
 * are stored as epoch millis anchored at **midnight in the device's local zone**
 * — not UTC. The user enters, sees, and filters by local time; the storage
 * layer doesn't decide what "Jan 15" means for them. Internal storage is still
 * epoch ms (cheaper than ISO strings, easy to compare), so these helpers are
 * the only place that pins down the encoding.
 *
 * Touching a date-bearing column? Use these and nothing else.
 */
fun LocalDate.toEpochMs(): Long =
    atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

fun Long.toLocalDate(): LocalDate =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

fun Long.toLocalDateTime(): LocalDateTime =
    Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDateTime()