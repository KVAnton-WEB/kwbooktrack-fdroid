package ru.antonkw.kwbooktrack.data.tellico

import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.snapshot.BookSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.MovieSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import ru.antonkw.kwbooktrack.data.snapshot.NoteSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.PhotoSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.SessionSnapshot
import ru.antonkw.kwbooktrack.data.text.formatDecimal
import ru.antonkw.kwbooktrack.data.time.toDayDate
import ru.antonkw.kwbooktrack.data.time.toLocalDay
import java.time.LocalDate

/** Одна запись каталога для экспорта. */
data class TcEntry(
    val snapshot: BookSnapshot,
    /** Имя файла обложки в архиве (`{md5}.{ext}`) — без пути `images/`. */
    val coverArchiveName: String? = null,
) {
    /** Текстовая цена («по подписке») — если числовой у книги нет. */
    fun textPrice(): String? =
        snapshot.extraFields["pur_price"]?.value?.takeIf { snapshot.book.purchasePrice == null }
}

/** Запись фильма для экспорта. */
data class TcMovieEntry(
    val snapshot: MovieSnapshot,
    /** Имя файла обложки в архиве (`{md5}.{ext}`) — без пути `images/`. */
    val coverArchiveName: String? = null,
)

/**
 * Сборщик `tellico.xml` — родного формата Tellico (DTD v12).
 *
 * Правила, которые Tellico не прощает (потому и написаны руками):
 *  - `<fields>` должен объявлять **все** поля записи, стандартные Tellico
 *    включительно: при импорте Tellico берёт поля только из `<fields>`, а
 *    значения необъявленных полей молча выбрасывает;
 *  - многозначное поле — контейнер `<имя+s>` с вложенными `<имя>`; плоская запись
 *    читается как одно значение;
 *  - дата — `<поле calendar="gregorian">` с `year`/`month`/`day`;
 *  - флаг — только `true`, отсутствие = false;
 *  - `<field>` требует `category`, а `<images>` — хотя бы один `<image>`;
 *  - `<image>` — ровно `id` и `format`: без `width`/`height` обложка не показывается.
 */
object TellicoXmlWriter {

