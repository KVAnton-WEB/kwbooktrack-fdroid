package ru.antonkw.kwbooktrack.data.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField
import ru.antonkw.kwbooktrack.data.snapshot.PhotoSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.SessionSnapshot
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.time.LocalDate

/**
 * Маппер Tellico: [TellicoRecord] → снимок книги.
 *
 * Ключи полей — внутренние имена Tellico (`title`, `место-покупки`), а не
 * русские заголовки: именно они лежат в `tellico.xml` и в `.tc`.
 */
class TellicoBookMapperTest {

    private val mapper = TellicoBookMapper()

    @Test
    fun mapsTitleAndAuthor() {
        val snapshot = mapper.map(record("title" to "Властелин Колец", "author" to "Толкин, Джон"))!!

        assertThat(snapshot.book.title).isEqualTo("Властелин Колец")
        assertThat(snapshot.book.author).isEqualTo("Толкин, Джон")
        assertThat(snapshot.book.source).isEqualTo(BookSource.TELLICO)
    }

    @Test
    fun returnsNullWhenTitleAndAuthorMissing() {
        assertThat(mapper.map(record("title" to "", "author" to ""))).isNull()
    }

    @Test
    fun missingTitleAndAuthorGetPlaceholders() {
        val onlyAuthor = mapper.map(record("author" to "Кто-то"))!!.book
        val onlyTitle = mapper.map(record("title" to "Дюна"))!!.book

        assertThat(onlyAuthor.title).isEqualTo("(без названия)")
        assertThat(onlyAuthor.author).isEqualTo("Кто-то")
        assertThat(onlyTitle.title).isEqualTo("Дюна")
        assertThat(onlyTitle.author).isEqualTo("(автор неизвестен)")
    }

    @Test
    fun isbnWithTenDigitsIsKept() {
        val book = mapper.map(record("title" to "Foo", "author" to "Bar", "isbn" to "5-04168446-4"))!!.book

        assertThat(book.isbn13).isEqualTo("5041684464")
    }

    @Test
    fun isbnWithXCheckDigitIsKept() {
        val book = mapper.map(record("title" to "Foo", "author" to "Bar", "isbn" to "5-04089273-X"))!!.book

        assertThat(book.isbn13).isEqualTo("504089273X")
    }

    @Test
    fun isbnOfOtherLengthIsDropped() {
        val book = mapper.map(record("title" to "Foo", "author" to "Bar", "isbn" to "12345"))!!.book

        assertThat(book.isbn13).isNull()
    }

    @Test
    fun isbnWithThirteenDigitsIsKept() {
        val book = mapper.map(record("title" to "Foo", "author" to "Bar", "isbn" to "978-5-04168446-4"))!!.book

        assertThat(book.isbn13).isEqualTo("9785041684464")
    }

    @Test
    fun ratingOutOfRangeIsDropped() {
        val book = mapper.map(record("title" to "Foo", "author" to "Bar", "rating" to "10"))!!.book

        assertThat(book.rating).isNull()
    }

    @Test
    fun mapsExtendedTellicoFields() {
        val book = mapper.map(
            record(
                "title" to "Hyperion",
                "author" to "Dan Simmons",
                "subtitle" to "Песни Гипериона",
                "binding" to "Твёрдый",
                "место-покупки" to "Лабиринт",
                "series" to "Гиперион",
                "series_num" to "1",
                "condition" to "Новое",
                "translator" to "А. Клементьев",
                "gift" to "да",
                "loaned" to "нет",
                "продано-new" to "true",
                "pur_price" to "1299",
                "pages" to "2691",
                "language" to "ru",
                "genre" to "фэнтези",
                "publisher" to "Litres",
                "pub_year" to "2019",
                "cr_year" to "1984; 1986; 1988",
                "усл.-печ.-л." to "32,5",
                "язык-оригинала" to "en",
                "оригинальное-название" to "Hyperion",
                "cover" to "0dcebf7e7711bd7154db82b21cac7f61.jpeg",
            ),
        )!!.book

        assertThat(book.subtitle).isEqualTo("Песни Гипериона")
        assertThat(book.binding).isEqualTo("Твёрдый")
        assertThat(book.purchasePlace).isEqualTo("Лабиринт")
        assertThat(book.series).isEqualTo("Гиперион")
        assertThat(book.seriesNumber).isEqualTo("1")
        assertThat(book.condition).isEqualTo("Новое")
        assertThat(book.translator).isEqualTo("А. Клементьев")
        assertThat(book.isGift).isTrue()
        assertThat(book.isLent).isFalse()
        assertThat(book.isSold).isTrue()
        assertThat(book.purchasePrice).isEqualTo(1299.0)
        assertThat(book.pageCount).isEqualTo(2691)
        assertThat(book.language).isEqualTo("ru")
        assertThat(book.genre).isEqualTo("фэнтези")
        assertThat(book.publisher).isEqualTo("Litres")
        assertThat(book.year).isEqualTo(2019)
        // «Год сдачи в печать» может быть списком — берём первый год.
        assertThat(book.printYear).isEqualTo(1984)
        assertThat(book.printPages).isEqualTo(32.5)
        assertThat(book.originalLanguage).isEqualTo("en")
        assertThat(book.originalTitle).isEqualTo("Hyperion")
        assertThat(book.coverPath).isEqualTo("0dcebf7e7711bd7154db82b21cac7f61.jpeg")
    }

