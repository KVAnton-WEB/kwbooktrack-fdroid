package ru.antonkw.kwbooktrack.data.tellico

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import java.io.File

class TellicoXmlReaderTest {

    private val reader = TellicoXmlReader()

    @Test
    fun containerValuesAreJoinedWithSemicolon() {
        val record = record("<authors><author>А</author><author>Б</author></authors>")

        assertThat(record.fields["author"]).isEqualTo("А; Б")
    }

    @Test
    fun tableRowsOfBothTablesAreJoinedWithNewline() {
        val record = record(
            fields = "<цитатыs><цитаты>1::первая\n2::вторая</цитаты><цитаты>3::третья</цитаты></цитатыs>",
            declarations = "<field name=\"цитаты\" type=\"2\"><prop name=\"columns\">2</prop></field>",
        )

        assertThat(record.fields["цитаты"]).isEqualTo("1::первая\n2::вторая\n3::третья")
    }

    @Test
    fun plainFieldKeepsItsText() {
        val record = record("<publisher>Litres</publisher>")

        assertThat(record.fields["publisher"]).isEqualTo("Litres")
    }

    @Test
    fun calendarDateBecomesIsoDate() {
        val record = record(
            "<дата-прочтения calendar=\"gregorian\"><year>2023</year><month>02</month><day>14</day></дата-прочтения>",
        )

        assertThat(record.fields["дата-прочтения"]).isEqualTo("2023-02-14")
    }

    @Test
    fun partialDateKeepsOnlyKnownParts() {
        // Review Focus #4: частичная дата не теряется.
        val yearOnly = record("<дата-прочтения calendar=\"gregorian\"><year>2023</year></дата-прочтения>")
        val yearMonth = record("<дата-прочтения calendar=\"gregorian\"><year>2023</year><month>02</month></дата-прочтения>")

        assertThat(yearOnly.fields["дата-прочтения"]).isEqualTo("2023")
        assertThat(yearMonth.fields["дата-прочтения"]).isEqualTo("2023-02")
    }

    @Test
    fun userFieldGoesToFields() {
        val record = record("<место-покупки>Лабиринт</место-покупки>")

        assertThat(record.fields["место-покупки"]).isEqualTo("Лабиринт")
    }

    @Test
    fun unknownFieldGoesToExtraWithItsTitle() {
        val record = record(
            fields = "<чтец>Иванова</чтец>",
            declarations = "<field name=\"чтец\" title=\"Чтец\" type=\"1\"/>",
        )

        assertThat(record.extra["чтец"]).isEqualTo(ExtraField(title = "Чтец", value = "Иванова"))
        assertThat(record.fields).doesNotContainKey("чтец")
    }

    @Test
    fun serviceEntryIdIsSkipped() {
        val record = record("<id>42</id><title>Дюна</title>")

        assertThat(record.fields).doesNotContainKey("id")
        assertThat(record.extra).doesNotContainKey("id")
        assertThat(record.fields["title"]).isEqualTo("Дюна")
    }

    @Test
    fun booleanFieldKeepsItsText() {
        val record = record("<read>true</read>")

        assertThat(record.fields["read"]).isEqualTo("true")
    }

    @Test
    fun nonBookCollectionIsRejected() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tellico xmlns="http://periapsis.org/tellico/">
              <collection title="Статьи" type="3">
                <entry id="1"><title>Статья</title></entry>
              </collection>
            </tellico>
        """.trimIndent()

        val error = runCatching { reader.read(xml.toByteArray()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("Это не коллекция книг")
    }

    @Test
    fun brokenXmlFailsWithParserCause() {
        val error = runCatching { reader.read("<tellico><collection".toByteArray()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasCauseThat().isNotNull()
    }

    @Test
    fun externalEntitiesAreNotExpanded() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tellico [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <tellico xmlns="http://periapsis.org/tellico/">
              <collection title="Мои книги" type="2">
                <entry id="1"><title>&xxe;</title></entry>
              </collection>
            </tellico>
        """.trimIndent()

        val record = reader.read(xml.toByteArray()).single()

        assertThat(record.fields.values.joinToString("\n")).doesNotContain("root:")
    }

    @Test
    fun readsFromFile() {
        val file = File.createTempFile("tellico-", ".xml")
        try {
            file.writeText(
                """
                <?xml version="1.0" encoding="UTF-8"?>
                <tellico xmlns="http://periapsis.org/tellico/">
                  <collection title="Мои книги" type="2">
                    <entry id="1"><title>Дюна</title></entry>
                  </collection>
                </tellico>
                """.trimIndent(),
            )

            val record = reader.read(file).single()

            assertThat(record.fields["title"]).isEqualTo("Дюна")
        } finally {
            file.delete()
        }
    }

    /** Одна запись коллекции книг: [fields] — тело `<entry>`, [declarations] — объявления `<field>`. */
    private fun record(fields: String, declarations: String = ""): TellicoRecord {
        val xml = TEMPLATE
            .replace("{declarations}", declarations)
            .replace("{fields}", fields)
        return reader.read(xml.toByteArray()).single()
    }

    private companion object {
        val TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tellico xmlns="http://periapsis.org/tellico/">
              <collection title="Мои книги" type="2">
                <fields>{declarations}</fields>
                <entry id="1">{fields}</entry>
              </collection>
            </tellico>
        """.trimIndent()
    }
}
