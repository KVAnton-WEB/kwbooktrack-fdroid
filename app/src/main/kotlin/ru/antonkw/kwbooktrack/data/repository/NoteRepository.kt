package ru.antonkw.kwbooktrack.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.NoteDao
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.storage.PhotoStorage
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val photoStorage: PhotoStorage,
) {
    fun observeAll(): Flow<List<NoteEntity>> = noteDao.observeAll()

    /** Заметки владельца — книга или фильм. */
    fun observeByMedia(mediaId: Long, mediaType: MediaType): Flow<List<NoteEntity>> =
        noteDao.observeByMedia(mediaId, mediaType)

    fun observeByKind(kind: NoteKind): Flow<List<NoteEntity>> = noteDao.observeByKind(kind)

    fun search(query: String): Flow<List<NoteEntity>> {
        val tokens = query.lowercase()
            .split(Regex("\\s+"))
            .map { it.filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return observeAll()
        val sql = "SELECT * FROM notes WHERE " + tokens.joinToString(" AND ") { "LOWER(body_md) LIKE ?" } +
            " ORDER BY created_at DESC"
        // Use bind args rather than string interpolation; tokens are filtered
        // to letters/digits today, but relying on that for SQL safety is fragile.
        return noteDao.rawSearch(SimpleSQLiteQuery(sql, tokens.map { "%$it%" }.toTypedArray()))
    }

    suspend fun randomOne(): NoteEntity? = noteDao.randomOne()

    suspend fun findById(id: Long): NoteEntity? = noteDao.findById(id)

    suspend fun insert(note: NoteEntity): Long {
        val now = System.currentTimeMillis()
        val withTimestamps = note.copy(
            createdAt = if (note.createdAt == 0L) now else note.createdAt,
            updatedAt = now,
        )
        return noteDao.insert(withTimestamps)
    }

    suspend fun update(note: NoteEntity): Int =
        noteDao.update(note.copy(updatedAt = System.currentTimeMillis()))

    suspend fun delete(note: NoteEntity): Int {
        photoStorage.deleteForNote(note.id)
        return noteDao.delete(note)
    }

    suspend fun deleteForMedia(mediaId: Long, mediaType: MediaType): Int =
        noteDao.deleteForMedia(mediaId, mediaType)
}
