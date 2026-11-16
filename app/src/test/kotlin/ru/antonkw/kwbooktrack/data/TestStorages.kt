package ru.antonkw.kwbooktrack.data

import ru.antonkw.kwbooktrack.data.storage.CoverStorage
import ru.antonkw.kwbooktrack.data.storage.PhotoStorage
import java.io.InputStream

/** Stub CoverStorage для unit-тестов: ничего не записывает, ничего не удаляет. */
object NoOpCoverStorage : CoverStorage {
    override fun saveTemp(source: InputStream, extension: String): String = ""
    override fun promote(tempPath: String?): String? = tempPath
    override fun delete(path: String?) = Unit
}

/** Stub PhotoStorage для unit-тестов. */
object NoOpPhotoStorage : PhotoStorage {
    override fun saveForNote(noteId: Long, source: InputStream, extension: String): String = ""

    /**
     * Путь детерминированный — иначе дедупликация фото при импорте не работает:
     * каждый импорт создавал бы новый путь, и фото дублировались бы.
     */
    override fun saveNamed(name: String, source: InputStream, extension: String): String =
        "/photos/$name.${extension.ifEmpty { "jpg" }}"

    override fun deleteForNote(noteId: Long) = Unit
    override fun deleteForBook(bookId: Long) = Unit
    override fun deletePaths(paths: Collection<String>) = Unit
}