package ru.antonkw.kwbooktrack.data.bookmory

import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class BookmoryBook(
    val title: String,
    val authors: List<String>,
    val translators: List<String>,
    val illustrators: List<String>,
    val narrators: List<String>,
    val publisher: String?,
    val publicationDate: String?,
    val language: String?,
    val isbn: String?,
    val bindingType: String?,
    val totalPages: Int?,
    val tags: List<String>,
    val collections: List<String>,
    val status: BookmoryStatus,
    val sessions: List<BookmorySession>,
    val notes: List<BookmoryNote>,
)

data class BookmorySession(
    val startedAt: LocalDate?,
    val endedAt: LocalDate?,
    val rating: Float?,
    val comment: String?,
    val readingTimeMinutes: Int?,
)

data class BookmoryNote(
    val date: LocalDate?,
    val page: Int?,
    val kind: String,
    val content: String,
)

enum class BookmoryStatus { FINISHED, READING, WISHLIST, UNKNOWN }

class BookmoryParser {

    fun parse(content: String): BookmoryBook? {
        val title = extractField(content, "Название") ?: return null
        val authors = splitMulti(extractField(content, "Авторы"))
        val translators = splitMulti(extractField(content, "Переводчики"))
        val illustrators = splitMulti(extractField(content, "Иллюстраторы"))
        val narrators = splitMulti(extractField(content, "Чтецы"))
        val publisher = extractField(content, "Издательство")
        val publicationDate = extractField(content, "Дата публикации")
        val language = extractField(content, "Язык")
        val isbn = extractField(content, "ISBN")?.filter { it.isDigit() }?.takeIf { it.length == 13 }
        val bindingType = extractField(content, "Тип книги")
        val totalPages = extractField(content, "Всего страниц")?.let { regexPages.find(it)?.groupValues?.get(1)?.toIntOrNull() }
        val tags = extractField(content, "Используемые теги")
            ?.split(Regex("\\s+#"))
            ?.map { it.trim().trimStart('#') }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val collections = splitMulti(extractField(content, "Коллекции"))
        val status = parseStatus(extractField(content, "Статус"))

        val sessions = parseSessions(content)
        val notes = parseNotes(content)

        return BookmoryBook(
            title = title,
            authors = authors,
            translators = translators,
            illustrators = illustrators,
            narrators = narrators,
            publisher = publisher,
            publicationDate = publicationDate,
            language = language,
            isbn = isbn,
            bindingType = bindingType,
            totalPages = totalPages,
            tags = tags,
            collections = collections,
            status = status,
            sessions = sessions,
            notes = notes,
        )
    }

    private fun extractField(content: String, label: String): String? {
        // Match "- Label: value" (possibly multiline via " " continuation)
        val regex = Regex("^- $label:\\s*(.+)$", RegexOption.MULTILINE)
        val match = regex.find(content) ?: return null
        return match.groupValues[1].trim().takeIf { it.isNotEmpty() && it != "-" }
    }

    private fun splitMulti(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",", ";")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun parseStatus(raw: String?): BookmoryStatus {
        if (raw.isNullOrBlank()) return BookmoryStatus.UNKNOWN
        return when {
            raw.contains("Дочитано") -> BookmoryStatus.FINISHED
            raw.contains("Читаю") -> BookmoryStatus.READING
            raw.contains("Хочу") -> BookmoryStatus.WISHLIST
            else -> BookmoryStatus.UNKNOWN
        }
    }

    private fun parseSessions(content: String): List<BookmorySession> {
        val sessionsSection = content.substringAfter("Журнал чтения", "")
        if (sessionsSection.isEmpty()) return emptyList()
        val blocks = sessionsSection.split(Regex("^### ", RegexOption.MULTILINE))
        return blocks.mapNotNull { block ->
            val period = extractField(block, "Период чтения")
            val (startedAt, endedAt) = parsePeriod(period)
            val rating = extractField(block, "Оценка")?.toFloatOrNull()
            val comment = extractField(block, "Комментарий")
            val readingTimeMinutes = extractField(block, "Время чтения")?.let { parseDuration(it) }
            if (startedAt == null && rating == null && readingTimeMinutes == null && comment.isNullOrBlank()) {
                null
            } else {
                BookmorySession(startedAt, endedAt, rating, comment, readingTimeMinutes)
            }
        }
    }

    private fun parsePeriod(raw: String?): Pair<LocalDate?, LocalDate?> {
        if (raw.isNullOrBlank()) return null to null
        val parts = raw.split("~")
        if (parts.size != 2) return parseDate(parts[0].trim()) to null
        return parseDate(parts[0].trim()) to parseDate(parts[1].trim())
    }

    private fun parseDate(raw: String): LocalDate? {
        if (raw.isBlank()) return null
        for (pattern in listOf("dd.MM.yyyy", "yyyy-MM-dd")) {
            try {
                return LocalDate.parse(raw, DateTimeFormatter.ofPattern(pattern))
            } catch (_: Exception) {
                // try next
            }
        }
        return null
    }

    private fun parseDuration(raw: String): Int? {
        val match = Regex("(\\d+):(\\d+)").find(raw) ?: return null
        val hours = match.groupValues[1].toIntOrNull() ?: 0
        val minutes = match.groupValues[2].toIntOrNull() ?: 0
        return hours * 60 + minutes
    }

    private fun parseNotes(content: String): List<BookmoryNote> {
        val notesSection = content.substringAfter("Список заметок", "")
        if (notesSection.isEmpty()) return emptyList()
        val blocks = notesSection.split(Regex("^#### ", RegexOption.MULTILINE))
        return blocks.mapNotNull { block ->
            val date = parseDate(extractField(block, "Дата") ?: "")
            val page = extractField(block, "Страница")?.let { regexPages.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            val kind = extractField(block, "Тип заметки") ?: ""
            val contentRaw = extractField(block, "Содержание") ?: return@mapNotNull null
            if (contentRaw.isBlank()) null else BookmoryNote(date, page, kind, contentRaw)
        }
    }

    companion object {
        private val regexPages = Regex("с\\.\\s*(\\d+)")
    }
}
