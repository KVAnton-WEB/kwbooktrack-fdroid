package ru.antonkw.kwbooktrack.data.tellico

import org.w3c.dom.Element
import org.w3c.dom.Node
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Ридер родного формата Tellico: `tellico.xml` из архива `.tc`.
 *
 * Особенности формата, которые обязан понимать ридер:
 *  - многозначное поле пишется **контейнером**: `<authors><author>…</author></authors>`,
 *    имя поля — единственное (`author`), контейнер — с `s` на конце;
 *  - дата — либо `calendar="gregorian"` с детьми `year`/`month`/`day` (возможна частичная),
 *    либо обычным текстом;
 *  - табличное поле (`columns > 1`) — строки через `\n`, колонки через `::`.
 */
@Singleton
class TellicoXmlReader @Inject constructor() {

    fun read(bytes: ByteArray): List<TellicoRecord> =
        ByteArrayInputStream(bytes).use { parse(it) }

    fun read(file: File): List<TellicoRecord> =
        file.inputStream().use { parse(it) }

    private fun parse(input: InputStream): List<TellicoRecord> {
        val document = try {
            factory().newDocumentBuilder().parse(input)
        } catch (e: Exception) {
            throw IllegalStateException("Не удалось прочитать XML Tellico", e)
        }
        val collection = document.documentElement
            ?.collect("collection")
            ?.firstOrNull { it.getAttribute("type") == "2" }
            ?: throw IllegalStateException("Это не коллекция книг")
        val declarations = collection.childElements()
            .firstOrNull { it.tag() == "fields" }
            ?.let { parseDeclarations(it) }
            .orEmpty()
        return collection.childElements()
            .filter { it.tag() == "entry" }
            .map { parseEntry(it, declarations) }
    }

    /**
     * Парсер без сети: файл Tellico ссылается на внешний DTD, а в записи могут быть
     * внешние сущности — и то и другое блокируем.
     */
    private fun factory(): DocumentBuilderFactory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        runCatching { isExpandEntityReferences = false }
        runCatching { isXIncludeAware = false }
    }

    /** Объявления полей коллекции: внутреннее имя → заголовок и число колонок таблицы. */
    private fun parseDeclarations(fields: Element): Map<String, Declaration> =
        fields.childElements()
            .filter { it.tag() == "field" }
            .mapNotNull { field ->
                val name = field.getAttribute("name")
                if (name.isEmpty()) return@mapNotNull null
                val columns = field.childElements()
                    .firstOrNull { it.tag() == "prop" && it.getAttribute("name") == "columns" }
                    ?.textContent
                    ?.trim()
                    ?.toIntOrNull()
                    ?: 0
                name to Declaration(title = field.getAttribute("title").ifEmpty { null }, columns = columns)
            }
            .toMap()

    private fun parseEntry(entry: Element, declarations: Map<String, Declaration>): TellicoRecord {
        val fields = mutableMapOf<String, String>()
        val extra = mutableMapOf<String, ExtraField>()
        for (element in entry.childElements()) {
            if (element.tag() == "id") continue // служебный номер записи Tellico
            val (name, values) = element.fieldValues()
            val declaration = declarations[name]
            val separator = if ((declaration?.columns ?: 0) > 1) "\n" else "; "
            val value = values.joinToString(separator)
            if (value.isEmpty()) continue
            if (name in TellicoFields.KNOWN) {
                fields[name] = value
            } else {
                extra[name] = ExtraField(title = declaration?.title ?: name, value = value)
            }
        }
        return TellicoRecord(fields, extra)
    }

    /** Имя поля и его значения: контейнер многозначного поля, дата или обычный текст. */
    private fun Element.fieldValues(): Pair<String, List<String>> {
        if (hasAttribute("calendar")) return tag() to listOf(dateValue())
        val children = childElements()
        if (children.isNotEmpty()) {
            val childName = children[0].tag()
            // Контейнер: все дети — одноимённые элементы, имя контейнера — имя поля + `s`.
            val isContainer = children.all { it.tag() == childName } && tag() == childName + "s"
            if (isContainer) {
                return childName to children.map { it.textContent.trim() }.filter { it.isNotEmpty() }
            }
        }
        return tag() to listOf(textContent.trim())
    }

    /** Дата Tellico → ISO: `yyyy`, `yyyy-MM` или `yyyy-MM-dd` (месяц и день с нулём). */
    private fun Element.dateValue(): String {
        val year = childText("year")
        val month = childText("month")
        val day = childText("day")
        return when {
            year.isEmpty() -> textContent.trim()
            month.isEmpty() -> year
            day.isEmpty() -> "$year-${month.padStart(2, '0')}"
            else -> "$year-${month.padStart(2, '0')}-${day.padStart(2, '0')}"
        }
    }

    private fun Element.childText(name: String): String =
        childElements().firstOrNull { it.tag() == name }?.textContent?.trim().orEmpty()

    /** Имя элемента без префикса пространства имён (в Tellico есть namespace). */
    private fun Element.tag(): String = localName ?: nodeName.substringAfterLast(':')

    private fun Element.childElements(): List<Element> {
        val result = mutableListOf<Element>()
        val nodes = childNodes
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            if (node.nodeType == Node.ELEMENT_NODE) result += node as Element
        }
        return result
    }

    /** Все потомки с именем [tag]. */
    private fun Element.collect(tag: String): List<Element> =
        childElements().flatMap { child ->
            (if (child.tag() == tag) listOf(child) else emptyList()) + child.collect(tag)
        }

    private data class Declaration(val title: String?, val columns: Int)
}
