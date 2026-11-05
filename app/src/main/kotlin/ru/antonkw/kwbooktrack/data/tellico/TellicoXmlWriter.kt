package ru.antonkw.kwbooktrack.data.tellico

import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.snapshot.BookSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import ru.antonkw.kwbooktrack.data.snapshot.NoteSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.PhotoSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.SessionSnapshot
import ru.antonkw.kwbooktrack.data.text.formatDecimal
import ru.antonkw.kwbooktrack.data.time.toLocalDate

/** Одна запись каталога для экспорта. */
data class TcEntry(
    val snapshot: BookSnapshot,
    /** Имя файла обложки в архиве (`{md5}.{ext}`) — без пути `images/`. */
    val coverArchiveName: String? = null,
)

/**
 * Сборщик `tellico.xml` — родного формата Tellico (DTD v12).
 *
 * Правила, которые Tellico не прощает (потому и написаны руками):
 *  - многозначное поле — контейнер `<имя+s>` с вложенными `<имя>`; плоская запись
 *    читается как одно значение;
 *  - дата — `<поле calendar="gregorian">` с `year`/`month`/`day`;
 *  - флаг — только `true`, отсутствие = false;
 *  - `<field>` требует `category`, а `<images>` — хотя бы один `<image>`;
 *  - `<image>` — ровно `id` и `format`: без `width`/`height` обложка не показывается.
 */
object TellicoXmlWriter {

    /** Собрать содержимое tellico.xml по записям. */
    fun write(entries: List<TcEntry>): String {
        val xml = StringBuilder()
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append(
            "<!DOCTYPE tellico PUBLIC '-//Robby Stephenson/DTD Tellico V12.0//EN' " +
                "'http://periapsis.org/tellico/dtd/v12/tellico.dtd'>\n",
        )
        xml.append("<tellico xmlns=\"http://periapsis.org/tellico/\" syntaxVersion=\"12\">\n")
        xml.append(" <collection title=\"kwbooktrack\" type=\"2\">\n")
        writeFields(xml, entries)
        entries.forEachIndexed { index, entry -> writeEntry(xml, entry, index + 1) }
        writeImages(xml, entries)
        xml.append(" </collection>\n")
        xml.append("</tellico>\n")
        return xml.toString()
    }

    /**
     * `<fields>`: объявляем только свои и чужие поля — встроенные Tellico знает сам
     * (со своими типами), а объявление встроенного поля с другим типом его портит.
     */
    private fun writeFields(xml: StringBuilder, entries: List<TcEntry>) {
        xml.append("  <fields>\n")
        ownFields.forEach { writeField(xml, it) }
        foreignFields(entries).forEach { (name, extra) ->
            writeField(xml, FieldDef(name = name, title = extra.title, type = 1, category = "Общее"))
        }
        xml.append("  </fields>\n")
    }

    /** Чужие поля всех книг: имя → поле (по первому попавшемуся). */
    private fun foreignFields(entries: List<TcEntry>): Map<String, ExtraField> {
        val result = linkedMapOf<String, ExtraField>()
        for (entry in entries) {
            for ((name, extra) in entry.snapshot.extraFields) {
                if (!isSafeName(name)) continue
                result.putIfAbsent(name, extra)
            }
        }
        return result
    }

    private fun writeField(xml: StringBuilder, field: FieldDef) {
        xml.append(
            "   <field category=\"${escape(field.category)}\" description=\"\" flags=\"${field.flags}\" " +
                "format=\"${field.format}\" name=\"${escape(field.name)}\" " +
                "title=\"${escape(field.title)}\" type=\"${field.type}\"",
        )
        val columns = field.columns
        if (columns == null) {
            xml.append("/>\n")
            return
        }
        xml.append(">\n")
        xml.append("    <prop name=\"columns\">$columns</prop>\n")
        field.columnTitles.forEachIndexed { index, title ->
            xml.append("    <prop name=\"column${index + 1}\">${escape(title)}</prop>\n")
        }
        xml.append("   </field>\n")
    }

