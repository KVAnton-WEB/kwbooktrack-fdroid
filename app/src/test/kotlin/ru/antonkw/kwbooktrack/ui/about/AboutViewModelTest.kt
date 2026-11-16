package ru.antonkw.kwbooktrack.ui.about

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.exporter.AppVersionProvider
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AboutViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var movieRepository: MovieRepository
    private val dispatcher = StandardTestDispatcher()
    private val versionProvider = object : AppVersionProvider {
        override val appVersion: String = "9.9.9-test"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        movieRepository = MovieRepository(
            db.movieDao(),
            ru.antonkw.kwbooktrack.data.NoOpCoverStorage,
            PhotoRepository(db.photoDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage),
        )
        bookRepository = BookRepository(
            db.bookDao(),
            db.tagDao(),
            ru.antonkw.kwbooktrack.data.NoOpCoverStorage,
            PhotoRepository(db.photoDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun initialInfoUsesProviderVersionAndZeroCounters() = runTest(dispatcher) {
        val vm = AboutViewModel(bookRepository, movieRepository, versionProvider)
        advanceUntilIdle()
        val info = vm.info.first { it.totalBooks == 0 }
        assertThat(info.version).isEqualTo("9.9.9-test")
        assertThat(info.totalBooks).isEqualTo(0)
        assertThat(info.finishedBooks).isEqualTo(0)
    }

    @Test
    fun countsAggregatesAcrossStatuses() = runTest(dispatcher) {
        val vm = AboutViewModel(bookRepository, movieRepository, versionProvider)
        advanceUntilIdle()

        repeat(3) {
            bookRepository.insert(sampleBook(status = BookStatus.WISHLIST))
        }
        repeat(2) {
            bookRepository.insert(sampleBook(status = BookStatus.READING))
        }
        repeat(1) {
            bookRepository.insert(sampleBook(status = BookStatus.FINISHED))
        }

        // Wait for the actual emission we care about — no busy-loop, no fixed sleeps.
        val info = vm.info.first { it.totalBooks == 6 }
        assertThat(info.totalBooks).isEqualTo(6)
        assertThat(info.finishedBooks).isEqualTo(1)
    }

    @Test
    fun reactiveUpdateOnBookInsertion() = runTest(dispatcher) {
        val vm = AboutViewModel(bookRepository, movieRepository, versionProvider)
        advanceUntilIdle()

        bookRepository.insert(sampleBook(status = BookStatus.FINISHED))
        val info = vm.info.first { it.totalBooks == 1 }

        assertThat(info.totalBooks).isEqualTo(1)
        assertThat(info.finishedBooks).isEqualTo(1)
    }

    private fun sampleBook(status: BookStatus) = BookEntity(
        title = "Sample",
        author = "Anon",
        status = status,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
