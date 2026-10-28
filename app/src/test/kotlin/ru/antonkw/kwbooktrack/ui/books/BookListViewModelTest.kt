package ru.antonkw.kwbooktrack.ui.books

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.TestViewModels
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BookListViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var repository: BookRepository
    private lateinit var sessionRepository: SessionRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        repository = BookRepository(
            db.bookDao(),
            db.tagDao(),
            ru.antonkw.kwbooktrack.data.NoOpCoverStorage,
            PhotoRepository(db.photoDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage),
        )
        sessionRepository = SessionRepository(db.readingSessionDao())
    }

    @After
    fun tearDown() {
        TestViewModels.cancelAll(dispatcher.scheduler)
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun initialQueryAndLayoutAreDefaults() = runTest(dispatcher) {
        val viewModel = BookListViewModel(repository, sessionRepository, androidx.lifecycle.SavedStateHandle())
        TestViewModels.track(viewModel)
        assertThat(viewModel.currentQuery.value).isEmpty()
        assertThat(viewModel.currentLayout.value).isFalse()
        assertThat(viewModel.uiState.value).isEqualTo(BookListUiState.Loading)
    }

    @Test
    fun setQueryUpdatesCurrentQuery() = runTest(dispatcher) {
        val viewModel = BookListViewModel(repository, sessionRepository, androidx.lifecycle.SavedStateHandle())
        TestViewModels.track(viewModel)
        viewModel.setQuery("Толкин")
        assertThat(viewModel.currentQuery.value).isEqualTo("Толкин")
    }

    @Test
    fun toggleLayoutFlipsIsGrid() = runTest(dispatcher) {
        val viewModel = BookListViewModel(repository, sessionRepository, androidx.lifecycle.SavedStateHandle())
        TestViewModels.track(viewModel)
        val before = viewModel.currentLayout.value
        viewModel.toggleLayout()
        val after = viewModel.currentLayout.value
        assertThat(before).isFalse()
        assertThat(after).isTrue()
    }

    @Test
    fun savedStateRestoresQuery() = runTest(dispatcher) {
        val saved = androidx.lifecycle.SavedStateHandle(mapOf("query" to "Asimov"))
        val viewModel = BookListViewModel(repository, sessionRepository, saved)
        TestViewModels.track(viewModel)
        assertThat(viewModel.currentQuery.value).isEqualTo("Asimov")
    }

    @Test
    fun savedStateRestoresLayout() = runTest(dispatcher) {
        val saved = androidx.lifecycle.SavedStateHandle(mapOf("isGrid" to true))
        val viewModel = BookListViewModel(repository, sessionRepository, saved)
        TestViewModels.track(viewModel)
        assertThat(viewModel.currentLayout.value).isTrue()
    }

    @Test
    fun yearPeriodKeepsEveryMonthOfThatYear() = runTest(dispatcher) {
        repository.insert(purchased("Январь", LocalDate.of(2026, 1, 10)))
        repository.insert(purchased("Декабрь", LocalDate.of(2026, 12, 31)))
        repository.insert(purchased("Прошлый год", LocalDate.of(2025, 12, 31)))
        val viewModel = BookListViewModel(repository, sessionRepository, androidx.lifecycle.SavedStateHandle())
        TestViewModels.track(viewModel)
        viewModel.setFilterType(BookFilterType.PURCHASED)
        viewModel.setPeriod(PeriodOption.year(2026))
        val loaded = viewModel.uiState.first { it is BookListUiState.Loaded } as BookListUiState.Loaded
        assertThat(loaded.books.map { it.book.title }).containsExactly("Январь", "Декабрь")
    }

    @Test
    fun periodFiltersAllTypesToo() = runTest(dispatcher) {
        repository.insert(purchased("Куплена в 2026", LocalDate.of(2026, 3, 1)))
        repository.insert(purchased("Куплена в 2025", LocalDate.of(2025, 3, 1)))
        val viewModel = BookListViewModel(repository, sessionRepository, androidx.lifecycle.SavedStateHandle())
        TestViewModels.track(viewModel)
        viewModel.setPeriod(PeriodOption.year(2026))
        val loaded = viewModel.uiState.first { it is BookListUiState.Loaded } as BookListUiState.Loaded
        // Тип по умолчанию — «Все», но период сужает и его: остаётся книга 2026 года.
        assertThat(loaded.books.map { it.book.title }).containsExactly("Куплена в 2026")
    }

    @Test
    fun savedStateRestoresPeriod() = runTest(dispatcher) {
        val saved = androidx.lifecycle.SavedStateHandle(
            mapOf("periodStart" to LocalDate.of(2026, 3, 1).toEpochMs(), "periodWholeYear" to false),
        )
        val viewModel = BookListViewModel(repository, sessionRepository, saved)
        TestViewModels.track(viewModel)
        val period = viewModel.currentPeriod.value
        assertThat(period.wholeYear).isFalse()
        assertThat(period.startMillis).isEqualTo(LocalDate.of(2026, 3, 1).toEpochMs())
    }

    @Test
    fun savedStateRestoresWholeYearPeriod() = runTest(dispatcher) {
        val saved = androidx.lifecycle.SavedStateHandle(
            mapOf("periodStart" to LocalDate.of(2026, 1, 1).toEpochMs(), "periodWholeYear" to true),
        )
        val viewModel = BookListViewModel(repository, sessionRepository, saved)
        TestViewModels.track(viewModel)
        assertThat(viewModel.currentPeriod.value.wholeYear).isTrue()
    }

    private fun purchased(title: String, date: LocalDate) = BookEntity(
        title = title,
        author = "Author",
        createdAt = 0L,
        updatedAt = 0L,
        purchaseDate = date.toEpochMs(),
    )
}
