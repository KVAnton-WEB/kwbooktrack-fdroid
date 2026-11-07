package ru.antonkw.kwbooktrack.data.tellico

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Инструментальный тест ридера Tellico: только на устройстве видно разницу
 * между парсером JVM (Xerces) и парсером Android (Harmony).
 *
 * Harmony-DOM отвергает имя элемента с не-ASCII буквами, а родной формат
 * Tellico полон кириллических полей (`<рецензия>`, `<цитатыs>`, `<дата-прочтения>`).
 * На JVM такой файл разбирается без ошибок, поэтому проверка живёт здесь.
 */
@RunWith(AndroidJUnit4::class)
class TellicoXmlReaderDeviceTest {

    private val reader = TellicoXmlReader()

    @Test
    fun readsCyrillicElementNames() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <tellico xmlns="http://periapsis.org/tellico/" syntaxVersion="12">
             <collection title="Мои книги" type="2">
              <fields>
               <field category="Рецензия" description="" flags="0" format="4" name="рецензия" title="Рецензия" type="2"/>
              </fields>
              <entry id="1">
               <title>Властелин Колец</title>
               <authors><author>Толкин, Джон</author></authors>
               <рецензия>Отличная книга</рецензия>
               <цитатыs><цитаты>1::такие истории не кончаются
            3::кто не умеет расстаться</цитаты></цитатыs>
              </entry>
             </collection>
            </tellico>
        """.trimIndent()

        val records = reader.read(xml.toByteArray())

        assertEquals(1, records.size)
        val record = records.first()
        assertEquals("Властелин Колец", record.fields["title"])
        assertEquals("Толкин, Джон", record.fields["author"])
        assertEquals("Отличная книга", record.fields["рецензия"])
        assertEquals(
            "1::такие истории не кончаются\n3::кто не умеет расстаться",
            record.fields["цитаты"],
        )
    }

    @Test
    fun readsRealTellicoShape() {
        // Настоящий файл Tellico: DOCTYPE со ссылкой на внешний DTD (сеть трогать
        // нельзя), пространство имён и кириллические имена полей.
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tellico PUBLIC '-//Robby Stephenson/DTD Tellico V12.0//EN' 'http://periapsis.org/tellico/dtd/v12/tellico.dtd'>
            <tellico xmlns="http://periapsis.org/tellico/" syntaxVersion="12">
             <collection title="Мои книги" type="2">
              <fields>
               <field category="Общее" description="" flags="0" format="1" name="title" title="Заголовок" type="1"/>
               <field category="Покупка" description="" flags="2" format="4" name="место-покупки" title="Место покупки" type="3"/>
              </fields>
              <entry id="1">
               <title>Властелин Колец</title>
               <место-покупки>chitai-gorod.ru</место-покупки>
               <pur_date calendar="gregorian"><year>2023</year><month>08</month><day>04</day></pur_date>
              </entry>
             </collection>
            </tellico>
        """.trimIndent()

        val record = reader.read(xml.toByteArray()).first()

        assertEquals("Властелин Колец", record.fields["title"])
        assertEquals("chitai-gorod.ru", record.fields["место-покупки"])
        assertEquals("2023-08-04", record.fields["pur_date"])
    }

    /**
     * Форма настоящей коллекции Tellico.
     *
     * Настоящий файл в git не лежит (это данные пользователя), поэтому здесь
     * его форма: контейнеры многозначных полей, таблица, дата с атрибутом
     * `calendar`, блок обложек и DOCTYPE с внешней DTD.
     */
    @Test
    fun readsRealTellicoCollectionShape() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE tellico PUBLIC '-//Robby Stephenson/DTD Tellico V12.0//EN' 'http://periapsis.org/tellico/dtd/v12/tellico.dtd'>
            <tellico xmlns="http://periapsis.org/tellico/" syntaxVersion="12">
             <collection title="Мои книги" type="2">
              <fields>
               <field category="Общее" description="" flags="0" format="1" name="title" title="Заголовок" type="1"/>
               <field category="Классификация" description="" flags="7" format="4" name="keyword" title="Ключевые слова" type="1"/>
               <field category="Цитаты" description="" flags="1" format="4" name="цитаты" title="Цитаты" type="2">
                <prop name="column1">страница</prop>
                <prop name="column2">Цитата</prop>
                <prop name="columns">2</prop>
               </field>
              </fields>
              <entry id="1">
               <title>Властелин Колец</title>
               <authors><author>Толкин, Джон</author></authors>
               <genres><genre>фэнтези</genre><genre>эпическое фэнтези</genre></genres>
               <keywords><keyword>классика</keyword><keyword>эпик</keyword></keywords>
               <цитатыs><цитаты>1::первая
            3::вторая</цитаты></цитатыs>
               <дата-прочтения calendar="gregorian"><year>2023</year><month>02</month><day>14</day></дата-прочтения>
              </entry>
              <images>
               <image id="0dcebf7e7711bd7154db82b21cac7f61.jpeg" format="jpeg"/>
              </images>
             </collection>
            </tellico>
        """.trimIndent()

        val record = reader.read(xml.toByteArray()).single()

        assertEquals("Властелин Колец", record.fields["title"])
        assertEquals("Толкин, Джон", record.fields["author"])
        assertEquals("фэнтези; эпическое фэнтези", record.fields["genre"])
        assertEquals("классика; эпик", record.fields["keyword"])
        assertEquals("1::первая\n3::вторая", record.fields["цитаты"])
        assertEquals("2023-02-14", record.fields["дата-прочтения"])
    }

    /**
     * Большая коллекция: настоящий `tellico.xml` — это 2 МБ и 600+ записей.
     *
     * Парсер держит документ в памяти целиком — на телефоне это проверка
     * размера и скорости, а не только разбора.
     */
    @Test
    fun readsBigCollection() {
        val entries = (1..600).joinToString("\n") { index ->
            """
               <entry id="$index">
                <title>Книга $index</title>
                <authors><author>Автор $index</author></authors>
                <keywords><keyword>книга</keyword><keyword>номер $index</keyword></keywords>
               </entry>
            """.trimIndent()
        }
        val xml = buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<tellico xmlns=\"http://periapsis.org/tellico/\" syntaxVersion=\"12\">\n")
            append(" <collection title=\"Мои книги\" type=\"2\">\n")
            append(entries)
            append("\n </collection>\n</tellico>\n")
        }

        val records = reader.read(xml.toByteArray())

        assertEquals(600, records.size)
        assertEquals("Книга 600", records.last().fields["title"])
        assertEquals("книга; номер 600", records.last().fields["keyword"])
    }
}
