package ru.antonkw.kwbooktrack.data.importer

import com.google.common.truth.Truth.assertThat
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVRecord
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import java.io.StringReader

@RunWith(RobolectricTestRunner::class)
class TellicoBookMapperTest {

    @Test
    fun mapsRussianFieldsToBookEntity() {
        val record = csvRecord(
            "Заголовок" to "Властелин Колец",
            "Автор" to "Толкин, Джон",
            "ISBN#" to "978-5-04168446-4",
            "Количество страниц" to "2691",
            "Язык" to "ru",
            "Жанр" to "фэнтези; эпическое фэнтези",
            "Издательство" to "Litres",
            "Год издания" to "2019",
            "Рейтинг" to "5",
            "Прочитано" to "true",
            "Дата прочтения" to "15.09.2026",
            "Лицевая сторона обложки" to "0dcebf7e7711bd7154db82b21cac7f61.jpeg",
        )

        val mapped = TellicoBookMapper().map(record)

        assertThat(mapped).isNotNull()
        val book = mapped!!.book
        assertThat(book.title).isEqualTo("Властелин Колец")
        assertThat(book.author).isEqualTo("Толкин, Джон")
        assertThat(book.isbn13).isEqualTo("9785041684464")
        assertThat(book.pageCount).isEqualTo(2691)
        assertThat(book.language).isEqualTo("ru")
        assertThat(book.genre).isEqualTo("фэнтези; эпическое фэнтези")
        assertThat(book.publisher).isEqualTo("Litres")
        assertThat(book.year).isEqualTo(2019)
        assertThat(book.rating).isEqualTo(5)
        assertThat(book.status).isEqualTo(BookStatus.FINISHED)
        assertThat(book.source).isEqualTo(BookSource.TELLICO)
        assertThat(book.finishedAt).isNotNull()
    }

    @Test
    fun returnsNullWhenBothTitleAndAuthorMissing() {
        val record = csvRecord(
            "Заголовок" to "",
            "Автор" to "",
            "Прочитано" to "no",
        )
        val book = TellicoBookMapper().map(record)
        assertThat(book).isNull()
    }

    @Test
    fun wishlistByDefault() {
        val record = csvRecord(
            "Заголовок" to "Dune",
            "Автор" to "Frank Herbert",
            "Прочитано" to "",
        )
        val book = TellicoBookMapper().map(record)!!.book
        assertThat(book.status).isEqualTo(BookStatus.WISHLIST)
    }

    @Test
    fun isbnShorterThan13IsDropped() {
        val record = csvRecord(
            "Заголовок" to "Foo",
            "Автор" to "Bar",
            "ISBN#" to "12345",
        )
        val book = TellicoBookMapper().map(record)!!.book
        assertThat(book.isbn13).isNull()
    }

    @Test
    fun ratingOutOfRangeIsDropped() {
        val record = csvRecord(
            "Заголовок" to "Foo",
            "Автор" to "Bar",
            "Рейтинг" to "10",
        )
        val book = TellicoBookMapper().map(record)!!.book
        assertThat(book.rating).isNull()
    }

    @Test
    fun mapsExtendedTellicoFields() {
        // v5 — all the new fields parsed from a representative row.
        val record = csvRecord(
            "Заголовок" to "Hyperion",
            "Автор" to "Dan Simmons",
            "Подзаголовок" to "Песни Гипериона",
            "Переплёт" to "Твёрдый",
            "Место покупки" to "Лабиринт",
            "Цена покупки" to "1299",
            "Серия" to "Гиперион",
            "Номер серии" to "1",
            "Состояние" to "Новое",
            "Переводчик" to "А. Клементьев",
            "Подарок" to "да",
            "Одолжено" to "нет",
            "Продано?" to "нет",
            "Аннотация" to "Далёкое будущее...",
            "Дата прочтения" to "01.01.2025",
            "Усл. печ. л." to "32",
            "Язык оригинала" to "en",
            "Оригинальное название" to "Hyperion",
            "Год сдачи в печать" to "2018",
        )

        val book = TellicoBookMapper().map(record)!!.book
        assertThat(book.subtitle).isEqualTo("Песни Гипериона")
        assertThat(book.binding).isEqualTo("Твёрдый")
        assertThat(book.purchasePlace).isEqualTo("Лабиринт")
        assertThat(book.purchasePrice).isEqualTo(1299.0)
        assertThat(book.series).isEqualTo("Гиперион")
        assertThat(book.seriesNumber).isEqualTo("1")
        assertThat(book.condition).isEqualTo("Новое")
        assertThat(book.translator).isEqualTo("А. Клементьев")
        assertThat(book.isGift).isTrue()
        assertThat(book.isLent).isFalse()
        assertThat(book.isSold).isFalse()
        assertThat(book.annotation).isEqualTo("Далёкое будущее...")
        assertThat(book.printPages).isEqualTo(32)
        assertThat(book.originalLanguage).isEqualTo("en")
        assertThat(book.originalTitle).isEqualTo("Hyperion")
        assertThat(book.printYear).isEqualTo(2018)
    }

    @Test
    fun extendedFieldsAreNullWhenCellsEmpty() {
        val record = csvRecord(
            "Заголовок" to "Foo",
            "Автор" to "Bar",
        )
        val book = TellicoBookMapper().map(record)!!.book
        assertThat(book.binding).isNull()
        assertThat(book.series).isNull()
        assertThat(book.isGift).isFalse()
        assertThat(book.printYear).isNull()
    }

    private fun csvRecord(vararg pairs: Pair<String, String>): CSVRecord {
        val headers = pairs.map { it.first }
        val values = pairs.map { (k, v) -> if (v.contains(';') || v.contains('"')) "\"$v\"" else v }
        val csv = headers.joinToString(";") + "\n" + values.joinToString(";") + "\n"
        val format = CSVFormat.Builder.create(CSVFormat.EXCEL)
            .setDelimiter(';')
            .setQuote('"')
            .setHeader()
            .setSkipHeaderRecord(true)
            .build()
        return CSVParser(StringReader(csv), format).use { it.records.first() }
    }
}
