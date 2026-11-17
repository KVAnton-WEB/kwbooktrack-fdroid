package ru.antonkw.kwbooktrack.data.tellico

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.StringReader
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.SAXParserFactory

/**
 * Ридер родного формата Tellico: `tellico.xml` из архива `.tc`.
 *
 * Особенности формата, которые обязан понимать ридер:
 *  - многозначное поле пишется **контейнером**: `<authors><author>…</author></authors>`,
 *    имя поля — единственное (`author`), контейнер — с `s` на конце;
 *  - дата — либо `calendar="gregorian"` с детьми `year`/`month`/`day` (возможна частичная),
 *    либо обычным текстом;
 *  - табличное поле (`columns > 1`) — строки через `\n`, колонки через `::`.
 *
 * Разбор — **SAX**, а не DOM: парсер Android (`org.apache.harmony.xml`) не
 * понимает имя элемента с не-ASCII буквами, а родной формат Tellico полон
 * кириллических полей (`<рецензия>`, `<цитатыs>`, `<дата-прочтения>`). DOM на
 * JVM такой файл читает, поэтому бая был виден только на устройстве.
 */
@Singleton
class TellicoXmlReader @Inject constructor() {

    fun read(bytes: ByteArray): List<TellicoRecord> =
        ByteArrayInputStream(bytes).use { parse(it) }

    fun read(file: File): List<TellicoRecord> =
        file.inputStream().use { parse(it) }

    private fun parse(input: InputStream): List<TellicoRecord> {
        val root = try {
            val handler = TreeHandler()
            factory().newSAXParser().parse(InputSource(input), handler)
            handler.root
        } catch (e: Exception) {
            throw IllegalStateException("Не удалось прочитать XML Tellico", e)
        } ?: throw IllegalStateException("Не удалось прочитать XML Tellico")
        // Читаем все коллекции, но обрабатываем только книги и фильмы.
        // Остальные типы (библиография, игры) пропускаем: у библиографии год
        // называется `year`, а не `pub_year`, и без разбора она превратилась бы
        // в книги.
        val collections = root.collect("collection")
            .filter { it.attribute("type") in SUPPORTED_TYPES }
        if (collections.isEmpty()) {
            throw IllegalStateException("Это не коллекция книг и не фильмов")
        }
        return collections.flatMap { collection ->
            val type = collection.attribute("type").toIntOrNull() ?: TellicoFields.COLLECTION_BOOKS
            val declarations = collection.children
                .firstOrNull { it.name == "fields" }
                ?.let { parseDeclarations(it) }
                .orEmpty()
            val known = TellicoFields.knownFields(type)
            collection.children
                .filter { it.name == "entry" }
                .map { parseEntry(it, declarations, known, type) }
        }
    }

    /** Типы коллекций, которые приложение умеет импортировать. */
    private val SUPPORTED_TYPES = setOf(
        TellicoFields.COLLECTION_BOOKS.toString(),
        TellicoFields.COLLECTION_MOVIES.toString(),
    )

