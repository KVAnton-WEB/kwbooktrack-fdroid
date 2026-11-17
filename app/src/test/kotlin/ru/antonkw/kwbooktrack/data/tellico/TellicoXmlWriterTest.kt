package ru.antonkw.kwbooktrack.data.tellico

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.snapshot.BookSnapshot
import ru.antonkw.kwbooktrack.data.snapshot.NoteSnapshot

/**
 * Писатель `tellico.xml`: файл должен остаться валидным XML и не терять
 * рецензии.
 */
class TellicoXmlWriterTest {

    @Test
    fun controlCharactersAreRemoved() {
        // Символы, запрещённые в XML 1.0, делают невалидным весь документ —
        // а не одно поле. Один такой символ в заметке убивал бы весь экспорт.
        val xml = TellicoXmlWriter.write(
            listOf(entry(notes = listOf(NoteSnapshot(kind = NoteKind.NOTE, body = "до\u000Bпосле")))),
        )

        assertThat(xml.contains('\u000B')).isFalse()
        assertThat(xml).contains("допосле")
    }

    @Test
    fun allReviewsAreWritten() {
        // Раньше в файл попадала только первая рецензия: остальные терялись, а
        // вместе с ними «съезжала» привязка фото (индекс фото считался по
        // полному списку заметок).
        val xml = TellicoXmlWriter.write(
            listOf(
                entry(
                    notes = listOf(
                        NoteSnapshot(kind = NoteKind.REVIEW, body = "первая", createdAt = 1L),
                        NoteSnapshot(kind = NoteKind.REVIEW, body = "вторая", createdAt = 2L),
                    ),
                ),
            ),
        )

        assertThat(xml).contains("первая")
        assertThat(xml).contains("вторая")
    }

    @Test
    fun reviewsWithNewlinesStayOneReview() {
        // Перевод строки внутри рецензии экранируется — иначе импорт разделил
        // бы её на две заметки и сдвинул привязку фото.
        val xml = TellicoXmlWriter.write(
            listOf(entry(notes = listOf(NoteSnapshot(kind = NoteKind.REVIEW, body = "строка\nвторая")))),
        )

        assertThat(xml).contains("\\n")
    }

    @Test
    fun numericPriceIsWrittenWithDot() {
        // `pur_price` у Tellico — числовое поле: запятая обрезала бы дробную
        // часть у того, кто читает файл.
        val xml = TellicoXmlWriter.write(
            listOf(entry(book = BookEntity(title = "Дюна", author = "Герберт", purchasePrice = 1234.5, createdAt = 0L, updatedAt = 0L))),
        )

        assertThat(xml).contains("<pur_price>1234.5</pur_price>")
    }

    private fun entry(
        book: BookEntity = BookEntity(title = "Дюна", author = "Герберт", createdAt = 0L, updatedAt = 0L),
        notes: List<NoteSnapshot> = emptyList(),
    ) = TcEntry(
        snapshot = BookSnapshot(book = book, notes = notes),
        coverArchiveName = null,
    )
}