    private fun writeEntry(xml: StringBuilder, entry: TcEntry, id: Int) {
        val snapshot = entry.snapshot
        val book = snapshot.book
        xml.append("  <entry id=\"$id\">\n")

        // Встроенные поля Tellico.
        element(xml, "title", book.title)
        book.subtitle?.let { element(xml, "subtitle", it) }
        book.publisher?.let { element(xml, "publisher", it) }
        book.year?.let { element(xml, "pub_year", it.toString()) }
        book.isbn13?.let { element(xml, "isbn", it) }
        book.pageCount?.let { element(xml, "pages", it.toString()) }
        book.series?.let { element(xml, "series", it) }
        book.seriesNumber?.let { element(xml, "series_num", it) }
        book.condition?.let { element(xml, "condition", it) }
        book.binding?.let { element(xml, "binding", it) }
        book.purchasePrice?.let { element(xml, "pur_price", formatDecimal(it)) }
        book.purchaseDate?.let { date(xml, "pur_date", it) }
        // У Tellico рейтинг 1..5 — значение вне диапазона в файл не пишем.
        book.rating?.takeIf { it in 1..5 }?.let { element(xml, "rating", it.toString()) }
        entry.coverArchiveName?.let { element(xml, "cover", it) }
        book.annotation?.let { element(xml, "comments", it) }
        date(xml, "cdate", book.createdAt)
        date(xml, "mdate", book.updatedAt)

        // Свои поля.
        book.startedAt?.let { date(xml, "дата-начала", it) }
        book.finishedAt?.let { date(xml, "дата-прочтения", it) }
        book.purchasePlace?.let { element(xml, "место-покупки", it) }
        book.printPages?.let { element(xml, "усл.-печ.-л.", formatDecimal(it)) }
        book.originalLanguage?.let { element(xml, "язык-оригинала", it) }
        book.originalTitle?.let { element(xml, "оригинальное-название", it) }

        // Многозначные поля — контейнерами.
        container(xml, "author", splitValues(book.author))
        container(xml, "genre", splitValues(book.genre))
        container(xml, "language", splitValues(book.language))
        container(xml, "translator", splitValues(book.translator))
        container(xml, "cr_year", listOfNotNull(book.printYear?.toString()))
        container(xml, "теги", snapshot.tags)
        container(xml, "цитаты", quoteRows(snapshot.notes))
        container(xml, "заметки", noteRows(snapshot.notes))
        container(xml, "сессии-чтения", sessionRows(snapshot.sessions))
        container(xml, "фото-заметок", photoRows(snapshot.photos))

        // Флаги — только `true`, отсутствие = false.
        if (book.status == BookStatus.FINISHED) element(xml, "read", "true")
        if (book.isGift) element(xml, "gift", "true")
        if (book.isLent) element(xml, "loaned", "true")
        if (book.isSold) element(xml, "продано-new", "true")
        if (book.status == BookStatus.ABANDONED) element(xml, "брошено", "true")
        if (book.status == BookStatus.READING) element(xml, "прочитал-часть", "true")

        // Рецензия — первая заметка REVIEW по createdAt.
        reviewText(snapshot.notes)?.let { element(xml, "рецензия", it) }

        // Чужие поля: имя элемента нельзя экранировать (экранирование даёт
        // невалидное XML), поэтому поле с небезопасным именем пропускаем.
        snapshot.extraFields.forEach { (name, extra) ->
            if (isSafeName(name)) element(xml, name, extra.value)
        }

        xml.append("  </entry>\n")
    }

    /** `<images>` — по одному `<image>` на обложку; без обложек блока нет вовсе. */
    private fun writeImages(xml: StringBuilder, entries: List<TcEntry>) {
        val names = entries.mapNotNull { it.coverArchiveName }.distinct()
        if (names.isEmpty()) return
        xml.append("  <images>\n")
        for (name in names) {
            val format = name.substringAfterLast('.', "")
            xml.append("   <image id=\"${escape(name)}\" format=\"${escape(format)}\"/>\n")
        }
        xml.append("  </images>\n")
    }

    /**
     * Элемент со значением.
     *
     * Имя элемента **не экранируется** — экранирование даёт невалидный XML.
     * Поэтому имя должно быть безопасным: свои поля — константы, чужие
     * проверяются [isSafeName] перед вызовом.
     */
    private fun element(xml: StringBuilder, name: String, value: String) {
        xml.append("   <$name>${escape(value)}</$name>\n")
    }

    /** Дата Tellico: `<поле calendar="gregorian">` с двузначными месяцем и днём. */
    private fun date(xml: StringBuilder, name: String, epochMillis: Long) {
        val date = epochMillis.toLocalDate()
        val month = date.monthValue.toString().padStart(2, '0')
        val day = date.dayOfMonth.toString().padStart(2, '0')
        xml.append("   <$name calendar=\"gregorian\"><year>${date.year}</year>")
        xml.append("<month>$month</month><day>$day</day></$name>\n")
    }

    /**
     * Контейнер многозначного поля: `<имя+s>` с вложенными `<имя>`.
     *
     * Имя контейнера — имя поля с `s` на конце (как у Tellico): `authors`/`author`,
     * `тегиs`/`теги`.
     */
    private fun container(xml: StringBuilder, item: String, values: List<String>) {
        if (values.isEmpty()) return
        xml.append("   <${item}s>")
        values.forEach { xml.append("<$item>${escape(it)}</$item>") }
        xml.append("</${item}s>\n")
    }

    /** Многозначное значение режется по `"; "` — так его и читает импортёр. */
    private fun splitValues(value: String?): List<String> =
        value?.split("; ")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** Цитаты: `страница::текст` (пустая страница → `::текст`). */
    private fun quoteRows(notes: List<NoteSnapshot>): List<String> =
        notes.filter { it.kind == NoteKind.QUOTE }
            .map { row(listOf(it.page?.toString().orEmpty(), it.body)) }

    /** Заметки: `страница::текст::дата`. */
    private fun noteRows(notes: List<NoteSnapshot>): List<String> =
        notes.filter { it.kind == NoteKind.NOTE }
            .map {
                row(
                    listOf(
                        it.page?.toString().orEmpty(),
                        it.body,
                        it.createdAt?.let { millis -> millis.toLocalDate().toString() }.orEmpty(),
                    ),
                )
            }

