package ru.antonkw.kwbooktrack.data.tellico

import com.google.common.truth.Truth.assertThat
import org.junit.Assume.assumeTrue
import org.junit.Test
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

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
    fun unsupportedCollectionIsRejected() {
        // type=1 — библиография: у неё год называется `year`, а не `pub_year`,
        // и без разбора она превратилась бы в книги.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tellico xmlns="http://periapsis.org/tellico/">
              <collection title="Статьи" type="1">
                <entry id="1"><title>Статья</title></entry>
              </collection>
            </tellico>
        """.trimIndent()

        val error = runCatching { reader.read(xml.toByteArray()) }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalStateException::class.java)
        assertThat(error).hasMessageThat().contains("Это не коллекция книг и не фильмов")
    }

    @Test
    fun readsMovieCollection() {
        val record = record(
            fields = "<title>Малхолланд Драйв</title><running-time>147</running-time>",
            collectionType = 3,
        )

        assertThat(record.collectionType).isEqualTo(3)
        assertThat(record.fields["title"]).isEqualTo("Малхолланд Драйв")
        assertThat(record.fields["running-time"]).isEqualTo("147")
    }

    @Test
    fun readsBothCollections() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tellico xmlns="http://periapsis.org/tellico/">
              <collection title="Мои книги" type="2">
                <entry id="1"><title>Дюна</title></entry>
              </collection>
              <collection title="Мои фильмы" type="3">
                <entry id="2"><title>Малхолланд Драйв</title></entry>
              </collection>
            </tellico>
        """.trimIndent()

        val records = reader.read(xml.toByteArray())

        assertThat(records).hasSize(2)
        assertThat(records.map { it.collectionType }).containsExactly(2, 3).inOrder()
        assertThat(records.map { it.fields["title"] }).containsExactly("Дюна", "Малхолланд Драйв").inOrder()
    }

    @Test
    fun skipsUnsupportedCollectionBesideSupportedOne() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tellico xmlns="http://periapsis.org/tellico/">
              <collection title="Статьи" type="1">
                <entry id="1"><title>Статья</title></entry>
              </collection>
              <collection title="Мои книги" type="2">
                <entry id="2"><title>Дюна</title></entry>
              </collection>
            </tellico>
        """.trimIndent()

        val records = reader.read(xml.toByteArray())

        assertThat(records).hasSize(1)
        assertThat(records.single().fields["title"]).isEqualTo("Дюна")
    }

    @Test
    fun castColumnsJoinedWithDoubleColon() {
        // Реальная коллекция фильмов пишет актёров «по столбцам»: актёр и
        // роль отдельными <column>. Склеивать их без разделителя нельзя —
        // теряется граница ячеек.
        val record = record(
            fields = "<casts><cast><column>Уоттс, Наоми</column><column>Роль</column></cast></casts>",
            declarations = "<field name=\"cast\" type=\"8\"><prop name=\"columns\">2</prop></field>",
            collectionType = 3,
        )

        assertThat(record.fields["cast"]).isEqualTo("Уоттс, Наоми::Роль")
    }

    @Test
    fun castWithSingleColumnKeepsOnlyActor() {
        val record = record(
            fields = "<casts><cast><column>Уоттс, Наоми</column></cast></casts>",
            declarations = "<field name=\"cast\" type=\"8\"><prop name=\"columns\">2</prop></field>",
            collectionType = 3,
        )

        assertThat(record.fields["cast"]).isEqualTo("Уоттс, Наоми")
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

    @Test
    fun readsRealTellicoCollection() {
        // Настоящая коллекция лежит в локальном архиве `tellico.zip` (в git его
        // нет — данные пользователя): тест проходит там, где архив есть, и
        // пропускается там, где его нет.
        val resource = javaClass.classLoader!!.getResource("tellico.zip")
        assumeTrue("tellico.zip present", resource != null)
        val xml = realTellicoXml(resource!!)
        assumeTrue("tellico.xml present", xml != null)

        val records = reader.read(xml!!)

        assertThat(records.size).isGreaterThan(500)
        val first = records.first()
        assertThat(first.fields["title"]).isNotEmpty()
        assertThat(first.fields["cover"]).isNotEmpty()
        // Дата Tellico — ISO, а не «20230214»: склейка года, месяца и дня.
        assertThat(first.fields["дата-прочтения"]).matches("\\d{4}-\\d{2}-\\d{2}")
        assertThat(first.fields["cdate"]).matches("\\d{4}-\\d{2}-\\d{2}")
        // Контейнер «Ключевых слов» — значения склеены "; ".
        assertThat(records.count { it.fields.containsKey("keyword") }).isGreaterThan(100)
    }

    /** `tellico.xml` из локального архива с настоящей коллекцией. */
    private fun realTellicoXml(archive: java.net.URL): ByteArray? {
        val tc = ZipInputStream(archive.openStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name.endsWith("Books.tc")) return@use zip.readBytes()
                entry = zip.nextEntry
            }
            null
        } ?: return null
        return ZipInputStream(tc.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (entry.name == "tellico.xml") return@use zip.readBytes()
                entry = zip.nextEntry
            }
            null
        }
    }

    /** Одна запись коллекции: [fields] — тело `<entry>`, [declarations] — объявления `<field>`. */
    private fun record(fields: String, declarations: String = "", collectionType: Int = 2): TellicoRecord {
        val xml = TEMPLATE
            .replace("{declarations}", declarations)
            .replace("{fields}", fields)
            .replace("{type}", collectionType.toString())
        return reader.read(xml.toByteArray()).single()
    }

    private companion object {
        val TEMPLATE = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tellico xmlns="http://periapsis.org/tellico/">
              <collection title="Мои книги" type="{type}">
                <fields>{declarations}</fields>
                <entry id="1">{fields}</entry>
              </collection>
            </tellico>
        """.trimIndent()
    }
}
