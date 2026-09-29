package ru.antonkw.kwbooktrack.data.repository

import androidx.sqlite.db.SimpleSQLiteQuery
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.dao.NoteDao
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

    fun observeByBook(bookId: Long): Flow<List<NoteEntity>> = noteDao.observeByBook(bookId)

    fun observeByKind(kind: NoteKind): Flow<List<NoteEntity>> = noteDao.observeByKind(kind)

    fun search(query: String): Flow<List<NoteEntity>> {
        val tokens = query.lowercase()
            .split(Regex("\\s+"))
            .map { it.filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return observeAll()
        val sql = "SELECT * FROM notes WHERE " + tokens.joinToString(" AND ") { t ->
            "LOWER(body_md) LIKE '%' || '$t' || '%'"
        } + " ORDER BY created_at DESC"
        return noteDao.rawSearch(SimpleSQLiteQuery(sql))
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

    suspend fun deleteForBook(bookId: Long): Int = noteDao.deleteForBook(bookId)
}
