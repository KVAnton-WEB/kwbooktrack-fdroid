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
}
