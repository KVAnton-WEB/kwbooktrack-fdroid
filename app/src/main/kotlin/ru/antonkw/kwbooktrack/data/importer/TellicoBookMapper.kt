package ru.antonkw.kwbooktrack.data.importer

import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.snapshot.BookSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import ru.antonkw.kwbooktrack.data.snapshot.SessionSnapshot
import ru.antonkw.kwbooktrack.data.text.HtmlToMarkdown
import ru.antonkw.kwbooktrack.data.tellico.TellicoFields
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import ru.antonkw.kwbooktrack.data.text.Isbn
import ru.antonkw.kwbooktrack.data.text.parseDecimal
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Маппер Tellico: [TellicoRecord] → [BookSnapshot].
 *
 * Ключи полей — **внутренние имена Tellico** (`title`, `место-покупки`), а не
 * русские заголовки: именно они лежат в `tellico.xml` и в `.tc`.
 *
 * Табличные поля Tellico — строки через `\n`, колонки через `::`:
 * цитаты — `страница::текст`, заметки — `страница::текст::дата`,
 * сессии — 6 колонок, фото — `файл::индекс::дата`.
 */
@Singleton
class TellicoBookMapper @Inject constructor() {

    fun map(record: TellicoRecord): BookSnapshot? {
        val title = TellicoCommon.f(record, "title").orEmpty()
        val author = TellicoCommon.f(record, "author").orEmpty()
        if (title.isEmpty() && author.isEmpty()) return null

        val now = System.currentTimeMillis()
        val book = BookEntity(
            // Без названия или автора книга в списке не опознаётся — подставляем заглушку.
            title = title.ifEmpty { "(без названия)" },
            subtitle = TellicoCommon.f(record, "subtitle"),
            author = author.ifEmpty { "(автор неизвестен)" },
            // ISBN приводится к 13 цифрам: десятизначный из Tellico иначе лежал
            // бы рядом с тем же тринадцатизначным номером, введённым вручную.
            isbn13 = Isbn.normalize(TellicoCommon.f(record, "isbn")),
            pageCount = TellicoCommon.f(record, "pages")?.toIntOrNull(),
            language = normalizeLanguage(TellicoCommon.f(record, "language")),
            genre = TellicoCommon.f(record, "genre"),
            publisher = TellicoCommon.f(record, "publisher"),
            year = TellicoCommon.f(record, "pub_year")?.toIntOrNull(),
            coverPath = TellicoCommon.f(record, "cover"),
            rating = TellicoCommon.f(record, "rating")?.toIntOrNull()?.takeIf { it in 1..5 },
            status = status(record),
            startedAt = TellicoCommon.parseDate(TellicoCommon.f(record, "дата-начала")),
            finishedAt = TellicoCommon.parseDate(TellicoCommon.f(record, "дата-прочтения")),
            createdAt = TellicoCommon.parseInstant(TellicoCommon.f(record, "cdate")) ?: now,
            updatedAt = TellicoCommon.parseInstant(TellicoCommon.f(record, "mdate")) ?: now,
            source = MediaSource.TELLICO,
            purchaseDate = TellicoCommon.parseDate(TellicoCommon.f(record, "pur_date")),
            purchasePrice = parsePrice(TellicoCommon.f(record, "pur_price")),
            binding = TellicoCommon.f(record, "binding"),
            purchasePlace = TellicoCommon.f(record, "место-покупки"),
            series = TellicoCommon.f(record, "series"),
            seriesNumber = TellicoCommon.f(record, "series_num"),
            condition = TellicoCommon.f(record, "condition"),
            translator = TellicoCommon.f(record, "translator"),
            printYear = firstYear(TellicoCommon.f(record, "cr_year")),
            printPages = TellicoCommon.f(record, "усл.-печ.-л.")?.let { parseDecimal(it) },
            originalLanguage = TellicoCommon.f(record, "язык-оригинала"),
            originalTitle = TellicoCommon.f(record, "оригинальное-название"),
            annotation = TellicoCommon.f(record, "comments")?.let { HtmlToMarkdown.convert(it) }?.takeUnless { it.isBlank() },
            isGift = TellicoCommon.isTrue(TellicoCommon.f(record, "gift"), "подарок"),
            isLent = TellicoCommon.isTrue(TellicoCommon.f(record, "loaned"), "одолжено"),
            isSold = TellicoCommon.isTrue(TellicoCommon.f(record, "продано-new"), "продано"),
        )
        return BookSnapshot(
            book = book,
            notes = TellicoCommon.quoteNotes(record) + TellicoCommon.reviewNote(record) + TellicoCommon.noteNotes(record),
            sessions = sessions(record),
            tags = TellicoCommon.tags(record),
            photos = TellicoCommon.photos(record),
            // Чужие поля Tellico плюс то, для чего в приложении нет колонки:
            // «Ключевые слова» и нечисловая цена.
            extraFields = record.extra + TellicoCommon.keywordExtra(record) + priceExtra(record, book.purchasePrice),
        )
    }

