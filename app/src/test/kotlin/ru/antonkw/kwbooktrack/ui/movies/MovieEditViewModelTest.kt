package ru.antonkw.kwbooktrack.ui.movies

import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.NoOpCoverStorage
import ru.antonkw.kwbooktrack.data.NoOpPhotoStorage
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField

@RunWith(RobolectricTestRunner::class)
class MovieEditViewModelTest {

    private lateinit var db: KwBooktrackDatabase
    private lateinit var movies: MovieRepository
    private lateinit var mediaDates: MediaDateRepository
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        movies = MovieRepository(
            db.movieDao(),
            NoOpCoverStorage,
            PhotoRepository(db.photoDao(), NoOpPhotoStorage),
        )
        mediaDates = MediaDateRepository(db.mediaDateDao())
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun editingMovieKeepsFieldsTheFormDoesNotShow() = runTest(dispatcher) {
        // Комментарии, чужие поля Tellico и источник в форме не показываются —
        // но правка названия не должна их стирать: фильм приехал из импорта.
        val id = movies.insert(
            MovieEntity(
                title = "Малхолланд Драйв",
                comments = "Комментарий из Tellico",
                extraFields = mapOf(
                    "widescreen" to ExtraField(title = "Широкоэкранное", value = "true"),
                ),
                source = MediaSource.TELLICO,
                createdAt = 1L,
                updatedAt = 1L,
            ),
        )
        val vm = editVm(id)
        vm.setTitle("Малхолланд Драйв")
        vm.save()
        vm.state.first { it.saved }

        val saved = movies.findById(id)!!
        assertThat(saved.comments).isEqualTo("Комментарий из Tellico")
        assertThat(saved.extraFields["widescreen"]?.value).isEqualTo("true")
        assertThat(saved.source).isEqualTo(MediaSource.TELLICO)
    }

    @Test
    fun newMovieIsSavedAsManual() = runTest(dispatcher) {
        val vm = newVm()
        vm.setTitle("Новый фильм")
        vm.setYear("2026")
        vm.save()
        vm.state.first { it.saved }

        val saved = movies.getAll().single()
        assertThat(saved.title).isEqualTo("Новый фильм")
        assertThat(saved.year).isEqualTo(2026)
        assertThat(saved.source).isEqualTo(MediaSource.MANUAL)
    }

    @Test
    fun secondSaveDoesNotDuplicateMovie() = runTest(dispatcher) {
        // У нового фильма isEditing = false, и save() идёт в insert — без
        // защиты второе нажатие «Сохранить» создало бы второй фильм.
        val vm = newVm()
        vm.setTitle("Новый фильм")
        vm.save()
        vm.state.first { it.saved }
        vm.save()
        advanceUntilIdle()

        assertThat(movies.getAll()).hasSize(1)
    }

    private fun editVm(movieId: Long) = MovieEditViewModel(
        SavedStateHandle(mapOf("movieId" to movieId)),
        movies,
        mediaDates,
        ApplicationProvider.getApplicationContext(),
        NoOpCoverStorage,
    )

    private fun newVm() = MovieEditViewModel(
        SavedStateHandle(),
        movies,
        mediaDates,
        ApplicationProvider.getApplicationContext(),
        NoOpCoverStorage,
    )
}
