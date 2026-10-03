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
    private val transcoder: CoverTranscoder,
) : CoverStorage {
    private val coversDir: File = File(context.filesDir, "covers").apply { mkdirs() }

    override fun saveTemp(source: InputStream, extension: String): String {
        // UUID avoids races where two parallel saves within the same millisecond
        // would pick the same name and overwrite each other.
        val raw = source.use { it.readBytes() }
        // Downscale + WebP here rather than at render time: this is the single
        // funnel every cover passes through (gallery pick, camera capture,
        // own-ZIP re-import), so a 300px lossy WebP is what lands on disk.
        val (bytes, ext) = transcoder.toWebp(raw)?.let { it to "webp" } ?: (raw to extension.ifEmpty { "jpg" })
        val target = File(coversDir, "cover_${UUID.randomUUID()}.$ext")
        target.writeBytes(bytes)
        return target.absolutePath
    }

    override fun promoteToBook(bookId: Long, tempPath: String?): String? {
        if (tempPath == null) return null
        val src = File(tempPath)
        if (!src.exists()) return tempPath
        val ext = src.extension.ifEmpty { "jpg" }
        val target = File(coversDir, "$bookId.$ext")
        return try {
            // Drop stale covers for this book (e.g. an old .png when the new
            // selection is .jpg), but skip the source — if src already happens
            // to be named {bookId}.{ext} (a re-promote of an existing cover)
            // the sweep would unlink it and the copyTo branch below is then
            // skipped (src == target), orphaning the row that points at it.
            deleteCoversFor(bookId, except = src)
            if (src.absolutePath != target.absolutePath) {
                src.copyTo(target, overwrite = true)
                src.delete()
            }
            target.absolutePath
        } catch (_: Exception) {
            // ponytail: on a failed copy the caller persists the temp path, so
            // the picked cover stays visible but keeps its cover_{uuid} name —
            // deleteForBook() won't match it, so a later book delete leaks it.
            // Renaming rather than copying in the failure path (or falling back
            // to null and dropping the cover) both trade this for a worse bug;
            // revisit only if failed promotions actually show up.
            tempPath
        }
    }

    override fun deleteForBook(bookId: Long) = deleteCoversFor(bookId)

    /** Unlink every `covers/{bookId}.*` file except [except] (same file by path). */
    private fun deleteCoversFor(bookId: Long, except: File? = null) {
        val prefix = "$bookId."
        coversDir.listFiles { f ->
            f.isFile && f.name.startsWith(prefix) && f.absolutePath != except?.absolutePath
        }?.forEach { it.delete() }
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