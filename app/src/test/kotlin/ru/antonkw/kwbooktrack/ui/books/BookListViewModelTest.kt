package ru.antonkw.kwbooktrack.ui.books

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.SessionRepository

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
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun initialQueryAndLayoutAreDefaults() = runTest(dispatcher) {
        val viewModel = BookListViewModel(repository, sessionRepository, androidx.lifecycle.SavedStateHandle())
        assertThat(viewModel.currentQuery.value).isEmpty()
        assertThat(viewModel.currentLayout.value).isFalse()
        assertThat(viewModel.uiState.value).isEqualTo(BookListUiState.Loading)
    }

    @Test
    fun setQueryUpdatesCurrentQuery() = runTest(dispatcher) {
        val viewModel = BookListViewModel(repository, sessionRepository, androidx.lifecycle.SavedStateHandle())
        viewModel.setQuery("Толкин")
        assertThat(viewModel.currentQuery.value).isEqualTo("Толкин")
    }

    @Test
    fun toggleLayoutFlipsIsGrid() = runTest(dispatcher) {
        val viewModel = BookListViewModel(repository, sessionRepository, androidx.lifecycle.SavedStateHandle())
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
        assertThat(viewModel.currentQuery.value).isEqualTo("Asimov")
    }

    @Test
    fun savedStateRestoresLayout() = runTest(dispatcher) {
        val saved = androidx.lifecycle.SavedStateHandle(mapOf("isGrid" to true))
        val viewModel = BookListViewModel(repository, sessionRepository, saved)
        assertThat(viewModel.currentLayout.value).isTrue()
    }
}
