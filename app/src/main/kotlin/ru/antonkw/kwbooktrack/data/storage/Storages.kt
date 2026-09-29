package ru.antonkw.kwbooktrack.data.storage

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.util.UUID
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
        // UUID avoids races where two parallel saves within the same millisecond
        // would pick the same name and overwrite each other.
        val target = File(coversDir, "cover_${UUID.randomUUID()}.${extension.ifEmpty { "jpg" }}")
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

    /**
     * Delete specific photo files by absolute path. Used by BookRepository
     * to clean up the actual files that PhotoEntity.pathsForBook() returned
     * before the CASCADE delete wiped the DB rows.
     */
    fun deletePaths(paths: Collection<String>)
}

@Singleton
class FilePhotoStorage @Inject constructor(
    @ApplicationContext private val context: Context,
) : PhotoStorage {
    private val photosDir: File = File(context.filesDir, "photos").apply { mkdirs() }

    override fun saveForNote(noteId: Long, source: InputStream, extension: String): String {
        // UUID suffix to disambiguate parallel saves; `noteId_timestamp` collided
        // when two attaches happened within the same millisecond.
        val target = File(photosDir, "note_${noteId}_${UUID.randomUUID()}.${extension.ifEmpty { "jpg" }}")
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
        // PhotoEntity uses ON DELETE SET NULL for noteId and ON DELETE CASCADE for
        // bookId, so the DB rows are gone after BookRepository.delete — but the
        // underlying files in filesDir/photos/ stay. The repository reads those
        // paths up front via PhotoDao.pathsForBook() and calls [deletePaths]
        // below, so this entry point is a no-op for symmetry.
    }

    override fun deletePaths(paths: Collection<String>) {
        paths.forEach { path ->
            val f = File(path)
            if (f.exists() && f.parentFile == photosDir) f.delete()
        }
    }
}