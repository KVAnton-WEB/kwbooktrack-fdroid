package ru.antonkw.kwbooktrack.ui.movies

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteEntity
import ru.antonkw.kwbooktrack.data.db.entity.PhotoEntity
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.repository.NoteRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.repository.TagRepository
import ru.antonkw.kwbooktrack.data.storage.PhotoStorage
import javax.inject.Inject

data class MovieDetailUiState(
    val movie: MovieEntity?,
    val notes: List<NoteEntity> = emptyList(),
    val photos: List<PhotoEntity> = emptyList(),
    val tags: List<String> = emptyList(),
)

/**
 * Карточка фильма.
 *
 * Заметки, цитаты, рецензия, теги и фото — те же самые, что у книги: у них
 * общая таблица, и «владелец» различается парой (id, тип).
 *
 * Сессий чтения нет: трекера страниц у фильма не бывает.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class MovieDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val movieRepository: MovieRepository,
    private val noteRepository: NoteRepository,
    private val photoRepository: PhotoRepository,
    private val tagRepository: TagRepository,
    private val photoStorage: PhotoStorage,
) : ViewModel() {

    private val movieId: Long = savedStateHandle.get<Long>(KEY_MOVIE_ID) ?: -1L

    private val _deleted = MutableStateFlow(false)
    val deleted: StateFlow<Boolean> = _deleted.asStateFlow()

    val state: StateFlow<MovieDetailUiState> = combine(
        movieRepository.observeById(movieId),
        noteRepository.observeByMedia(movieId, MediaType.MOVIES),
        photoRepository.observeByMedia(movieId, MediaType.MOVIES),
    ) { movie, notes, photos ->
        MovieDetailUiState(
            movie = movie,
            notes = notes,
            photos = photos,
            tags = tagRepository.tagsOfMedia(movieId, MediaType.MOVIES),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MovieDetailUiState(null))

    fun deleteMovie() {
        viewModelScope.launch {
            // Пути файлов фото читаем ДО удаления строк: после их не узнать.
            val photoPaths = photoRepository.observeByMedia(movieId, MediaType.MOVIES).first().map { it.path }
            movieRepository.deleteById(movieId)
            photoStorage.deletePaths(photoPaths)
            _deleted.value = true
        }
    }

    companion object {
        const val KEY_MOVIE_ID = "movieId"
    }
}