    /**
     * Символы, запрещённые в XML 1.0: Control-ы, кроме табуляции, перевода
     * строки и возврата каретки. Один такой символ в тексте делает невалидным
     * весь документ — Tellico и SAX отвергнут файл целиком.
     */
    private val ILLEGAL_XML_CHARS = Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]")

    /** Собрать содержимое tellico.xml по записям. */
    fun write(entries: List<TcEntry>): String {
        val xml = StringBuilder()
        header(xml)
        xml.append(" <collection title=\"kwbooktrack\" type=\"2\">\n")
        writeFields(xml, entries)
        entries.forEachIndexed { index, entry -> writeEntry(xml, entry, index + 1) }
        writeImages(xml, entries.map { it.coverArchiveName })
        xml.append(" </collection>\n")
        xml.append("</tellico>\n")
        return xml.toString()
    }

    /**
     * Коллекция фильмов — `type="3"`.
     *
     * Отдельная коллекция, а не вторая в том же файле: Tellico держит в файле
     * **одну** коллекцию — при двух он взял бы последнюю и смешал записи.
     */
    fun writeMovies(entries: List<TcMovieEntry>): String {
        val xml = StringBuilder()
        header(xml)
        xml.append(" <collection title=\"kwbooktrack\" type=\"3\">\n")
        writeMovieFields(xml, entries)
        entries.forEachIndexed { index, entry -> writeMovieEntry(xml, entry, index + 1) }
        writeImages(xml, entries.map { it.coverArchiveName })
        xml.append(" </collection>\n")
        xml.append("</tellico>\n")
        return xml.toString()
    }

    /** Заголовок документа: XML-пролог, DTD и корневой элемент. */
    private fun header(xml: StringBuilder) {
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        xml.append(
            "<!DOCTYPE tellico PUBLIC '-//Robby Stephenson/DTD Tellico V12.0//EN' " +
                "'http://periapsis.org/tellico/dtd/v12/tellico.dtd'>\n",
        )
        xml.append("<tellico xmlns=\"http://periapsis.org/tellico/\" syntaxVersion=\"12\">\n")
    }

    /**
     * `<fields>`: объявляем **все** поля, которые пишем в записи, — и свои, и
     * стандартные Tellico.
     *
     * Стандартные поля объявлять обязательно: при импорте Tellico собирает
     * коллекцию только из объявленных полей (`addFields = defaultFields ||
     * fields.isEmpty()`) и **молча выбрасывает** значения тех полей, которых в
     * коллекции нет. Без объявления терялись заголовок, автор, издательство,
     * обложка, аннотация и прочее — записи выглядели пустыми.
     */
    private fun writeFields(xml: StringBuilder, entries: List<TcEntry>) {
        xml.append("  <fields>\n")
        standardFields.forEach { writeField(xml, it) }
        ownFields.forEach { writeField(xml, it) }
        // Текстовая цена — отдельное поле: `pur_price` у Tellico числовой.
        if (entries.any { it.textPrice() != null }) {
            writeField(
                xml,
                FieldDef(
                    name = TellicoFields.TEXT_PRICE,
                    title = TellicoFields.TITLE.getValue(TellicoFields.TEXT_PRICE),
                    type = 1,
                    category = "Покупка",
                ),
            )
        }
        foreignFields(entries.map { it.snapshot.extraFields }).forEach { (name, extra) ->
            writeField(xml, FieldDef(name = name, title = extra.title, type = 1, category = "Общее"))
        }
        xml.append("  </fields>\n")
    }

    /** Чужие поля всех записей: имя → поле (по первому попавшемуся). */
    private fun foreignFields(
        allExtra: List<Map<String, ExtraField>>,
        known: Set<String> = TellicoFields.KNOWN,
        skipBuiltins: Boolean = true,
    ): Map<String, ExtraField> {
        val result = linkedMapOf<String, ExtraField>()
        for (extraFields in allExtra) {
            for ((name, extra) in extraFields) {
                if (!isSafeName(name)) continue
                // Поле, которое Tellico знает сам, объявлять нельзя — будет дубликат.
                if (name in known) continue
                if (skipBuiltins && name in TellicoFields.TELLICO_BUILTIN) continue
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
        // `pur_price` у Tellico — числовое поле: запятая обрезала бы дробную
        // часть у того, кто читает файл.
        book.purchasePrice?.let { element(xml, "pur_price", formatDecimal(it).replace(',', '.')) }
        book.purchaseDate?.let { date(xml, "pur_date", it.toDayDate()) }
        // У Tellico рейтинг 1..5 — значение вне диапазона в файл не пишем.
        book.rating?.takeIf { it in 1..5 }?.let { element(xml, "rating", it.toString()) }
        entry.coverArchiveName?.let { element(xml, "cover", it) }
        book.annotation?.let { element(xml, "comments", it) }
        dateTime(xml, "cdate", book.createdAt)
        dateTime(xml, "mdate", book.updatedAt)

        // Свои поля.
        book.startedAt?.let { date(xml, "дата-начала", it.toDayDate()) }
        book.finishedAt?.let { date(xml, "дата-прочтения", it.toDayDate()) }
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
        table(xml, "цитаты", quoteRows(snapshot.notes))
        table(xml, "заметки", noteRows(snapshot.notes))
        table(xml, "сессии-чтения", sessionRows(snapshot.sessions))
        table(xml, "фото-заметок", photoRows(snapshot.photos))

        // Флаги — только `true`, отсутствие = false.
        if (book.status == BookStatus.FINISHED) element(xml, "read", "true")
        if (book.isGift) element(xml, "gift", "true")
        if (book.isLent) element(xml, "loaned", "true")
        if (book.isSold) element(xml, "продано-new", "true")
        if (book.status == BookStatus.ABANDONED) element(xml, "брошено", "true")
        if (book.status == BookStatus.READING) element(xml, "прочитал-часть", "true")

        // Рецензии — все, по одной на строку (см. `reviewText`).
        reviewText(snapshot.notes)?.let { element(xml, "рецензия", it) }

        // Чужие поля: имя элемента нельзя экранировать (экранирование даёт
        // невалидное XML), поэтому поле с небезопасным именем пропускаем.
        snapshot.extraFields.forEach { (name, extra) ->
            writeExtra(xml, name, extra, TellicoFields.TEXT_PRICE, book.purchasePrice)
        }

        xml.append("  </entry>\n")
    }

    /**
     * Чужее поле — элемент в записи.
     *
     * Поля, которые Tellico знает сам (`keyword`, `pur_price`), объявлять в
     * `<fields>` нельзя — Tellico завёл бы дубликат. «Ключевые слова» и
     * «Редактор» многозначные, поэтому их пишут контейнером; текстовая цена
     * идёт в отдельное поле ([TellicoFields.TEXT_PRICE]), а флаг — как
     * `true`/`false`.
     */
    private fun writeExtra(
        xml: StringBuilder,
        name: String,
        extra: ExtraField,
        /**
         * Куда писать нечисловую цену. У книг `pur_price` Tellico числовой
         * (`type="6"`) — текст в нём он не покажет, и цена живёт в отдельном
         * поле. У фильмов `pur_price` текстовый, и отдельного поля не нужно.
         */
        textPriceField: String,
        /** Числовая цена уже записана своей колонкой — значит, текст не нужен. */
        purchasePrice: Double?,
    ) {
        when {
            !isSafeName(name) -> Unit
            name == "keyword" -> container(xml, "keyword", extra.value.split("; ").filter { it.isNotEmpty() })
            // «Редактор» у Tellico многозначный: `<editors><editor>…</editor></editors>`.
            name == "editor" -> container(xml, "editor", extra.value.split("; ").filter { it.isNotEmpty() })
            // Числовая цена важнее текстовой: два `<pur_price>` — последний победил бы.
            name == "pur_price" && purchasePrice != null -> Unit
            name == "pur_price" -> element(xml, textPriceField, extra.value)
            // Флаг Tellico: `true`/`false` — так пишет сам Tellico.
            name == "signed" -> element(xml, name, boolText(extra.value))
            else -> element(xml, name, extra.value)
        }
    }

    /** Флаг Tellico: `true`/`false`. */
    private fun boolText(value: String): String =
        if (value.trim().lowercase() in TellicoFields.TRUE_VALUES) "true" else "false"

    /**
     * `<fields>` коллекции фильмов: поля Tellico для видео плюс наши
     * собственные (иначе Tellico молча выбросил бы заметки, теги и фото).
     *
     * Чужие поля — по словарю коллекции фильмов: имена из книжного словаря
     * (`author`, `publisher`…) в фильмовой коллекции чужие, и без объявления
     * Tellico молча выбросил бы их значения при импорте. Встроенные поля
     * Tellico — встроенные у книжной коллекции; у видео коллекции таких нет.
     */
    private fun writeMovieFields(xml: StringBuilder, entries: List<TcMovieEntry>) {
        xml.append("  <fields>\n")
        movieFields.forEach { writeField(xml, it) }
        ownMovieFields.forEach { writeField(xml, it) }
        foreignFields(
            entries.map { it.snapshot.extraFields },
            known = TellicoFields.MOVIE_KNOWN,
            skipBuiltins = false,
        ).forEach { (name, extra) ->
            writeField(xml, FieldDef(name = name, title = extra.title, type = 1, category = "Общее"))
        }
        xml.append("  </fields>\n")
    }

    /** Запись фильма в коллекции `type="3"`. */
    private fun writeMovieEntry(xml: StringBuilder, entry: TcMovieEntry, id: Int) {
        val snapshot = entry.snapshot
        val movie = snapshot.movie
        xml.append("  <entry id=\"$id\">\n")

        element(xml, "title", movie.title)
        movie.originalTitle?.let { element(xml, "оригинальное-название", it) }
        movie.year?.let { element(xml, "year", it.toString()) }
        container(xml, "genre", splitValues(movie.genre))
        container(xml, "nationality", splitValues(movie.nationality))
        container(xml, "director", splitValues(movie.director))
        castTable(xml, splitRows(movie.cast))
        movie.durationMin?.let { element(xml, "running-time", it.toString()) }
        // У Tellico рейтинг 1..5 — значение вне диапазона в файл не пишем.
        movie.rating?.takeIf { it in 1..5 }?.let { element(xml, "rating", it.toString()) }
        entry.coverArchiveName?.let { element(xml, "cover", it) }
        movie.plot?.let { element(xml, "plot", it) }
        movie.comments?.let { element(xml, "comments", it) }
        movie.watchedAt?.let { date(xml, "дата-просмотра", it.toDayDate()) }
        movie.url?.let { element(xml, "кинопоиск", it) }
        if (movie.isDownloaded) element(xml, "скачан", "true")
        dateTime(xml, "cdate", movie.createdAt)
        dateTime(xml, "mdate", movie.updatedAt)

        container(xml, "теги", snapshot.tags)
        table(xml, "цитаты", quoteRows(snapshot.notes))
        table(xml, "заметки", noteRows(snapshot.notes))
        table(xml, "фото-заметок", photoRows(snapshot.photos))
        reviewText(snapshot.notes)?.let { element(xml, "рецензия", it) }

        snapshot.extraFields.forEach { (name, extra) ->
            // `pur_price` у фильмов текстовое — пишем в своё же поле.
            writeExtra(xml, name, extra, "pur_price", null)
        }

        xml.append("  </entry>\n")
    }

    /**
     * Таблица `cast` — родным для Tellico способом.
     *
     * У Tellico `cast` — таблица из двух колонок, и актёр с ролью лежат
     * отдельными элементами `<column>`. Обычная табличная запись (одна строка
     * через `::`) для него не годится: он прочитал бы её как один столбец.
     */
    private fun castTable(xml: StringBuilder, rows: List<String>) {
        if (rows.isEmpty()) return
        xml.append("   <casts>\n")
        for (row in rows) {
            xml.append("    <cast>")
            row.split("::").forEach { cell -> xml.append("<column>${escape(cell)}</column>") }
            xml.append("</cast>\n")
        }
        xml.append("   </casts>\n")
    }

    /** Строки табличного значения: перевод строки между ними. */
    private fun splitRows(value: String?): List<String> =
        value?.split("\n")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    /** `<images>` — по одному `<image>` на обложку; без обложек блока нет вовсе. */
    private fun writeImages(xml: StringBuilder, coverNames: List<String?>) {
        val names = coverNames.filterNotNull().distinct()
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

    /** Момент времени Tellico — дата в зоне устройства. */
    private fun dateTime(xml: StringBuilder, name: String, epochMillis: Long) =
        date(xml, name, epochMillis.toLocalDay())

    /** Дата Tellico: `<поле calendar="gregorian">` с двузначными месяцем и днём. */
    private fun date(xml: StringBuilder, name: String, date: LocalDate) {
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

    /**
     * Табличное поле: **одна** таблица, строки — переводом строки.
     *
     * Так пишет сам Tellico: `<цитатыs><цитаты>1::первая\n2::вторая</цитаты></цитатыs>`.
     * Отдельный элемент на строку Tellico прочитал бы как отдельную таблицу — в его
     * интерфейсе появился бы переключатель таблиц вместо одной таблицы с цитатами.
     */
    private fun table(xml: StringBuilder, item: String, rows: List<String>) {
        if (rows.isEmpty()) return
        container(xml, item, listOf(rows.joinToString("\n")))
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
                        it.createdAt?.let { millis -> millis.toLocalDay().toString() }.orEmpty(),
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

    /**
     * Рецензии — все, по времени создания.
     *
     * Раньше писалась только первая, а остальные молча терялись — и вместе с
     * ними «съезжала» привязка фото: индекс фото считался по полному списку
     * заметок, а в файле рецензия была одна. Теперь каждая рецензия — своя
     * строка, переводы строк внутри текста экранированы (`cell`), так что
     * импорт разбирает файл обратно без потерь.
     */
    private fun reviewText(notes: List<NoteSnapshot>): String? =
        notes.filter { it.kind == NoteKind.REVIEW }
            .sortedBy { it.createdAt ?: Long.MAX_VALUE }
            .map { it.body }
            .filter { it.isNotBlank() }
            .takeIf { it.isNotEmpty() }
            ?.joinToString("\n") { cell(it) }

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
        // Символы, запрещённые в XML 1.0 (кроме табуляции и переводов строк),
        // делают невалидным весь документ — а не одно поле. Один такой символ
        // в заметке убивал бы весь экспорт.
        .replace(ILLEGAL_XML_CHARS, "")
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

    /**
     * Стандартные поля Tellico для коллекции «книги».
     *
     * Типы, форматы и флаги — как в живой коллекции Tellico
     * (`src/collections/bookcollection.cpp`):
     * `type` — вид поля (1 текст, 2 многострочный, 4 флаг, 6 число, 10 картинка,
     * 12 дата, 14 рейтинг), `format` — оформление (0 как есть, 1 заголовок,
     * 2 имя, 3 дата), `flags` — биты (1 несколько значений, 2 группировка,
     * 4 подсказка, 16 не править).
     *
     * Бит 1 (несколько значений) обязателен у многозначных полей: без него
     * Tellico склеит значения контейнера в одно.
     *
     * «Переплёт» и «Состояние» у Tellico — списки выбора, и значение не из
     * списка он **молча выбрасывает**. В приложении это свободный текст, поэтому
     * объявляем их текстом: потерять значение хуже, чем показать его без
     * выпадающего списка.
     */
    private val standardFields: List<FieldDef> = listOf(
        std("title", type = 1, format = 1, category = "Общее"),
        std("subtitle", type = 1, format = 1, category = "Общее"),
        std("author", type = 1, format = 2, flags = 7, category = "Общее"),
        std("editor", type = 1, format = 2, flags = 5, category = "Общее"),
        std("publisher", type = 1, format = 0, flags = 6, category = "Издательство"),
        std("edition", type = 1, format = 0, flags = 4, category = "Издательство"),
        std("cr_year", type = 6, flags = 1, category = "Издательство"),
        std("pub_year", type = 6, flags = 2, category = "Издательство"),
        std("isbn", type = 1, category = "Издательство"),
        std("lccn", type = 1, category = "Издательство"),
        std("pages", type = 6, category = "Издательство"),
        std("language", type = 1, flags = 5, category = "Издательство"),
        std("genre", type = 1, flags = 7, category = "Классификация"),
        std("keyword", type = 1, flags = 7, category = "Классификация"),
        std("series", type = 1, flags = 6, category = "Классификация"),
        std("series_num", type = 6, category = "Классификация"),
        std("condition", type = 1, category = "Классификация"),
        std("translator", type = 1, format = 2, flags = 7, category = "Издательство"),
        std("binding", type = 1, flags = 2, category = "Общее"),
        std("pur_price", type = 6, category = "Покупка"),
        std("pur_date", type = 12, format = 3, category = "Покупка"),
        std("read", type = 4, flags = 2, category = "Личное"),
        std("gift", type = 4, flags = 2, category = "Личное"),
        std("loaned", type = 4, flags = 2, category = "Личное"),
        std("signed", type = 4, category = "Личное"),
        std("rating", type = 14, flags = 2, category = "Личное"),
        std("cover", type = 10, category = "Лицевая сторона обложки"),
        std("plot", type = 2, category = "Содержание"),
        std("comments", type = 2, category = "Аннотация"),
        std("cdate", type = 12, format = 3, flags = 16, category = "Личное"),
        std("mdate", type = 12, format = 3, flags = 16, category = "Личное"),
    )

    /**
     * Поля коллекции фильмов Tellico.
     *
     * Типы, форматы, флаги и категории — как в живой коллекции видео
     * (`movi.tc`), чтобы выгрузка читалась Tellico как родная:
     * `type` — вид поля (1 текст, 2 многострочный, 3 список, 4 флаг, 6 число,
     * 7 ссылка, 8 таблица, 10 картинка, 12 дата, 14 рейтинг), `format` —
     * оформление (0 как есть, 1 заголовок, 2 имя, 3 дата, 4 в списке),
     * `flags` — биты (1 несколько значений, 2 группировка, 4 подсказка,
     * 8 заголовок, 16 не править).
     */
    private val movieFields: List<FieldDef> = listOf(
        movieStd("title", type = 1, format = 1, flags = 8, category = "Общее"),
        movieStd("medium", type = 1, flags = 2, category = "Общее"),
        movieStd("year", type = 6, flags = 2, category = "Общее"),
        movieStd("certification", type = 1, flags = 2, category = "Общее"),
        movieStd("genre", type = 1, flags = 7, format = 0, category = "Общее"),
        movieStd("region", type = 1, flags = 2, category = "Общее"),
        movieStd("nationality", type = 1, flags = 7, format = 0, category = "Общее"),
        movieStd("format", type = 1, flags = 2, category = "Общее"),
        movieStd(
            "cast", type = 8, flags = 3, format = 2, category = "В ролях",
            columns = 2, columnTitles = listOf("Актёр/актриса", "Роль"),
        ),
        movieStd("director", type = 1, flags = 7, format = 2, category = "Other People"),
        movieStd("producer", type = 1, flags = 7, format = 2, category = "Other People"),
        movieStd("writer", type = 1, flags = 7, format = 2, category = "Other People"),
        movieStd("composer", type = 1, flags = 7, format = 2, category = "Other People"),
        movieStd("studio", type = 1, flags = 7, format = 0, category = "Other People"),
        movieStd("language", type = 1, flags = 7, format = 0, category = "Features"),
        movieStd("subtitle", type = 1, flags = 7, format = 0, category = "Features"),
        movieStd("audio-track", type = 1, flags = 7, format = 0, category = "Features"),
        movieStd("running-time", type = 6, category = "Features"),
        movieStd("aspect-ratio", type = 1, flags = 7, category = "Features"),
        movieStd("widescreen", type = 4, category = "Features"),
        movieStd("color", type = 1, flags = 2, category = "Features"),
        movieStd("directors-cut", type = 4, category = "Features"),
        movieStd("plot", type = 2, category = "Сюжет"),
        movieStd("rating", type = 14, flags = 2, category = "Личное"),
        movieStd("pur_date", type = 1, format = 3, category = "Личное"),
        movieStd("gift", type = 4, category = "Личное"),
        movieStd("pur_price", type = 1, category = "Личное"),
        movieStd("loaned", type = 4, category = "Личное"),
        movieStd("keyword", type = 1, flags = 7, category = "Личное"),
        movieStd("cover", type = 10, category = "Обложка"),
        movieStd("comments", type = 2, category = "Комментарии"),
        movieStd("cdate", type = 12, format = 3, flags = 16, category = "Личное"),
        movieStd("mdate", type = 12, format = 3, flags = 16, category = "Личное"),
        movieStd("дата-просмотра", type = 12, format = 3, flags = 2, category = "Личное"),
        movieStd("оригинальное-название", type = 1, category = "Общее"),
        movieStd("кинопоиск", type = 7, category = "Общее"),
        movieStd("скачан", type = 4, flags = 2, category = "Личное"),
        movieStd("рецензия", type = 2, category = "Рецензия"),
    )

    /** Наши поля в коллекции фильмов — те же, что у книг. */
    private val ownMovieFields: List<FieldDef> = listOf(
        ownField(
            "цитаты", type = 2, flags = 1, category = "Цитаты",
            columns = 2, columnTitles = listOf("страница", "Цитата"),
        ),
        ownField(
            "заметки", type = 2, flags = 1, category = "Личное",
            columns = 3, columnTitles = listOf("страница", "Текст", "Дата"),
        ),
        ownField("теги", type = 1, flags = 1, category = "Классификация"),
        ownField(
            "фото-заметок", type = 2, flags = 1, category = "Личное", columns = 3,
            columnTitles = listOf("файл", "индекс заметки", "дата"),
        ),
    )

    /** Поле Tellico для видео: заголовок — из словаря полей коллекции фильмов. */
    private fun movieStd(
        name: String,
        type: Int,
        format: Int = 4,
        flags: Int = 0,
        category: String,
        columns: Int? = null,
        columnTitles: List<String> = emptyList(),
    ) = FieldDef(
        name = name,
        title = TellicoFields.MOVIE_TITLES.getValue(name),
        type = type,
        category = category,
        flags = flags,
        format = format,
        columns = columns,
        columnTitles = columnTitles,
    )

    /** Стандартное поле Tellico для видео. */
    private fun std(
        name: String,
        type: Int,
        format: Int = 4,
        flags: Int = 0,
        category: String,
    ) = FieldDef(
        name = name,
        title = TellicoFields.TITLE[name] ?: TellicoFields.STANDARD_TITLES.getValue(name),
        type = type,
        category = category,
        flags = flags,
        format = format,
    )

    /** Свои поля — с теми же `name`/`title`, что в живой коллекции Tellico. */
    private val ownFields: List<FieldDef> = listOf(
        ownField("брошено", type = 4, flags = 2, category = "Личное"),
        ownField("дата-начала", type = 12, format = 3, category = "Личное"),
        ownField("дата-прочтения", type = 12, format = 3, category = "Личное"),
        ownField("прочитал-часть", type = 4, flags = 2, category = "Личное"),
        ownField("место-покупки", type = 1, category = "Покупка"),
        ownField("продано-new", type = 4, flags = 2, category = "Личное"),
        // Тип 1 (текст), а не 6 (число): в живой коллекции Tellico это поле
        // объявлено текстовым, значения — «28,2» с запятой.
        ownField("усл.-печ.-л.", type = 1, category = "Издательство"),
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
        // Флаг 1 в `flags` — «много значений»: значение пишется контейнером
        // (`<тегиs><теги>…</теги></тегиs>`). Так объявлены и поля живой
        // коллекции Tellico: `keyword` (`flags="7"`), `language` (`flags="5"`).
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