    @Test
    fun annotationHtmlBecomesMarkdown() {
        val book = mapper.map(
            record(
                "title" to "Дюна",
                "author" to "Герберт",
                "comments" to "<p>Пустыня и <b>песок</b>&nbsp;везде.</p><p>Вторая.</p>",
            ),
        )!!.book

        assertThat(book.annotation).isEqualTo("Пустыня и **песок** везде.\n\nВторая.")
    }

    @Test
    fun statusIsWishlistByDefault() {
        val book = mapper.map(record("title" to "Dune", "author" to "Frank Herbert"))!!.book

        assertThat(book.status).isEqualTo(BookStatus.WISHLIST)
    }

    @Test
    fun abandonedFlagGivesAbandonedStatus() {
        val book = mapper.map(record("title" to "X", "author" to "Y", "брошено" to "true"))!!.book

        assertThat(book.status).isEqualTo(BookStatus.ABANDONED)
    }

    @Test
    fun readFlagGivesFinishedStatus() {
        val book = mapper.map(record("title" to "X", "author" to "Y", "read" to "true"))!!.book

        assertThat(book.status).isEqualTo(BookStatus.FINISHED)
    }

    @Test
    fun partiallyReadFlagGivesReadingStatus() {
        val book = mapper.map(record("title" to "X", "author" to "Y", "прочитал-часть" to "true"))!!.book

        assertThat(book.status).isEqualTo(BookStatus.READING)
    }

    @Test
    fun abandonedWinsOverRead() {
        val book = mapper.map(
            record("title" to "X", "author" to "Y", "брошено" to "true", "read" to "true"),
        )!!.book

        assertThat(book.status).isEqualTo(BookStatus.ABANDONED)
    }

    @Test
    fun htmlInAnnotationReviewAndQuotesBecomesMarkdown() {
        // Выгрузка Tellico отдаёт эти поля HTML: цитаты разделяются тегом <br/>.
        // Без конвертации до деления они слиплись бы в одну заметку.
        val snapshot = mapper.map(
            record(
                "title" to "Дюна",
                "author" to "Герберт",
                "comments" to "<p>Пустыня и <b>песок</b>&nbsp;везде.</p><p>Вторая.</p>",
                "рецензия" to "<p>Отличная <i>книга</i>.</p>",
                "цитаты" to "<p>Первая цитата.</p><p>Вторая &laquo;цитата&raquo;.</p>",
            ),
        )!!

        assertThat(snapshot.book.annotation).isEqualTo("Пустыня и **песок** везде.\n\nВторая.")
        assertThat(snapshot.notes.single { it.kind == NoteKind.REVIEW }.body).isEqualTo("Отличная *книга*.")
        assertThat(snapshot.notes.filter { it.kind == NoteKind.QUOTE }.map { it.body })
            .containsExactly("Первая цитата.", "Вторая «цитата».").inOrder()
    }

    @Test
    fun quotesWithPagePrefixBecomeQuoteNotes() {
        val snapshot = mapper.map(
            record("title" to "X", "author" to "Y", "цитаты" to "1::первая\n3::вторая\n4"),
        )!!

        val quotes = snapshot.notes.filter { it.kind == NoteKind.QUOTE }
        assertThat(quotes).hasSize(2)
        assertThat(quotes[0].body).isEqualTo("первая")
        assertThat(quotes[0].page).isEqualTo(1)
        assertThat(quotes[1].body).isEqualTo("вторая")
        assertThat(quotes[1].page).isEqualTo(3)
    }

    @Test
    fun quoteWithoutPageHasNoPage() {
        val snapshot = mapper.map(record("title" to "X", "author" to "Y", "цитаты" to "просто текст"))!!

        val quote = snapshot.notes.single { it.kind == NoteKind.QUOTE }
        assertThat(quote.body).isEqualTo("просто текст")
        assertThat(quote.page).isNull()
    }

    @Test
    fun quoteWithEmptyPagePrefixLosesOnlyThePrefix() {
        // `::текст` — так Tellico пишет цитату без страницы (в живой коллекции
        // есть строка `::Абстракция…`). Без разбора префикса каждое
        // «экспорт → импорт» добавляло бы к тексту ещё `::`.
        val snapshot = mapper.map(record("title" to "X", "author" to "Y", "цитаты" to "::текст"))!!

        val quote = snapshot.notes.single { it.kind == NoteKind.QUOTE }
        assertThat(quote.body).isEqualTo("текст")
        assertThat(quote.page).isNull()
    }

