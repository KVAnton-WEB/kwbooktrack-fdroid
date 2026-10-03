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
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository

@RunWith(RobolectricTestRunner::class)
class BookSearchCaseInsensitivityTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var repo: BookRepository

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        repo = BookRepository(
            db.bookDao(),
            db.tagDao(),
            ru.antonkw.kwbooktrack.data.NoOpCoverStorage,
            PhotoRepository(db.photoDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage),
        )
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

    @Test
    fun cyrillicSeriesMatchesLowercaseQuery() {
        runBlocking {
            repo.insert(sample("Братство", "Робинс", series = "Стиль Сага", createdAt = 0L, updatedAt = 0L))
            val results = repo.search("сага").first()
            assertThat(results.map { it.title }).containsExactly("Братство")
        }
    }

    @Test
    fun cyrillicSeriesMatchesUppercaseQuery() {
        // Guards the pre-lowercased series_search column (BookRepository
        // .withSearchKeys). The query token is lowercased in Kotlin and the
        // stored key is lowercased in Kotlin too — SQLite can't be trusted to
        // do either half, since LIKE and lower() fold case for ASCII only on
        // AOSP. The stored series is lowercased and the query is uppercased
        // here, so the test only passes if the column is doing the work.
        runBlocking {
            repo.insert(sample("Братство", "Робинс", series = "стиль сага", createdAt = 0L, updatedAt = 0L))
            val results = repo.search("САГА").first()
            assertThat(results.map { it.title }).containsExactly("Братство")
        }
    }

    @Test
    fun seriesNumberIsSearchable() {
        runBlocking {
            repo.insert(sample("Братство", "Робинс", series = "Стиль Сага", seriesNumber = "7", createdAt = 0L, updatedAt = 0L))
            val results = repo.search("7").first()
            assertThat(results.map { it.title }).containsExactly("Братство")
        }
    }

    @Test
    fun seriesAndKeywordAreAndedTogether() {
        runBlocking {
            repo.insert(
                sample("Братство", "Робинс", series = "Стиль Сага", seriesNumber = "7", createdAt = 0L, updatedAt = 0L),
            )
            repo.insert(sample("Дюна", "Герберт", series = "Стиль Сага", seriesNumber = "1", createdAt = 0L, updatedAt = 1L))
            val results = repo.search("сага 7").first()
            assertThat(results.map { it.title }).containsExactly("Братство")
        }
    }

    @Test
    fun bookWithoutSeriesDoesNotMatchOrBreakResults() {
        runBlocking {
            repo.insert(sample("Без серии", "Никто", createdAt = 0L, updatedAt = 0L))
            repo.insert(sample("С серией", "Кто-то", series = "Стиль Сага", createdAt = 0L, updatedAt = 1L))
            assertThat(repo.search("сага").first().map { it.title }).containsExactly("С серией")
            assertThat(repo.search("никто").first().map { it.title }).containsExactly("Без серии")
        }
    }

    private fun sample(
        title: String,
        author: String,
        createdAt: Long,
        updatedAt: Long,
        series: String? = null,
        seriesNumber: String? = null,
    ) = BookEntity(
        title = title,
        author = author,
        series = series,
        seriesNumber = seriesNumber,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )
}
