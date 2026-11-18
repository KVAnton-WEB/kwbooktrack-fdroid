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
import ru.antonkw.kwbooktrack.data.db.entity.MediaType
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.repository.MediaDateRepository
import ru.antonkw.kwbooktrack.data.repository.SaveMovieUseCase
import ru.antonkw.kwbooktrack.data.repository.MovieRepository
import ru.antonkw.kwbooktrack.data.storage.CoverStorage
import ru.antonkw.kwbooktrack.data.time.toDayDate
import ru.antonkw.kwbooktrack.data.time.toDayMs
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

/** Ошибка сохранения фильма — текст подставляет экран, как у книги. */
enum class MovieEditError {
    /** Дата введена, но не разобралась — молча терять её нельзя. */
    INVALID_DATE,
}

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
    /** Даты просмотра — по одной на поле. Основная (самая ранняя) — первая. */
    val watchedDates: List<String> = listOf(""),
    val url: String = "",
    val isDownloaded: Boolean = false,
    val plot: String = "",
    val coverPath: String? = null,
    val saved: Boolean = false,
    val loading: Boolean = false,
    val error: MovieEditError? = null,
    /**
     * Растёт с каждой ошибкой сохранения: StateFlow склеивает равные
     * значения, и без него повторный Save не показал бы snackbar снова.
     */
    val errorNonce: Int = 0,
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
    private val mediaDateRepository: MediaDateRepository,
    private val saveMovieUseCase: SaveMovieUseCase,
    @ApplicationContext private val context: Context,
    private val coverStorage: CoverStorage,
) : ViewModel() {

    private val editingMovieId: Long = savedStateHandle.get<Long>(KEY_MOVIE_ID) ?: 0L
    val isEditing: Boolean = editingMovieId > 0L

    private val _state = MutableStateFlow(MovieEditState(loading = isEditing))
    val state: StateFlow<MovieEditState> = _state.asStateFlow()

    /** Обложка, сохранённая в БД до правки: её надо убрать, если её заменили. */
    private var originalCoverPath: String? = null

    /**
     * Снимок формы до правки — для вопроса «отменить изменения?».
     *
     * В режиме правки перезаписывается после загрузки фильма: пустая форма
     * делала бы её «грязной» сразу.
     */
    private var initialSnapshot: MovieEditState = MovieEditState(loading = isEditing)

    /** Идёт запись фильма: повторное «Сохранить» до её конца — no-op. */
    private var saving = false

    val isDirty: Boolean
        get() = _state.value.copy(saved = false, loading = false, error = null, errorNonce = 0) !=
            initialSnapshot.copy(saved = false, loading = false, error = null, errorNonce = 0)

    val hasInput: Boolean
        get() = _state.value.title.isNotBlank() || _state.value.originalTitle.isNotBlank() ||
            _state.value.year.isNotBlank() || _state.value.genre.isNotBlank() ||
            _state.value.nationality.isNotBlank() || _state.value.director.isNotBlank() ||
            _state.value.cast.isNotBlank() || _state.value.duration.isNotBlank() ||
            _state.value.url.isNotBlank() || _state.value.plot.isNotBlank() ||
            _state.value.rating != null || _state.value.isDownloaded ||
            _state.value.coverPath != null || _state.value.watchedDates.any { it.isNotBlank() }

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
                    watchedDates = loadWatchDates(movie.watchedAt, mediaDateRepository.getFor(movie.id, MediaType.MOVIES)),
                    url = movie.url.orEmpty(),
                    isDownloaded = movie.isDownloaded,
                    plot = movie.plot.orEmpty(),
                    coverPath = movie.coverPath,
                    loading = false,
                )
                initialSnapshot = _state.value
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
    fun setWatchedDate(index: Int, value: String) = update {
        copy(watchedDates = watchedDates.toMutableList().also { list ->
            if (index in list.indices) list[index] = value
        })
    }

    /** Ещё одно поле даты просмотра — пересмотр. */
    fun addWatchedDate() = update { copy(watchedDates = watchedDates + "") }

    /** Убрать поле даты. Последнее не убираем — вместо него пустое поле. */
    fun removeWatchedDate(index: Int) = update {
        copy(
            watchedDates = watchedDates.filterIndexed { i, _ -> i != index }
                .ifEmpty { listOf("") },
        )
    }
    fun setUrl(value: String) = update { copy(url = value) }
    fun setPlot(value: String) = update { copy(plot = value) }
    fun setDownloaded(value: Boolean) = update { copy(isDownloaded = value) }
    fun setRating(value: Int?) = update { copy(rating = value) }

    /**
     * Временную обложку убираем, сохранённую — нет.
     *
     * Пользователь выбрал обложку и нажал «Назад»: файл `cover_{uuid}.webp`
     * остался бы в `filesDir/covers/` навсегда. Обложка, уже сохранённая в БД,
     * — источник истины, её удалять нельзя.
     */
    public override fun onCleared() {
        super.onCleared()
        val path = _state.value.coverPath
        if (path != null && path != originalCoverPath) {
            runCatching { File(path).delete() }
        }
    }

    /** Обложка из галереи: файл сохраняется, путь — в состояние. */
    fun attachCover(uri: Uri) {
        viewModelScope.launch {
            val previous = _state.value.coverPath
            val path = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    coverStorage.saveTemp(input, "jpg")
                }
            }.getOrNull() ?: return@launch
            // Прошлую временную обложку убираем: иначе выбор A, потом B
            // оставит A в covers/ навсегда. Сохранённую в БД — не трогаем.
            if (previous != null && previous != path && previous != originalCoverPath) {
                runCatching { File(previous).delete() }
            }
            update { copy(coverPath = path) }
        }
    }

    fun clearCover() {
        // Временный файл удаляем сразу, сохранённый — только после успешного
        // save(): «Отменить» не должно стирать обложку фильма.
        _state.value.coverPath
            ?.takeIf { it != originalCoverPath }
            ?.let { runCatching { File(it).delete() } }
        update { copy(coverPath = null) }
    }

    fun save() {
        val s = _state.value
        if (s.title.isBlank()) return
        // Повторное нажатие «Сохранить» не должно вставлять второй фильм:
        // у нового фильма isEditing = false, и save() идёт в insert.
        if (s.saved) return
        // Пока идёт запись, повторное нажатие — no-op. Флаг `saved` ставится
        // после транзакции, и в окне между ними второй вызов вставлял вторую
        // строку: у фильма нет уникального индекса, как у ISBN у книги.
        if (saving) return
        // Неразобранная дата — не «нет даты»: молча потерять введённое
        // значение нельзя, показываем ошибку (как в книжной форме).
        if (s.watchedDates.any { it.isNotBlank() && parseDate(it) == null }) {
            _state.value = s.copy(error = MovieEditError.INVALID_DATE, errorNonce = s.errorNonce + 1)
            return
        }
        saving = true
        viewModelScope.launch {
            try {
                val coverPath = s.coverPath
                // Основная дата — самая ранняя: из неё Tellico берёт первую дату.
                // Остальные — дополнительные.
                val dates = s.watchedDates.mapNotNull { parseDate(it) }.sorted()
                val form = MovieEntity(
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
                    watchedAt = dates.firstOrNull(),
                    coverPath = coverPath,
                    plot = s.plot.trim().takeIf { it.isNotEmpty() },
                    comments = null,
                    url = s.url.trim().takeIf { it.isNotEmpty() },
                    isDownloaded = s.isDownloaded,
                    createdAt = 0L,
                    updatedAt = System.currentTimeMillis(),
                    source = MediaSource.MANUAL,
                )
                val movieId = if (isEditing) {
                    val existing = movieRepository.findById(editingMovieId) ?: return@launch
                    // Поля, которых в форме нет (комментарии Tellico, чужие
                    // поля, источник), сохраняем: правка названия не должна
                    // стирать то, что приехало из импорта.
                    saveMovieUseCase(
                        form.copy(
                            comments = existing.comments,
                            extraFields = existing.extraFields,
                            source = existing.source,
                            createdAt = existing.createdAt,
                        ),
                        dates.drop(1),
                    )
                } else {
                    saveMovieUseCase(form, dates.drop(1))
                }
                if (movieId <= 0L) return@launch
                // Прежняя обложка больше не нужна: путь новой — с UUID-именем.
                originalCoverPath
                    ?.takeIf { it != coverPath }
                    ?.let { coverStorage.delete(it) }
                originalCoverPath = coverPath
                _state.value = _state.value.copy(saved = true)
            } finally {
                saving = false
            }
        }
    }

    /** Даты просмотра фильма — по возрастанию, в виде строк формы. */
    private fun loadWatchDates(primary: Long?, extra: List<Long>): List<String> {
        val dates = (listOfNotNull(primary) + extra).sorted()
        if (dates.isEmpty()) return listOf("")
        return dates.map { DATE_FMT.format(it.toDayDate()) }
    }

    private fun update(block: MovieEditState.() -> MovieEditState) {
        // Правка любого поля снимает прошлую ошибку «неверная дата».
        _state.value = _state.value.block().copy(error = null)
    }

    private fun parseDate(raw: String): Long? {
        if (raw.isBlank()) return null
        return runCatching { LocalDate.parse(raw, DATE_FMT).toDayMs() }.getOrNull()
    }

    companion object {
        const val KEY_MOVIE_ID = "movieId"
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
    }
}
