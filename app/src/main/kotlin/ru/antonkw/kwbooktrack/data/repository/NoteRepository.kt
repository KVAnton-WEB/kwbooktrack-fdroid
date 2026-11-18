package ru.antonkw.kwbooktrack.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import ru.antonkw.kwbooktrack.data.db.dao.NoteDao
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.storage.PhotoStorage
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Совпадение тела заметки с токенами поиска — регистр не важен.
 *
 * Сравнение в Kotlin: там сворачивание регистра Unicode-полное, а в SQL
 * зависит от сборки SQLite (в системной сборке для приложений ICU выключен,
 * и LOWER() трогает только латиницу).
 */
internal fun noteMatchesTokens(body: String, tokens: List<String>): Boolean =
    tokens.all { body.contains(it, ignoreCase = true) }

@Singleton
class NoteRepository @Inject constructor(
    private val noteDao: NoteDao,
    private val photoStorage: PhotoStorage,
) {
    fun observeAll(): Flow<List<NoteEntity>> = noteDao.observeAll()

    /** Заметки владельца — книга или фильм. */
    fun observeByMedia(mediaId: Long, mediaType: MediaType): Flow<List<NoteEntity>> =
        noteDao.observeByMedia(mediaId, mediaType)

    fun search(query: String): Flow<List<NoteEntity>> {
        val tokens = query
            .split(Regex("\\s+"))
            .map { it.filter { ch -> ch.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return observeAll()
        // Фильтр в Kotlin, а не в SQL: системная сборка SQLite для приложений
        // собрана без ICU, и LOWER() сворачивает регистр только для латинских
        // букв — кириллица остаётся как есть, и поиск по русским заметкам
        // ничего не находил. Так же сделано для книг и фильмов: там регистр
        // сворачивается в Kotlin при записи в колонку title_search.
        return observeAll().map { notes ->
            notes.filter { note -> noteMatchesTokens(note.bodyMd, tokens) }
        }
    }

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

}
