package ru.antonkw.kwbooktrack.data.importer

import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import ru.antonkw.kwbooktrack.data.snapshot.NoteSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.PhotoSnapshot
import ru.antonkw.kwbooktrack.data.tellico.TellicoFields
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import ru.antonkw.kwbooktrack.data.text.HtmlToMarkdown
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField

/**
 * Общие куски разбора записи Tellico — то, что одинаково у книг и фильмов:
 * даты, флаги, теги, цитаты, заметки, рецензия, фото.
 *
 * Вынесено из [TellicoBookMapper], когда появились фильмы: у них те же самые
 * поля `теги`, `цитаты`, `заметки`, `рецензия` и `фото-заметок` (мы объявляем
 * их в коллекции фильмов при экспорте), и копировать разбор значило бы
 * держать две копии, которые разъедутся.
 */
internal object TellicoCommon {

    /** Значение поля записи: пустое и состоящее из пробелов — как отсутствующее. */
    fun f(record: TellicoRecord, name: String): String? =
        record.fields[name]?.trim()?.takeUnless { it.isBlank() }

    /**
     * Флаги Tellico: «true», «yes», «да», «1», «y» — и слово самого поля
     * («Скачан»): в выгрузке Tellico значение флага — его заголовок.
     */
    fun isTrue(raw: String?, vararg words: String): Boolean {
        val value = raw?.lowercase() ?: return false
        return value in TRUE_VALUES || words.any { it == value }
    }

    /**
     * Даты Tellico: `yyyy-MM-dd`, `yyyy-MM`, `yyyy` и `dd.MM.yyyy`.
     *
     * Неполная дата дополняется: год → 1 января, год+месяц → 1-е число.
     */
    fun parseDate(raw: String?): Long? {
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

    /** Теги — многозначное поле Tellico, значения склеены `"; "`. */
    fun tags(record: TellicoRecord): List<String> =
        f(record, "теги")
            ?.split("; ")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /**
     * «Ключевые слова» — встроенное поле Tellico, в приложении для него нет
     * колонки. Без `extra_fields` они бы просто потерялись.
     */
    fun keywordExtra(record: TellicoRecord): Map<String, ExtraField> {
        val value = f(record, "keyword") ?: return emptyMap()
        return mapOf("keyword" to ExtraField(TellicoFields.TITLE.getValue("keyword"), value))
    }

    /**
     * Цитаты: строки через `\n`, страница — префикс `цифры:` или `цифры::`.
     * Строка из одних цифр — не цитата, а «страница без текста» — пропускаем.
     *
     * Сначала HTML → markdown: в выгрузке Tellico цитаты разделяются тегом
     * `<br/>`, без конвертации они слиплись бы в одну заметку.
     */
    fun quoteNotes(record: TellicoRecord): List<NoteSnapshot> {
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
    fun reviewNote(record: TellicoRecord): List<NoteSnapshot> {
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
    fun noteNotes(record: TellicoRecord): List<NoteSnapshot> {
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

    /** Фото заметок: `файл::индекс::дата`. */
    fun photos(record: TellicoRecord): List<PhotoSnapshot> {
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

    /** Разворачивает экранирование Tellico: `\\` → `\`, `\n` → перевод строки, `\r` → CR. */
    fun unescape(raw: String): String {
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

    private val TRUE_VALUES = TellicoFields.TRUE_VALUES
    private val DATE_PATTERNS = listOf("yyyy-MM-dd", "yyyy-MM", "yyyy", "dd.MM.yyyy")
    /** Страница цитаты: `1:текст` или `1::текст`. */
    private val QUOTE_PAGE = Regex("(\\d+)(::?)(.*)")
}