    @Test
    fun quoteInCsvFormatKeepsPage() {
        // Живая выгрузка Tellico пишет цитаты как «1:текст».
        val snapshot = mapper.map(record("title" to "X", "author" to "Y", "цитаты" to "1:текст"))!!

        val quote = snapshot.notes.single { it.kind == NoteKind.QUOTE }
        assertThat(quote.body).isEqualTo("текст")
        assertThat(quote.page).isEqualTo(1)
    }

    @Test
    fun quoteKeepsDoubleColonInsideText() {
        val snapshot = mapper.map(record("title" to "X", "author" to "Y", "цитаты" to "1::хвост с :: внутри"))!!

        val quote = snapshot.notes.single { it.kind == NoteKind.QUOTE }
        assertThat(quote.body).isEqualTo("хвост с :: внутри")
        assertThat(quote.page).isEqualTo(1)
    }

    @Test
    fun reviewBecomesReviewNote() {
        val snapshot = mapper.map(
            record("title" to "X", "author" to "Y", "рецензия" to "<p>Отличная <i>книга</i>.</p>"),
        )!!

        val review = snapshot.notes.single { it.kind == NoteKind.REVIEW }
        assertThat(review.body).isEqualTo("Отличная *книга*.")
    }

    @Test
    fun notesCarryPageAndDate() {
        val snapshot = mapper.map(
            record("title" to "X", "author" to "Y", "заметки" to "12::мысль::2024-03-01"),
        )!!

        val note = snapshot.notes.single { it.kind == NoteKind.NOTE }
        assertThat(note.page).isEqualTo(12)
        assertThat(note.body).isEqualTo("мысль")
        assertThat(note.createdAt).isEqualTo(LocalDate.parse("2024-03-01").toEpochMs())
    }

    @Test
    fun noteWithoutPageHasNoPage() {
        // Строка без разделителей — весь текст заметки, страницы в ней нет.
        val snapshot = mapper.map(record("title" to "X", "author" to "Y", "заметки" to "42"))!!

        val note = snapshot.notes.single { it.kind == NoteKind.NOTE }
        assertThat(note.body).isEqualTo("42")
        assertThat(note.page).isNull()
    }

    @Test
    fun noteWithDoubleColonInsideTextKeepsWholeText() {
        // Текст заметки может содержать `::` — он не должен обрезаться.
        val snapshot = mapper.map(
            record("title" to "X", "author" to "Y", "заметки" to "12::мысль :: продолжение::2024-03-01"),
        )!!

        val note = snapshot.notes.single { it.kind == NoteKind.NOTE }
        assertThat(note.body).isEqualTo("мысль :: продолжение")
        assertThat(note.page).isEqualTo(12)
        assertThat(note.createdAt).isEqualTo(LocalDate.parse("2024-03-01").toEpochMs())
    }

    @Test
    fun noteWithDoubleColonAndWithoutDateKeepsWholeText() {
        val snapshot = mapper.map(
            record("title" to "X", "author" to "Y", "заметки" to "12::std::string"),
        )!!

        val note = snapshot.notes.single { it.kind == NoteKind.NOTE }
        assertThat(note.body).isEqualTo("std::string")
        assertThat(note.page).isEqualTo(12)
        assertThat(note.createdAt).isNull()
    }

    @Test
    fun noteWithEmptyDateKeepsTextAndHasNoDate() {
        // Экспорт пишет пустую дату, когда её нет: `страница::текст::`.
        val snapshot = mapper.map(record("title" to "X", "author" to "Y", "заметки" to "12::мысль::"))!!

        val note = snapshot.notes.single { it.kind == NoteKind.NOTE }
        assertThat(note.body).isEqualTo("мысль")
        assertThat(note.page).isEqualTo(12)
        assertThat(note.createdAt).isNull()
    }

    @Test
    fun noteWithNumberAsTextIsNotTakenForDate() {
        // «1999» — текст, а не дата: иначе заметка потеряла бы текст.
        val snapshot = mapper.map(record("title" to "X", "author" to "Y", "заметки" to "12::1999"))!!

        val note = snapshot.notes.single { it.kind == NoteKind.NOTE }
        assertThat(note.body).isEqualTo("1999")
        assertThat(note.createdAt).isNull()
    }

