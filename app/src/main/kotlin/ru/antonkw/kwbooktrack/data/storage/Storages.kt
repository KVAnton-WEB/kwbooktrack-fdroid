package ru.antonkw.kwbooktrack.data.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Абстракция над каталогом обложек в `filesDir/covers/`.
 * Реализация хранит ровно один файл на книгу с именем `{bookId}.{ext}`.
 * Temp-файлы от `attachCover` живут отдельно (`cover_{tempId}.{ext}`) и при `save()`
 * переименовываются в финальные.
 */
interface CoverStorage {
    /** Скопировать байты в `covers/cover_{tempId}.{ext}`, вернуть абсолютный путь. */
    fun saveTemp(source: InputStream, extension: String): String

    /** Переименовать temp-файл по `bookId` (или скопировать, если уже под другим именем). */
    fun promoteToBook(bookId: Long, tempPath: String?): String?

    /** Удалить все файлы обложки для данной книги (по префиксу). */
    fun deleteForBook(bookId: Long)
}

@Singleton
class FileCoverStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) : CoverStorage {
    private val coversDir: File = File(context.filesDir, "covers").apply { mkdirs() }

    override fun saveTemp(source: InputStream, extension: String): String {
        val tempId = -System.currentTimeMillis()
        val target = File(coversDir, "cover_$tempId.${extension.ifEmpty { "jpg" }}")
        source.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target.absolutePath
    }

    override fun promoteToBook(bookId: Long, tempPath: String?): String? {
        if (tempPath == null) return null
        val src = File(tempPath)
        if (!src.exists()) return tempPath
        val ext = src.extension.ifEmpty { "jpg" }
        val target = File(coversDir, "$bookId.$ext")
        return try {
            // Удаляем ВСЕ предыдущие обложки этой книги (могут быть с другим расширением).
            deleteForBook(bookId)
            if (src.absolutePath != target.absolutePath) {
                src.copyTo(target, overwrite = true)
                src.delete()
            }
            target.absolutePath
        } catch (_: Exception) {
            tempPath
        }
    }

    override fun deleteForBook(bookId: Long) {
        val prefix = "$bookId."
        coversDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            ?.forEach { it.delete() }
    }
}

/**
 * Абстракция над каталогом фото в `filesDir/photos/`.
 * Файлы имеют вид `note_{noteId}_{timestamp}.{ext}`.
 */
interface PhotoStorage {
    fun saveForNote(noteId: Long, source: InputStream, extension: String): String
    fun deleteForNote(noteId: Long)
    fun deleteForBook(bookId: Long)
}

@Singleton
class FilePhotoStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhotoStorage {
    private val photosDir: File = File(context.filesDir, "photos").apply { mkdirs() }

    override fun saveForNote(noteId: Long, source: InputStream, extension: String): String {
        val target = File(photosDir, "note_${noteId}_${System.currentTimeMillis()}.${extension.ifEmpty { "jpg" }}")
        source.use { input ->
            target.outputStream().use { input.copyTo(it) }
        }
        return target.absolutePath
    }

    override fun deleteForNote(noteId: Long) {
        val prefix = "note_${noteId}_"
        photosDir.listFiles { f -> f.isFile && f.name.startsWith(prefix) }
            ?.forEach { it.delete() }
    }

    override fun deleteForBook(bookId: Long) {
        // Намеренно пустая: фото удаляются через deleteForNote при удалении заметок.
        // PhotoEntity использует ON DELETE SET NULL для noteId, поэтому при удалении
        // книги (CASCADE → notes CASCADE → photos SET NULL) фото остаются как осиротевшие.
        // Вычищать их предполагается через явный purge в BookRepository.delete.
    }
}