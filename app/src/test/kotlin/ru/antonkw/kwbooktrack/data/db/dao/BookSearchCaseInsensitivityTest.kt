package ru.antonkw.kwbooktrack.data.db.dao

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository

@RunWith(RobolectricTestRunner::class)
class BookSearchCaseInsensitivityTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var repo: BookRepository

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        repo = BookRepository(db.bookDao(), db.tagDao(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun lowercaseQueryMatchesCapitalizedTitle() {
        runBlocking {
            repo.insert(sample("Властелин Колец", "Толкин", createdAt = 0L, updatedAt = 0L))
            val results = repo.search("властелин").first()
            assertThat(results.map { it.title }).contains("Властелин Колец")
        }
    }

    @Test
    fun uppercaseQueryMatchesLowercaseTitle() {
        runBlocking {
            repo.insert(sample("властелин колец", "толкин", createdAt = 0L, updatedAt = 0L))
            val results = repo.search("ВЛАСТЕЛИН").first()
            assertThat(results.map { it.title }).contains("властелин колец")
        }
    }

    @Test
    fun cyrillicPrefixMatches() {
        runBlocking {
            repo.insert(sample("Властелин Колец", "Толкин", createdAt = 0L, updatedAt = 0L))
            repo.insert(sample("Властители", "Иванов", createdAt = 0L, updatedAt = 1L))
            repo.insert(sample("Дюна", "Герберт", createdAt = 0L, updatedAt = 2L))
            val results = repo.search("власт").first()
            assertThat(results.map { it.title }).containsExactly("Властители", "Властелин Колец")
        }
    }

    @Test
    fun authorIsSearchableCaseInsensitively() {
        runBlocking {
            repo.insert(sample("Dune", "Frank Herbert", createdAt = 0L, updatedAt = 0L))
            val results = repo.search("FRANK").first()
            assertThat(results).hasSize(1)
        }
    }

    @Test
    fun multiWordQuery() {
        runBlocking {
            repo.insert(sample("Властелин Колец", "Толкин Джон", createdAt = 0L, updatedAt = 0L))
            val results = repo.search("Толкин Властелин").first()
            assertThat(results).hasSize(1)
        }
    }

    @Test
    fun specialCharsAreStripped() {
        runBlocking {
            repo.insert(sample("Властелин Колец", "Толкин", createdAt = 0L, updatedAt = 0L))
            val results = repo.search("Толкин & \"").first()
            assertThat(results).hasSize(1)
        }
    }

    @Test
    fun emptyQueryReturnsAll() {
        runBlocking {
            repo.insert(sample("A", "X", createdAt = 0L, updatedAt = 0L))
            repo.insert(sample("B", "Y", createdAt = 0L, updatedAt = 1L))
            val results = repo.search("").first()
            assertThat(results).hasSize(2)
        }
    }

    private fun sample(title: String, author: String, createdAt: Long, updatedAt: Long) =
        BookEntity(
            title = title,
            author = author,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )
}
