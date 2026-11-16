package ru.antonkw.kwbooktrack.ui.movies

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.storage.CoverStorage
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import ru.antonkw.kwbooktrack.data.time.toLocalDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Состояние формы редактирования фильма. */
data class MovieEditState(
    val title: String = "",
    val originalTitle: String = "",
    val year: String = "",
    val genre: String = "",
    val nationality: String = "",
    val director: String = "",
    val cast: String = "",
    val duration: String = "",
    val rating: Int? = null,
    val watchedDate: String = "",
    val url: String = "",
    val isDownloaded: Boolean = false,
    val plot: String = "",
    val coverPath: String? = null,
    val saved: Boolean = false,
    val loading: Boolean = false,
)

/**
 * Создание и редактирование фильма — по образцу [ru.antonkw.kwbooktrack.ui.add.AddBookViewModel].
 *
 * Поля фильма: название, оригинальное название, год, жанры, страна, режиссёр,
 * актёры, продолжительность, рейтинг, дата просмотра, ссылка, сюжет и обложка.
 */
@HiltViewModel
class MovieEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val movieRepository: MovieRepository,
    @ApplicationContext private val context: Context,
    private val coverStorage: CoverStorage,
) : ViewModel() {

    private val editingMovieId: Long = savedStateHandle.get<Long>(KEY_MOVIE_ID) ?: 0L
    val isEditing: Boolean = editingMovieId > 0L

    private val _state = MutableStateFlow(MovieEditState(loading = isEditing))
    val state: StateFlow<MovieEditState> = _state.asStateFlow()

    /** Обложка, сохранённая в БД до правки: её надо убрать, если её заменили. */
    private var originalCoverPath: String? = null

    init {
        if (isEditing) {
            viewModelScope.launch {
                val movie = movieRepository.findById(editingMovieId) ?: return@launch
                originalCoverPath = movie.coverPath
                _state.value = MovieEditState(
                    title = movie.title,
                    originalTitle = movie.originalTitle.orEmpty(),
                    year = movie.year?.toString().orEmpty(),
                    genre = movie.genre.orEmpty(),
                    nationality = movie.nationality.orEmpty(),
                    director = movie.director.orEmpty(),
                    cast = movie.cast.orEmpty(),
                    duration = movie.durationMin?.toString().orEmpty(),
                    rating = movie.rating,
                    watchedDate = movie.watchedAt?.let { DATE_FMT.format(it.toLocalDate()) }.orEmpty(),
                    url = movie.url.orEmpty(),
                    isDownloaded = movie.isDownloaded,
                    plot = movie.plot.orEmpty(),
                    coverPath = movie.coverPath,
                    loading = false,
                )
            }
        }
    }

    fun setTitle(value: String) = update { copy(title = value) }
    fun setOriginalTitle(value: String) = update { copy(originalTitle = value) }
    fun setYear(value: String) = update { copy(year = value.filter { it.isDigit() }.take(4)) }
    fun setGenre(value: String) = update { copy(genre = value) }
    fun setNationality(value: String) = update { copy(nationality = value) }
    fun setDirector(value: String) = update { copy(director = value) }
    fun setCast(value: String) = update { copy(cast = value) }
    fun setDuration(value: String) = update { copy(duration = value.filter { it.isDigit() }) }
    fun setWatchedDate(value: String) = update { copy(watchedDate = value) }
    fun setUrl(value: String) = update { copy(url = value) }
    fun setPlot(value: String) = update { copy(plot = value) }
    fun setDownloaded(value: Boolean) = update { copy(isDownloaded = value) }
    fun setRating(value: Int?) = update { copy(rating = value) }

    /** Обложка из галереи: файл сохраняется, путь — в состояние. */
    fun attachCover(uri: Uri) {
        viewModelScope.launch {
            val path = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    coverStorage.saveTemp(input, "jpg")
                }
            }.getOrNull() ?: return@launch
            update { copy(coverPath = path) }
        }
    }

    fun clearCover() {
        update { copy(coverPath = null) }
    }

    fun save() {
        val s = _state.value
        if (s.title.isBlank()) return
        viewModelScope.launch {
            val coverPath = s.coverPath?.let { coverStorage.promote(it) }
            val movie = MovieEntity(
                id = if (isEditing) editingMovieId else 0L,
                title = s.title.trim(),
                originalTitle = s.originalTitle.trim().takeIf { it.isNotEmpty() },
                year = s.year.toIntOrNull(),
                genre = s.genre.trim().takeIf { it.isNotEmpty() },
                nationality = s.nationality.trim().takeIf { it.isNotEmpty() },
                director = s.director.trim().takeIf { it.isNotEmpty() },
                cast = s.cast.trim().takeIf { it.isNotEmpty() },
                durationMin = s.duration.toIntOrNull(),
                rating = s.rating,
                watchedAt = parseDate(s.watchedDate),
                coverPath = coverPath,
                plot = s.plot.trim().takeIf { it.isNotEmpty() },
                comments = null,
                url = s.url.trim().takeIf { it.isNotEmpty() },
                isDownloaded = s.isDownloaded,
                createdAt = 0L,
                updatedAt = System.currentTimeMillis(),
                source = MediaSource.MANUAL,
            )
            if (isEditing) {
                val existing = movieRepository.findById(editingMovieId)
                if (existing != null) {
                    movieRepository.update(movie.copy(createdAt = existing.createdAt))
                }
            } else {
                movieRepository.insert(movie)
            }
            // Прежняя обложка больше не нужна: путь новой — с UUID-именем.
            originalCoverPath
                ?.takeIf { it != coverPath }
                ?.let { coverStorage.delete(it) }
            originalCoverPath = coverPath
            _state.value = _state.value.copy(saved = true)
        }
    }

    private fun update(block: MovieEditState.() -> MovieEditState) {
        _state.value = _state.value.block()
    }

    private fun parseDate(raw: String): Long? {
        if (raw.isBlank()) return null
        return runCatching { LocalDate.parse(raw, DATE_FMT).toEpochMs() }.getOrNull()
    }

    companion object {
        const val KEY_MOVIE_ID = "movieId"
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