    /**
     * Цена, которая не число («по подписке»), — в `extra_fields`: иначе
     * пользовательская пометка пропала бы.
     *
     * Текстовая цена приходит и из поля Tellico [TellicoFields.TEXT_PRICE] —
     * туда её пишет наш же экспорт (в `pur_price` Tellico текст не покажет).
     */
    private fun priceExtra(record: TellicoRecord, price: Double?): Map<String, ExtraField> {
        if (price != null) return emptyMap()
        val raw = TellicoCommon.f(record, "pur_price") ?: TellicoCommon.f(record, TellicoFields.TEXT_PRICE) ?: return emptyMap()
        return mapOf("pur_price" to ExtraField(TellicoFields.TITLE.getValue("pur_price"), raw))
    }

    /** Статус: три независимых флага Tellico, приоритет — брошено → прочитано → часть. */
    private fun status(record: TellicoRecord): BookStatus = when {
        TellicoCommon.isTrue(TellicoCommon.f(record, "брошено"), "брошено") -> BookStatus.ABANDONED
        TellicoCommon.isTrue(TellicoCommon.f(record, "read"), "прочитано") -> BookStatus.FINISHED
        TellicoCommon.isTrue(TellicoCommon.f(record, "прочитал-часть"), "прочитал-часть") -> BookStatus.READING
        else -> BookStatus.WISHLIST
    }

    /** Сессии: `начало::конец::стр.начала::стр.конца::длительность::заметка`. */
    private fun sessions(record: TellicoRecord): List<SessionSnapshot> {
        val raw = TellicoCommon.f(record, "сессии-чтения") ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val parts = trimmed.split("::", limit = 6)
            val startedAt = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: return@mapNotNull null
            SessionSnapshot(
                startedAt = startedAt,
                endedAt = parts.getOrNull(1)?.trim()?.toLongOrNull(),
                startPage = parts.getOrNull(2)?.trim()?.toIntOrNull(),
                endPage = parts.getOrNull(3)?.trim()?.toIntOrNull(),
                durationSec = parts.getOrNull(4)?.trim()?.toLongOrNull(),
                note = parts.getOrNull(5)?.trim()?.takeUnless { it.isEmpty() },
            )
        }
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

    /** «Год сдачи в печать» бывает списком («1984; 1986; 1988») — берём первый год. */
    private fun firstYear(raw: String?): Int? =
        raw?.let { FIRST_NUMBER.find(it)?.value?.toIntOrNull() }

    /**
     * Цена Tellico: число с разделителями («275», «195,44», «275 ₽»).
     *
     * Цена с буквами — не цена: «4 книга бесплатно» не число, и превращать
     * его в 4.0 нельзя — пометка потерялась бы. Такие значения остаются
     * текстом (см. [priceExtra]).
     */
    private fun parsePrice(raw: String?): Double? {
        if (raw.isNullOrBlank()) return null
        // Валюта и пробелы — не часть числа; остальные буквы — значит, это не цена.
        val cleaned = raw.trim().replace(PRICE_NOISE, "")
        if (cleaned.any { !it.isDigit() && it != '.' && it != ',' && it != '-' && it != '+' }) return null
        val normalized = cleaned.replace(',', '.')
        if (normalized.isBlank() || normalized == "-" || normalized == ".") return null
        return normalized.toDoubleOrNull()
    }

    private companion object {
        val FIRST_NUMBER = Regex("\\d+")
        /** Валюта в цене: «275 ₽», «275 руб.», «275р» — число без них. */
        val PRICE_NOISE = Regex("[\\s₽\\$]|руб\\.?|RUB|USD|EUR|р\\b")
    }
}
