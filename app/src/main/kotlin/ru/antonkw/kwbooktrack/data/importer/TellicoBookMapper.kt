package ru.antonkw.kwbooktrack.data.importer

import org.apache.commons.csv.CSVRecord
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

data class MappedBook(
    val book: BookEntity,
    val quotes: List<String>,
    val review: String?,
)

@Singleton
class TellicoBookMapper @Inject constructor() {

    fun map(record: CSVRecord): MappedBook? {
        val title = field(record, "Заголовок").orEmpty()
        val author = field(record, "Автор").orEmpty()
        if (title.isEmpty() && author.isEmpty()) return null

        val now = System.currentTimeMillis()
        val book = BookEntity(
            title = title.ifEmpty { "(без названия)" },
            subtitle = nullable(record, "Подзаголовок"),
            author = author.ifEmpty { "(автор неизвестен)" },
            isbn13 = normalizeIsbn(field(record, "ISBN#")),
            pageCount = field(record, "Количество страниц")?.toIntOrNull(),
            language = normalizeLanguage(field(record, "Язык")),
            genre = field(record, "Жанр")?.takeUnless { it.isEmpty() },
            publisher = field(record, "Издательство")?.takeUnless { it.isEmpty() },
            year = field(record, "Год издания")?.toIntOrNull(),
            coverPath = field(record, "Лицевая сторона обложки")?.takeUnless { it.isEmpty() },
            rating = field(record, "Рейтинг")?.toIntOrNull()?.takeIf { it in 1..5 },
            status = if (isRead(record)) BookStatus.FINISHED else BookStatus.WISHLIST,
            finishedAt = parseDate(field(record, "Дата прочтения")),
            purchaseDate = parseDate(field(record, "Дата покупки")),
            purchasePrice = parsePrice(field(record, "Цена покупки")),
            // v5 — extended Tellico fields. nullable() / takeUnless keeps the
            // import idempotent: an empty CSV cell doesn't overwrite existing
            // values (per #10/mergeWith semantics) — but here we're inserting
            // a new row, so empty strings simply become NULL.
            binding = nullable(record, "Переплёт"),
            purchasePlace = nullable(record, "Место покупки"),
            series = nullable(record, "Серия"),
            seriesNumber = nullable(record, "Номер серии"),
            condition = nullable(record, "Состояние"),
            translator = nullable(record, "Переводчик"),
            printYear = field(record, "Год сдачи в печать")?.toIntOrNull(),
            printPages = field(record, "Усл. печ. л.")?.toIntOrNull(),
            originalLanguage = nullable(record, "Язык оригинала"),
            originalTitle = nullable(record, "Оригинальное название"),
            annotation = nullable(record, "Аннотация"),
            isGift = parseBool(record, "Подарок"),
            isLent = parseBool(record, "Одолжено"),
            isSold = parseBool(record, "Продано?"),
            createdAt = now,
            updatedAt = now,
            source = BookSource.TELLICO,
        )
        val quotes = parseQuotes(field(record, "Цитаты"))
        val review = field(record, "Рецензия")?.takeUnless { it.isBlank() }
        return MappedBook(book, quotes, review)
    }

    private fun parseQuotes(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split('\n', ';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun field(record: CSVRecord, name: String): String? =
        if (record.isMapped(name)) record.get(name)?.trim() else null

    /** Like [field], but converts blank strings to null so the DB column
     *  stays NULL instead of carrying an empty-string marker. */
    private fun nullable(record: CSVRecord, name: String): String? =
        field(record, name)?.takeUnless { it.isBlank() }

    /** Tellico stores booleans as localized strings ("Да", "Нет", "true"). */
    private fun parseBool(record: CSVRecord, name: String): Boolean {
        val raw = field(record, name)?.lowercase() ?: return false
        return raw in setOf("true", "yes", "да", "1", "y")
    }

    private fun isRead(record: CSVRecord): Boolean {
        val flag = field(record, "Прочитано")?.lowercase() ?: return false
        return flag in setOf("true", "yes", "да", "1", "прочитано")
    }

    private fun normalizeIsbn(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val digits = raw.filter { it.isDigit() }
        return digits.takeIf { it.length == 13 }
    }

    private fun normalizeLanguage(raw: String?): String {
        if (raw.isNullOrBlank()) return "ru"
        val first = raw.substringBefore(';').trim().lowercase()
        return when (first) {
            "ru", "русский" -> "ru"
            "en", "английский" -> "en"
            else -> first.take(2)
        }
    }

    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val candidates = listOf("dd.MM.yyyy", "yyyy-MM-dd")
        for (pattern in candidates) {
            try {
                return LocalDate.parse(raw.trim(), DateTimeFormatter.ofPattern(pattern))
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            } catch (_: Exception) {
                // try next
            }
        }
        return null
    }

    private fun parsePrice(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        val normalized = raw.replace(',', '.').replace(Regex("[^0-9.\\-]"), "")
        if (normalized.isBlank()) return null
        return normalized.toDoubleOrNull()
    }
}
