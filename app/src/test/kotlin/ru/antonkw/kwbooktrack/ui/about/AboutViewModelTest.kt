package ru.antonkw.kwbooktrack.ui.about

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import org.robolectric.shadows.ShadowLooper
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.exporter.AppVersionProvider
import ru.antonkw.kwbooktrack.data.repository.BookRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AboutViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private val dispatcher = StandardTestDispatcher()
    private val versionProvider = object : AppVersionProvider {
        override val appVersion: String = "9.9.9-test"
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        bookRepository = BookRepository(db.bookDao(), db.tagDao(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun initialInfoUsesProviderVersionAndZeroCounters() = runTest(dispatcher) {
        val vm = AboutViewModel(bookRepository, versionProvider)
        advanceUntilIdle()
        ShadowLooper.idleMainLooper()

        val info = vm.info.value
        assertThat(info.version).isEqualTo("9.9.9-test")
        assertThat(info.totalBooks).isEqualTo(0)
        assertThat(info.finishedBooks).isEqualTo(0)
    }

    @Test
    fun countsAggregatesAcrossStatuses() = runTest(dispatcher) {
        val vm = AboutViewModel(bookRepository, versionProvider)
        advanceUntilIdle()
        assertThat(vm.info.value.totalBooks).isEqualTo(0)

        repeat(3) {
            bookRepository.insert(sampleBook(status = BookStatus.WISHLIST))
        }
        repeat(2) {
            bookRepository.insert(sampleBook(status = BookStatus.READING))
        }
        repeat(1) {
            bookRepository.insert(sampleBook(status = BookStatus.FINISHED))
        }
        var attempts = 20
        while (attempts-- > 0 && vm.info.value.totalBooks < 6) {
            advanceUntilIdle()
            kotlinx.coroutines.delay(50)
        }

        val info = vm.info.value
        assertThat(info.totalBooks).isEqualTo(6)
        assertThat(info.finishedBooks).isEqualTo(1)
    }

    @Test
    fun reactiveUpdateOnBookInsertion() = runTest(dispatcher) {
        val vm = AboutViewModel(bookRepository, versionProvider)
        advanceUntilIdle()
        assertThat(vm.info.value.totalBooks).isEqualTo(0)

        bookRepository.insert(sampleBook(status = BookStatus.FINISHED))
        var attempts = 10
        while (attempts-- > 0 && vm.info.value.totalBooks == 0) {
            advanceUntilIdle()
            kotlinx.coroutines.delay(50)
        }
        assertThat(vm.info.value.totalBooks).isEqualTo(1)
        assertThat(vm.info.value.finishedBooks).isEqualTo(1)
    }

    private fun sampleBook(status: BookStatus) = BookEntity(
        title = "Sample",
        author = "Anon",
        status = status,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
