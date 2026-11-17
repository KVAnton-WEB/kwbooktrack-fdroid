package ru.antonkw.kwbooktrack.data.db.dao

import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity

@RunWith(RobolectricTestRunner::class)
class MovieDaoTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var dao: MovieDao

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        dao = db.movieDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun insertAndFindById() = runTest {
        val id = dao.insert(sampleMovie(title = "Малхолланд Драйв"))
        assertThat(id).isGreaterThan(0L)

        val found = dao.findById(id)
        assertThat(found).isNotNull()
        assertThat(found!!.title).isEqualTo("Малхолланд Драйв")
        assertThat(found.durationMin).isEqualTo(147)
        assertThat(found.source).isEqualTo(MediaSource.MANUAL)
    }

    @Test
    fun findByTitleAndYearMatchesEvenWhenYearIsNull() = runTest {
        dao.insert(sampleMovie(title = "Без года", year = null))
        val found = dao.findByTitleAndYear("Без года", null)
        assertThat(found).isNotNull()
    }

    @Test
    fun upsertFromImportDoesNotDuplicate() = runTest {
        val movie = sampleMovie(title = "Малхолланд Драйв", year = 1999)
        val first = dao.upsertFromImport(movie)
        val second = dao.upsertFromImport(movie)

        assertThat(second).isEqualTo(first)
        assertThat(dao.count()).isEqualTo(1)
    }

    @Test
    fun upsertFromImportKeepsExistingNullableFields() = runTest {
        // Рейтинг, проставленный вручную, импорт не должен затирать пустым.
        val id = dao.insert(sampleMovie(title = "Начало", year = 2010, rating = 5))
        dao.upsertFromImport(sampleMovie(title = "Начало", year = 2010, rating = null))

        val found = dao.findById(id)
        assertThat(found!!.rating).isEqualTo(5)
    }

    @Test
    fun upsertFromImportKeepsDownloadedFlag() = runTest {
        // Пользователь отметил фильм скачанным. Повторный импорт бэкапа, где
        // флага нет, не должен молча снимать отметку — как флаги книги.
        val id = dao.insert(sampleMovie(title = "Малхолланд Драйв", year = 1999, isDownloaded = true))
        dao.upsertFromImport(sampleMovie(title = "Малхолланд Драйв", year = 1999, isDownloaded = false))

        assertThat(dao.findById(id)!!.isDownloaded).isTrue()
    }

    @Test
    fun upsertFromImportKeepsModifiedDateFromSource() = runTest {
        // Метка «изменено» — из источника, а не «сейчас»: архив хранит её
        // именно для того, чтобы история фильма пережила импорт.
        val id = dao.insert(sampleMovie(title = "Начало", year = 2010, updatedAt = 1_700_000_000_000L))
        dao.upsertFromImport(sampleMovie(title = "Начало", year = 2010, updatedAt = 1_600_000_000_000L))

        assertThat(dao.findById(id)!!.updatedAt).isEqualTo(1_600_000_000_000L)
    }

    @Test
    fun searchFindsByDirector() = runTest {
        dao.insert(sampleMovie(title = "Малхолланд Драйв", director = "Линч, Дэвид"))
        dao.insert(sampleMovie(title = "Синий бархат", director = "Линч, Дэвид"))

        val query = SimpleSQLiteQuery(
            "SELECT * FROM movies WHERE people_search LIKE ?",
            arrayOf("%линч%"),
        )
        val found = dao.rawSearch(query).first()
        assertThat(found).hasSize(2)
    }

    private fun sampleMovie(
        title: String = "Фильм",
        year: Int? = 2024,
        director: String? = "Режиссёр",
        durationMin: Int? = 147,
        rating: Int? = null,
        isDownloaded: Boolean = false,
        updatedAt: Long = 0L,
    ) = MovieEntity(
        title = title,
        originalTitle = null,
        year = year,
        genre = "Драма",
        nationality = "США",
        director = director,
        cast = "Уоттс, Наоми::Роль",
        durationMin = durationMin,
        rating = rating,
        watchedAt = 1_700_000_000_000L,
        coverPath = null,
        plot = null,
        comments = null,
        url = null,
        isDownloaded = isDownloaded,
        createdAt = 0L,
        updatedAt = updatedAt,
        source = MediaSource.MANUAL,
        titleSearch = title.lowercase(),
        peopleSearch = (director ?: "").lowercase(),
        extraFields = emptyMap(),
    )
}