    /**
     * Парсер без сети: файл Tellico ссылается на внешний DTD, а в записи могут быть
     * внешние сущности — и то и другое блокируем.
     */
    private fun factory(): SAXParserFactory = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }

    /** Объявления полей коллекции: внутреннее имя → заголовок и число колонок таблицы. */
    private fun parseDeclarations(fields: XmlNode): Map<String, Declaration> =
        fields.children
            .filter { it.name == "field" }
            .mapNotNull { field ->
                val name = field.attribute("name")
                if (name.isEmpty()) return@mapNotNull null
                val columns = field.children
                    .firstOrNull { it.name == "prop" && it.attribute("name") == "columns" }
                    ?.textContent()
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 0
                name to Declaration(title = field.attribute("title").ifEmpty { null }, columns = columns)
            }
            .toMap()

    private fun parseEntry(
        entry: XmlNode,
        declarations: Map<String, Declaration>,
        known: Set<String>,
        collectionType: Int,
    ): TellicoRecord {
        val fields = mutableMapOf<String, String>()
        val extra = mutableMapOf<String, ExtraField>()
        for (element in entry.children) {
            if (element.name == "id") continue // служебный номер записи Tellico
            val (name, values) = element.fieldValues(known)
            val declaration = declarations[name]
            val separator = if ((declaration?.columns ?: 0) > 1) "\n" else "; "
            val value = values.joinToString(separator)
            if (value.isEmpty()) continue
            if (name in known) {
                fields[name] = value
            } else {
                extra[name] = ExtraField(title = declaration?.title ?: name, value = value)
            }
        }
        return TellicoRecord(fields, extra, collectionType)
    }

    /** Имя поля и его значения: контейнер многозначного поля, дата или обычный текст. */
    private fun XmlNode.fieldValues(known: Set<String>): Pair<String, List<String>> {
        if (attributes.containsKey("calendar")) return name to listOf(dateValue())
        val children = children
        if (children.isNotEmpty()) {
            val childName = children[0].name
            // Контейнер: все дети — одноимённые элементы, имя контейнера — имя поля + `s`.
            val isContainer = children.all { it.name == childName } && name == childName + "s"
            if (isContainer) {
                return childName to children.map { it.cellValues() }.filter { it.isNotEmpty() }
            }
            // Контейнер со смешанными детьми (`<authors><author/><editor/></authors>`):
            // имя поля — имя контейнера без `s`. Иначе поле ушло бы в «чужие», и
            // автор книги потерялся бы.
            val fieldName = name.removeSuffix("s")
            if (fieldName in known && fieldName != name) {
                return fieldName to children.map { it.cellValues() }.filter { it.isNotEmpty() }
            }
        }
        return name to listOf(textContent().trim())
    }

    /**
     * Значение одного элемента контейнера.
     *
     * Табличное поле Tellico записано «по столбцам»:
     * `<cast><column>Актёр</column><column>Роль</column></cast>`. Текст элемента
     * через [textContent] склеил бы колонки **вместе с whitespace** между
     * ними — перевод строки попал бы в значение, а граница «актёр / роль»
     * потерялась бы. Поэтому колонки соединяем через `::` — так же, как их
     * читает импортёр.
     */
    private fun XmlNode.cellValues(): String {
        val columns = children.filter { it.name == "column" }
        if (columns.isEmpty()) return textContent().trim()
        return columns.joinToString("::") { it.textContent().trim() }
    }

    /** Дата Tellico → ISO: `yyyy`, `yyyy-MM` или `yyyy-MM-dd` (месяц и день с нулём). */
    private fun XmlNode.dateValue(): String {
        val year = childText("year")
        val month = childText("month")
        val day = childText("day")
        return when {
            year.isEmpty() -> textContent().trim()
            month.isEmpty() -> year
            day.isEmpty() -> "$year-${month.padStart(2, '0')}"
            else -> "$year-${month.padStart(2, '0')}-${day.padStart(2, '0')}"
        }
    }

    private fun XmlNode.childText(name: String): String =
        children.firstOrNull { it.name == name }?.textContent()?.trim().orEmpty()

    private fun XmlNode.attribute(name: String): String = attributes[name].orEmpty()

    /** Все потомки с именем [tag]. */
    private fun XmlNode.collect(tag: String): List<XmlNode> =
        children.flatMap { child ->
            (if (child.name == tag) listOf(child) else emptyList()) + child.collect(tag)
        }

    private data class Declaration(val title: String?, val columns: Int)
}

/**
 * Элемент разобранного XML: имя, атрибуты и содержимое в порядке появления.
 *
 * Содержимое — смесь текста и дочерних элементов, порядок важен: `textContent()`
 * должен склеить их как это делает DOM.
 */
private class XmlNode(
    val name: String,
    val attributes: Map<String, String>,
) {
    private val content = mutableListOf<Any>()

    fun addChild(node: XmlNode) {
        content += node
    }

    fun addText(text: String) {
        if (text.isNotEmpty()) content += text
    }

    /** Дочерние элементы (без текста). */
    val children: List<XmlNode> get() = content.filterIsInstance<XmlNode>()

    /** Текст элемента вместе с текстом потомков — как `textContent` в DOM. */
    fun textContent(): String = buildString {
        for (item in content) {
            when (item) {
                is String -> append(item)
                is XmlNode -> append(item.textContent())
            }
        }
    }
}

/** Собирает дерево элементов из потока SAX-событий. */
private class TreeHandler : DefaultHandler() {

    /**
     * Внешние сущности не читаем — ни DTD, ни `file://`.
     *
     * `setFeature` в [factory] может не сработать (парсер не поддерживает
     * фичу), а тогда файл из чужого источника увёл бы приложение в сеть или
     * прочитал локальный файл. Свой резолвер работает на любом парсере:
     * парсер зовёт его сам.
     */
    override fun resolveEntity(publicId: String?, systemId: String?): InputSource =
        InputSource(StringReader(""))

    private val stack = ArrayDeque<XmlNode>()

    var root: XmlNode? = null
        private set

    override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
        val name = localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfterLast(':')
        val node = XmlNode(name, attributes.map())
        stack.lastOrNull()?.addChild(node) ?: run { root = node }
        stack.addLast(node)
    }

    override fun endElement(uri: String?, localName: String?, qName: String?) {
        stack.removeLastOrNull()
    }

    override fun characters(ch: CharArray?, start: Int, length: Int) {
        if (ch == null) return
        stack.lastOrNull()?.addText(String(ch, start, length))
    }

    /** Атрибуты по локальному имени: без префикса у них имя как есть. */
    private fun Attributes.map(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (index in 0 until length) {
            val key = getLocalName(index)?.takeIf { it.isNotEmpty() }
                ?: getQName(index).orEmpty().substringAfterLast(':')
            result[key] = getValue(index)
        }
        return result
    }
}