    /** Сессии: `начало::конец::стр.начала::стр.конца::длительность::заметка`. */
    private fun sessionRows(sessions: List<SessionSnapshot>): List<String> =
        sessions.map {
            row(
                listOf(
                    it.startedAt.toString(),
                    it.endedAt?.toString().orEmpty(),
                    it.startPage?.toString().orEmpty(),
                    it.endPage?.toString().orEmpty(),
                    it.durationSec?.toString().orEmpty(),
                    it.note.orEmpty(),
                ),
            )
        }

    /** Фото: `путь::индекс::дата`. */
    private fun photoRows(photos: List<PhotoSnapshot>): List<String> =
        photos.map {
            row(
                listOf(
                    it.archivePath,
                    it.noteIndex?.toString().orEmpty(),
                    it.createdAt?.toString().orEmpty(),
                ),
            )
        }

    /** Рецензия — текст первой заметки REVIEW по `createdAt`. */
    private fun reviewText(notes: List<NoteSnapshot>): String? =
        notes.filter { it.kind == NoteKind.REVIEW }
            .minByOrNull { it.createdAt ?: Long.MAX_VALUE }
            ?.body
            ?.takeIf { it.isNotBlank() }

    /** Строка таблицы: колонки через `::`. */
    private fun row(cells: List<String>): String = cells.joinToString("::") { cell(it) }

    /**
     * Ячейка таблицы: `\` → `\\`, LF → `\n`, CR → `\r`.
     *
     * Без этого перевод строки внутри заметки потерялся бы, а текст с `\` —
     * развернулся бы при чтении.
     */
    private fun cell(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")

    /** Экранирование XML. */
    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    /**
     * Имя, пригодное для имени элемента XML.
     *
     * По правилам XML 1.0 имя элемента начинается с NameStartChar (буква или `_`),
     * дальше — буквы, цифры, `_`, `-`, `.`. Нарушение этого правила отвергает
     * **весь документ**, а не одно поле, — поэтому чужие поля с небезопасным
     * именем пропускаем, а не экранируем.
     *
     * Проверка Unicode- aware: `isLetter()`/`isLetterOrDigit()` знают любой
     * алфавит (кириллица, грека, «café»), а рукописный список букв выбрасывал бы
     * валидные имена. Имена с префиксом `xml` зарезервированы XML — их не пишем.
     */
    private fun isSafeName(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.startsWith("xml", ignoreCase = true)) return false
        val first = name[0]
        if (!first.isLetter() && first != '_') return false
        return name.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
    }

    /** Объявление поля в `<fields>`. */
    private data class FieldDef(
        val name: String,
        val title: String,
        val type: Int,
        val category: String,
        val flags: Int = 0,
        val format: Int = 4,
        val columns: Int? = null,
        val columnTitles: List<String> = emptyList(),
    )

    /** Свои поля — с теми же `name`/`title`, что в живой коллекции Tellico. */
    private val ownFields: List<FieldDef> = listOf(
        ownField("брошено", type = 4, flags = 2, category = "Личное"),
        ownField("дата-начала", type = 12, format = 3, category = "Личное"),
        ownField("дата-прочтения", type = 12, format = 3, category = "Личное"),
        ownField("прочитал-часть", type = 4, flags = 2, category = "Личное"),
        ownField("место-покупки", type = 1, category = "Покупка"),
        ownField("продано-new", type = 4, flags = 2, category = "Личное"),
        ownField("усл.-печ.-л.", type = 6, category = "Издательство"),
        ownField("язык-оригинала", type = 1, category = "Издательство"),
        ownField("оригинальное-название", type = 1, category = "Общее"),
        ownField(
            "цитаты", type = 2, flags = 1, category = "Цитаты",
            columns = 2, columnTitles = listOf("страница", "Цитата"),
        ),
        ownField("рецензия", type = 2, category = "Рецензия"),
        ownField(
            "заметки", type = 2, flags = 1, category = "Личное",
            columns = 3, columnTitles = listOf("страница", "Текст", "Дата"),
        ),
        ownField("теги", type = 1, flags = 1, category = "Классификация"),
        ownField(
            "сессии-чтения", type = 2, flags = 1, category = "Личное", columns = 6,
            columnTitles = listOf("начало", "конец", "страница начала", "страница конца", "длительность", "заметка"),
        ),
        ownField(
            "фото-заметок", type = 2, flags = 1, category = "Личное", columns = 3,
            columnTitles = listOf("файл", "индекс заметки", "дата"),
        ),
    )

    /** Заголовок поля берём из словаря Tellico — чтобы имена не разъезжались. */
    private fun ownField(
        name: String,
        type: Int,
        category: String,
        flags: Int = 0,
        format: Int = 4,
        columns: Int? = null,
        columnTitles: List<String> = emptyList(),
    ) = FieldDef(
        name = name,
        title = TellicoFields.TITLE.getValue(name),
        type = type,
        category = category,
        flags = flags,
        format = format,
        columns = columns,
        columnTitles = columnTitles,
    )
}
