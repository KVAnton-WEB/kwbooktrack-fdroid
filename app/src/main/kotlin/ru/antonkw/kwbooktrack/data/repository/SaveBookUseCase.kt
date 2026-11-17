package ru.antonkw.kwbooktrack.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import javax.inject.Inject

/**
 * Сохраняет книгу и её дополнительные даты **одной транзакцией**.
 *
 * Без неё между вставкой книги и записью дат могла прерваться программа, и
 * книга осталась бы без дат перечитывания — а форма об этом не говорила.
 *
 * @return id записанной строки или -1, если запись не прошла (занятый ISBN:
 *   уникальный индекс по isbn13).
 */
class SaveBookUseCase @Inject constructor(
    private val database: KwBooktrackDatabase,
    private val bookRepository: BookRepository,
    private val mediaDateRepository: MediaDateRepository,
) {

    suspend operator fun invoke(book: BookEntity, dates: List<Long>): Long =
        database.withTransaction {
            try {
                val id = if (book.id > 0L) {
                    if (bookRepository.update(book) == 0) -1L else book.id
                } else {
                    bookRepository.insert(book)
                }
                if (id <= 0L) return@withTransaction -1L
                mediaDateRepository.replaceFor(id, MediaType.BOOKS, dates)
                id
            } catch (e: SQLiteConstraintException) {
                // «Книга уже есть» — это сообщение пользователю, а не крэш.
                -1L
            }
        }
}