    @Test
    fun flagsAcceptRussianWordsOfTheirFields() {
        // Старый импорт понимал «прочитано»/«брошено»/… — поведение не должно отличаться.
        assertThat(
            mapper.map(record("title" to "X", "author" to "Y", "read" to "прочитано"))!!.book.status,
        ).isEqualTo(BookStatus.FINISHED)
        assertThat(
            mapper.map(record("title" to "X", "author" to "Y", "брошено" to "брошено"))!!.book.status,
        ).isEqualTo(BookStatus.ABANDONED)
        assertThat(
            mapper.map(record("title" to "X", "author" to "Y", "прочитал-часть" to "прочитал-часть"))!!.book.status,
        ).isEqualTo(BookStatus.READING)

        val flags = mapper.map(
            record(
                "title" to "X",
                "author" to "Y",
                "gift" to "подарок",
                "loaned" to "одолжено",
                "продано-new" to "продано",
            ),
        )!!.book
        assertThat(flags.isGift).isTrue()
        assertThat(flags.isLent).isTrue()
        assertThat(flags.isSold).isTrue()
    }

    @Test
    fun partialDateIsCompletedToFirstDay() {
        val year = mapper.map(record("title" to "X", "author" to "Y", "дата-прочтения" to "2023"))!!.book
        val month = mapper.map(record("title" to "X", "author" to "Y", "дата-прочтения" to "2023-02"))!!.book

        assertThat(year.finishedAt).isEqualTo(LocalDate.parse("2023-01-01").toEpochMs())
        assertThat(month.finishedAt).isEqualTo(LocalDate.parse("2023-02-01").toEpochMs())
    }

    @Test
    fun purchaseDateIsParsedFromIso() {
        val book = mapper.map(record("title" to "X", "author" to "Y", "pur_date" to "2023-08-04"))!!.book

        assertThat(book.purchaseDate).isEqualTo(LocalDate.parse("2023-08-04").toEpochMs())
    }

    @Test
    fun sessionsAreParsedFromSixColumns() {
        val snapshot = mapper.map(
            record(
                "title" to "X",
                "author" to "Y",
                "сессии-чтения" to "1730000000000::1730000600000::1::20::600::читал",
            ),
        )!!

        assertThat(snapshot.sessions).containsExactly(
            SessionSnapshot(
                startedAt = 1730000000000L,
                endedAt = 1730000600000L,
                startPage = 1,
                endPage = 20,
                durationSec = 600L,
                note = "читал",
            ),
        )
    }

    @Test
    fun tagsAreSplitBySemicolon() {
        val snapshot = mapper.map(record("title" to "X", "author" to "Y", "теги" to "фэнтези; классика"))!!

        assertThat(snapshot.tags).containsExactly("фэнтези", "классика").inOrder()
    }

    @Test
    fun photosAreParsedFromThreeColumns() {
        val snapshot = mapper.map(
            record("title" to "X", "author" to "Y", "фото-заметок" to "photos/abc.jpg::2::1730000000000"),
        )!!

        assertThat(snapshot.photos).containsExactly(
            PhotoSnapshot(archivePath = "photos/abc.jpg", noteIndex = 2, createdAt = 1730000000000L),
        )
    }

    @Test
    fun foreignFieldsAreKeptAsExtraFields() {
        val snapshot = mapper.map(
            TellicoRecord(
                fields = mapOf("title" to "X"),
                extra = mapOf("чтец" to ExtraField("Чтец", "Иванова")),
            ),
        )!!

        assertThat(snapshot.extraFields).containsExactly("чтец", ExtraField("Чтец", "Иванова"))
    }

    @Test
    fun keywordsAreKeptAsExtraField() {
        // «Ключевые слова» — встроенное поле Tellico, в приложении для него нет
        // колонки. Без extra_fields они бы просто потерялись.
        val snapshot = mapper.map(record("title" to "X", "author" to "Y", "keyword" to "классика; эпик"))!!

        assertThat(snapshot.extraFields)
            .containsExactly("keyword", ExtraField("Ключевые слова", "классика; эпик"))
    }

    @Test
    fun nonNumericPriceIsKeptAsExtraField() {
        // Цена «по подписке» в число не переводится — но и не должна исчезать.
        val snapshot = mapper.map(record("title" to "X", "author" to "Y", "pur_price" to "по подписке"))!!

        assertThat(snapshot.book.purchasePrice).isNull()
        assertThat(snapshot.extraFields)
            .containsExactly("pur_price", ExtraField("Цена покупки", "по подписке"))
    }

    @Test
    fun createdAndUpdatedDatesComeFromTellico() {
        val book = mapper.map(
            record("title" to "X", "author" to "Y", "cdate" to "2023-02-14", "mdate" to "2024-10-28"),
        )!!.book

        assertThat(book.createdAt).isEqualTo(LocalDate.parse("2023-02-14").toEpochMs())
        assertThat(book.updatedAt).isEqualTo(LocalDate.parse("2024-10-28").toEpochMs())
    }

    private fun record(vararg pairs: Pair<String, String>): TellicoRecord =
        TellicoRecord(fields = pairs.toMap())
}
