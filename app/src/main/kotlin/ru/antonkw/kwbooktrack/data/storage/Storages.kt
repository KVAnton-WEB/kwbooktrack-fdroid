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
 *
 * Имя файла обложки — UUID (`cover_{uuid}.{ext}`), а не id записи. Имя по id
 * привязывало бы файл к строке БД: у книг и фильмов id считаются независимо
 * и совпадают, и обложка фильма 1 перезаписала бы обложку книги 1. С UUID
 * имя уникально, файл не привязан к строке, а удалить его можно по точному
 * пути — в БД лежит именно путь.
 */
interface CoverStorage {
    /** Скопировать байты в `covers/cover_{uuid}.{ext}`, вернуть абсолютный путь. */
    fun saveTemp(source: InputStream, extension: String): String

    /** Удалить файл обложки по точному пути. */
    fun delete(path: String?)
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
        // own-ZIP re-import), so a 600px lossy WebP is what lands on disk.
        val (bytes, ext) = transcoder.toWebp(raw)?.let { it to "webp" } ?: (raw to extension.ifEmpty { "jpg" })
        val target = File(coversDir, "cover_${UUID.randomUUID()}.$ext")
        target.writeBytes(bytes)
        return target.absolutePath
    }

    override fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        val file = File(path)
        // Только файлы из каталога обложек: путь приходит из строки БД, а
        // удалять по нему можно что угодно.
        if (file.parentFile != coversDir) return
        if (file.isFile) file.delete()
    }
}

/**
 * Абстракция над каталогом фото в `filesDir/photos/`.
 * Файлы имеют вид `note_{noteId}_{timestamp}.{ext}`.
 */
interface PhotoStorage {
    fun saveForNote(noteId: Long, source: InputStream, extension: String): String

    /**
     * Сохранить байты под заданным именем (без расширения) в каталог фото;
     * вернуть абсолютный путь. Импорт архива так сохраняет фото под именем
     * `{md5}_{bookId}` — одинаковое содержимое у двух книг не удаляется
     * вместе с одной из них.
     */
    fun saveNamed(name: String, source: InputStream, extension: String): String

    fun deleteForNote(noteId: Long)

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

    override fun saveNamed(name: String, source: InputStream, extension: String): String {
        val target = File(photosDir, "$name.${extension.ifEmpty { "jpg" }}")
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

    override fun deletePaths(paths: Collection<String>) {
        paths.forEach { path ->
            val f = File(path)
            if (f.exists() && f.parentFile == photosDir) f.delete()
        }
    }
}