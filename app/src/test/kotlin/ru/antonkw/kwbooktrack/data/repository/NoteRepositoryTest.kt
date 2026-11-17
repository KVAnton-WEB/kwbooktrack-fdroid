package ru.antonkw.kwbooktrack.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind

/**
 * Поиск по заметкам.
 *
 * Раньше запрос был `LOWER(body_md) LIKE ?`: SQLite на Android сворачивает
 * регистр только для латинских букв (сборка без ICU), поэтому кириллица в
 * теле заметки оставалась как есть и поиск по русским заметкам ничего не
 * находил. Теперь фильтр считается в Kotlin.
 */
@RunWith(RobolectricTestRunner::class)
class NoteRepositoryTest {

    private lateinit var db: KwBooktrackDatabase
    private lateinit var repo: NoteRepository

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        repo = NoteRepository(db.noteDao(), NoOpPhotoStorage)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun searchFindsCyrillicRegardlessOfCase() = runTest {
        repo.insert(note("Мысль о Дюне"))
        repo.insert(note("Просто заметка"))

        val found = repo.search("дюне").first()

        assertThat(found).hasSize(1)
        assertThat(found.single().bodyMd).isEqualTo("Мысль о Дюне")
    }

    @Test
    fun searchFindsCyrillicInTheOtherDirection() = runTest {
        repo.insert(note("мысль о дюне"))

        val found = repo.search("ДЮНЕ").first()

        assertThat(found).hasSize(1)
    }

    @Test
    fun searchStillWorksForLatin() = runTest {
        repo.insert(note("Dune Messiah"))

        val found = repo.search("messiah").first()

        assertThat(found).hasSize(1)
    }

    @Test
    fun searchRequiresAllTokens() = runTest {
        repo.insert(note("Мысль о Дюне"))

        val found = repo.search("дюне герберт").first()

        assertThat(found).isEmpty()
    }

    @Test
    fun matchingFoldsCyrillicCaseInKotlin() {
        // Сверка регистра делается в Kotlin: системная сборка SQLite для
        // приложений собрана без ICU, и LOWER() в SQL кириллицу не трогает.
        // Robolectric собран с ICU, поэтому разницу видно только здесь.
        assertThat(noteMatchesTokens("Мысль о Дюне", listOf("дюне"))).isTrue()
        assertThat(noteMatchesTokens("Мысль о Дюне", listOf("ДЮНЕ"))).isTrue()
        assertThat(noteMatchesTokens("Мысль о Дюне", listOf("дюне", "герберт"))).isFalse()
    }

    private suspend fun note(body: String) = NoteEntity(
        mediaId = 1L,
        mediaType = MediaType.BOOKS,
        bodyMd = body,
        kind = NoteKind.NOTE,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
