package ru.antonkw.kwbooktrack.data.importer

import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.snapshot.BookSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import ru.antonkw.kwbooktrack.data.snapshot.NoteSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.PhotoSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.SessionSnapshot
import ru.antonkw.kwbooktrack.data.tellico.TellicoFields
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import ru.antonkw.kwbooktrack.data.text.HtmlToMarkdown
import ru.antonkw.kwbooktrack.data.text.parseDecimal
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
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
        val title = f(record, "title").orEmpty()
        val author = f(record, "author").orEmpty()
        if (title.isEmpty() && author.isEmpty()) return null

        val now = System.currentTimeMillis()
        val book = BookEntity(
            // Без названия или автора книга в списке не опознаётся — подставляем заглушку.
            title = title.ifEmpty { "(без названия)" },
            subtitle = f(record, "subtitle"),
            author = author.ifEmpty { "(автор неизвестен)" },
            isbn13 = normalizeIsbn(f(record, "isbn")),
            pageCount = f(record, "pages")?.toIntOrNull(),
            language = normalizeLanguage(f(record, "language")),
            genre = f(record, "genre"),
            publisher = f(record, "publisher"),
            year = f(record, "pub_year")?.toIntOrNull(),
            coverPath = f(record, "cover"),
            rating = f(record, "rating")?.toIntOrNull()?.takeIf { it in 1..5 },
            status = status(record),
            startedAt = parseDate(f(record, "дата-начала")),
            finishedAt = parseDate(f(record, "дата-прочтения")),
            createdAt = parseDate(f(record, "cdate")) ?: now,
            updatedAt = parseDate(f(record, "mdate")) ?: now,
            source = MediaSource.TELLICO,
            purchaseDate = parseDate(f(record, "pur_date")),
            purchasePrice = parsePrice(f(record, "pur_price")),
            binding = f(record, "binding"),
            purchasePlace = f(record, "место-покупки"),
            series = f(record, "series"),
            seriesNumber = f(record, "series_num"),
            condition = f(record, "condition"),
            translator = f(record, "translator"),
            printYear = firstYear(f(record, "cr_year")),
            printPages = f(record, "усл.-печ.-л.")?.let { parseDecimal(it) },
            originalLanguage = f(record, "язык-оригинала"),
            originalTitle = f(record, "оригинальное-название"),
            annotation = f(record, "comments")?.let { HtmlToMarkdown.convert(it) }?.takeUnless { it.isBlank() },
            isGift = isTrue(f(record, "gift"), "подарок"),
            isLent = isTrue(f(record, "loaned"), "одолжено"),
            isSold = isTrue(f(record, "продано-new"), "продано"),
        )
        return BookSnapshot(
            book = book,
            notes = quoteNotes(record) + reviewNote(record) + noteNotes(record),
            sessions = sessions(record),
            tags = tags(record),
            photos = photos(record),
            // Чужие поля Tellico плюс то, для чего в приложении нет колонки:
            // «Ключевые слова» и нечисловая цена.
            extraFields = record.extra + keywordExtra(record) + priceExtra(record, book.purchasePrice),
        )
    }

    /**
     * «Ключевые слова» — встроенное поле Tellico, в приложении для него нет
     * колонки. Без `extra_fields` они бы просто потерялись.
     */
    private fun keywordExtra(record: TellicoRecord): Map<String, ExtraField> {
        val value = f(record, "keyword") ?: return emptyMap()
        return mapOf("keyword" to ExtraField(TellicoFields.TITLE.getValue("keyword"), value))
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
        val raw = f(record, "pur_price") ?: f(record, TellicoFields.TEXT_PRICE) ?: return emptyMap()
        return mapOf("pur_price" to ExtraField(TellicoFields.TITLE.getValue("pur_price"), raw))
    }

    private fun f(record: TellicoRecord, name: String): String? =
        record.fields[name]?.trim()?.takeUnless { it.isBlank() }

    /** Статус: три независимых флага Tellico, приоритет — брошено → прочитано → часть. */
    private fun status(record: TellicoRecord): BookStatus = when {
        isTrue(f(record, "брошено"), "брошено") -> BookStatus.ABANDONED
        isTrue(f(record, "read"), "прочитано") -> BookStatus.FINISHED
        isTrue(f(record, "прочитал-часть"), "прочитал-часть") -> BookStatus.READING
        else -> BookStatus.WISHLIST
    }

    /**
     * Флаги Tellico: «true», «yes», «да», «1», «y» — и слово самого поля
     * («Прочитано», «Подарок»): в выгрузке Tellico значение флага — его заголовок.
     */
    private fun isTrue(raw: String?, vararg words: String): Boolean {
        val value = raw?.lowercase() ?: return false
        return value in TRUE_VALUES || words.any { it == value }
    }

    /**
     * Цитаты: строки через `\n`, страница — префикс `цифры:` или `цифры::`.
     * Строка из одних цифр — не цитата, а «страница без текста» — пропускаем.
     *
     * Сначала HTML → markdown: в выгрузке Tellico цитаты разделяются тегом
     * `<br/>`, без конвертации они слиплись бы в одну заметку.
     */
    private fun quoteNotes(record: TellicoRecord): List<NoteSnapshot> {
        val raw = f(record, "цитаты") ?: return emptyList()
        return HtmlToMarkdown.convert(raw).split('\n').mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.all { it.isDigit() }) return@mapNotNull null
            val match = QUOTE_PAGE.matchEntire(trimmed)
            // Цитата без страницы: `::текст` — так пишет сам Tellico. Префикс надо
            // снять, иначе каждое «экспорт → импорт» добавлял бы к тексту ещё `::`.
            val body = unescape(
                when {
                    match != null -> match.groupValues[3]
                    trimmed.startsWith("::") -> trimmed.removePrefix("::")
                    else -> trimmed
                },
            ).trim()
            if (body.isEmpty()) return@mapNotNull null
            NoteSnapshot(
                kind = NoteKind.QUOTE,
                body = body,
                page = match?.groupValues?.get(1)?.toIntOrNull(),
            )
        }
    }

    /** Рецензия — одна заметка REVIEW. */
    private fun reviewNote(record: TellicoRecord): List<NoteSnapshot> {
        val body = f(record, "рецензия")
            ?.let { HtmlToMarkdown.convert(it) }
            ?.takeUnless { it.isBlank() }
            ?: return emptyList()
        return listOf(NoteSnapshot(kind = NoteKind.REVIEW, body = body))
    }

    /**
     * Заметки: `страница::текст::дата`.
     *
     * Текст заметки может содержать `::` (`std::string`, `Foo::bar`), поэтому
     * строка делится на **все** части: страница — первая, дата — последняя
     * (только если она пустая или разбирается как ISO-дата), текст — всё
     * между ними, склеенное обратно через `::`.
     */
    private fun noteNotes(record: TellicoRecord): List<NoteSnapshot> {
        val raw = f(record, "заметки") ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val parts = trimmed.split("::")
            // Строка без разделителей — это сам текст заметки, страницы в ней нет.
            if (parts.size == 1) {
                val body = unescape(parts[0]).trim()
                if (body.isEmpty()) return@mapNotNull null
                return@mapNotNull NoteSnapshot(kind = NoteKind.NOTE, body = body)
            }
            val last = parts.last().trim()
            val date = parseNoteDate(last)
            // Дата — последняя часть, только если она пустая или разбирается как дата.
            val hasDate = last.isEmpty() || date != null
            val text = if (hasDate) parts.subList(1, parts.size - 1) else parts.subList(1, parts.size)
            val body = unescape(text.joinToString("::")).trim()
            if (body.isEmpty()) return@mapNotNull null
            NoteSnapshot(
                kind = NoteKind.NOTE,
                body = body,
                page = parts[0].trim().toIntOrNull(),
                createdAt = if (hasDate) date else null,
            )
        }
    }

    /** Сессии: `начало::конец::стр.начала::стр.конца::длительность::заметка`. */
    private fun sessions(record: TellicoRecord): List<SessionSnapshot> {
        val raw = f(record, "сессии-чтения") ?: return emptyList()
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

    /** Теги — многозначное поле Tellico, значения склеены `"; "`. */
    private fun tags(record: TellicoRecord): List<String> =
        f(record, "теги")
            ?.split("; ")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** Фото заметок: `файл::индекс::дата`. */
    private fun photos(record: TellicoRecord): List<PhotoSnapshot> {
        val raw = f(record, "фото-заметок") ?: return emptyList()
        return raw.split('\n').mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null
            val parts = trimmed.split("::", limit = 3)
            val path = parts.getOrNull(0)?.trim().orEmpty()
            if (path.isEmpty()) return@mapNotNull null
            PhotoSnapshot(
                archivePath = path,
                noteIndex = parts.getOrNull(1)?.trim()?.toIntOrNull(),
                createdAt = parts.getOrNull(2)?.trim()?.toLongOrNull(),
            )
        }
    }

    /**
     * ISBN: 13 цифр (ISBN-13) или 10 знаков (ISBN-10, последний может быть «X»).
     *
     * Десятизначный ISBN — половина реальных книг: отбрасывать его нельзя.
     */
    private fun normalizeIsbn(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim().uppercase()
        val digits = text.filter { it.isDigit() }
        val hasX = 'X' in text
        return when {
            digits.length == 13 && !hasX -> digits
            digits.length == 10 && !hasX -> digits
            digits.length == 9 && hasX -> digits + "X"
            else -> null
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

    /**
     * Даты Tellico: `yyyy-MM-dd`, `yyyy-MM`, `yyyy` и `dd.MM.yyyy`.
     *
     * Неполная дата дополняется: год → 1 января, год+месяц → 1-е число.
     */
    private fun parseDate(raw: String?): Long? {
        if (raw.isNullOrBlank()) return null
        val text = raw.trim()
        for (pattern in DATE_PATTERNS) {
            val parsed = runCatching { DateTimeFormatter.ofPattern(pattern).parse(text) }.getOrNull() ?: continue
            val date = runCatching {
                LocalDate.of(
                    parsed.get(ChronoField.YEAR),
                    if (parsed.isSupported(ChronoField.MONTH_OF_YEAR)) parsed.get(ChronoField.MONTH_OF_YEAR) else 1,
                    if (parsed.isSupported(ChronoField.DAY_OF_MONTH)) parsed.get(ChronoField.DAY_OF_MONTH) else 1,
                )
            }.getOrNull() ?: continue
            return date.toEpochMs()
        }
        return null
    }

    /**
     * Дата в колонке «Дата» таблицы заметок: строго ISO (`yyyy-MM-dd`).
     *
     * Строго — потому, что последняя часть строки может быть обычным текстом
     * («версия 1999»): неразобранная дата не должна съедать текст заметки.
     */
    private fun parseNoteDate(raw: String): Long? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        return runCatching { LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE).toEpochMs() }.getOrNull()
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

    /** Разворачивает экранирование Tellico: `\\` → `\`, `\n` → перевод строки, `\r` → CR. */
    private fun unescape(raw: String): String {
        val out = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val char = raw[index]
            if (char == '\\' && index + 1 < raw.length) {
                when (raw[index + 1]) {
                    'n' -> { out.append('\n'); index += 2 }
                    'r' -> { out.append('\r'); index += 2 }
                    '\\' -> { out.append('\\'); index += 2 }
                    else -> { out.append(char); index++ }
                }
            } else {
                out.append(char)
                index++
            }
        }
        return out.toString()
    }

    private companion object {
        val TRUE_VALUES = TellicoFields.TRUE_VALUES
        val DATE_PATTERNS = listOf("yyyy-MM-dd", "yyyy-MM", "yyyy", "dd.MM.yyyy")
        val FIRST_NUMBER = Regex("\\d+")
        /** Страница цитаты: `1:текст` или `1::текст`. */
        val QUOTE_PAGE = Regex("(\\d+)(::?)(.*)")
        /** Валюта в цене: «275 ₽», «275 руб.», «275р» — число без них. */
        val PRICE_NOISE = Regex("[\\s₽\\$]|руб\\.?|RUB|USD|EUR|р\\b")
    }
}
