package ru.antonkw.kwbooktrack.data

import ru.antonkw.kwbooktrack.data.storage.CoverStorage
import ru.antonkw.kwbooktrack.data.storage.PhotoStorage
import java.io.InputStream

/** Stub CoverStorage для unit-тестов: ничего не записывает, ничего не удаляет. */
object NoOpCoverStorage : CoverStorage {
    override fun saveTemp(source: InputStream, extension: String): String = ""
    override fun promoteToBook(bookId: Long, tempPath: String?): String? = tempPath
    override fun deleteForBook(bookId: Long) = Unit
}

/** Stub PhotoStorage для unit-тестов. */
object NoOpPhotoStorage : PhotoStorage {
    override fun saveForNote(noteId: Long, source: InputStream, extension: String): String = ""
    override fun deleteForNote(noteId: Long) = Unit
    override fun deleteForBook(bookId: Long) = Unit
}