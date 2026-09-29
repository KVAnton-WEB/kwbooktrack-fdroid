package ru.antonkw.kwbooktrack.ui.add

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import java.io.File
import javax.inject.Inject

data class AddBookState(
    val title: String = "",
    val author: String = "",
    val isbn: String = "",
    val pageCount: String = "",
    val year: String = "",
    val publisher: String = "",
    val genre: String = "",
    val rating: Int? = null,
    val coverPath: String? = null,
    val purchaseDate: String = "",
    val purchasePrice: String = "",
    val saved: Boolean = false,
    val loading: Boolean = false,
)

@HiltViewModel
class AddBookViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val bookRepository: BookRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val editingBookId: Long = savedStateHandle.get<Long>(KEY_BOOK_ID) ?: 0L
    val isEditing: Boolean = editingBookId > 0L

    private val _state = MutableStateFlow(AddBookState(loading = isEditing))
    val state: StateFlow<AddBookState> = _state.asStateFlow()

    private val initialState: AddBookState = _state.value

    val authorSuggestions: StateFlow<List<String>> = combine(
        bookRepository.observeAll(),
        _state,
    ) { books, current ->
        suggest(books, current.author) { it.author }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val genreSuggestions: StateFlow<List<String>> = combine(
        bookRepository.observeAll(),
        _state,
    ) { books, current ->
        suggest(books, current.genre) { it.genre }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val publisherSuggestions: StateFlow<List<String>> = combine(
        bookRepository.observeAll(),
        _state,
    ) { books, current ->
        suggest(books, current.publisher) { it.publisher }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private fun suggest(
        books: List<BookEntity>,
        prefix: String,
        get: (BookEntity) -> String?,
    ): List<String> {
        val p = prefix.trim().lowercase()
        return books.asSequence()
            .mapNotNull { get(it)?.trim()?.takeIf(String::isNotEmpty) }
            .filter { p.isEmpty() || it.lowercase().contains(p) }
            .distinct()
            .sorted()
            .take(8)
            .toList()
    }

    val isDirty: Boolean
        get() = _state.value.copy(saved = false, loading = false) != initialState.copy(saved = false, loading = false)

    init {
        if (isEditing) {
            viewModelScope.launch {
                val book = bookRepository.findById(editingBookId)
                if (book != null) {
                    _state.value = AddBookState(
                        title = book.title,
                        author = book.author,
                        isbn = book.isbn13.orEmpty(),
                        pageCount = book.pageCount?.toString().orEmpty(),
                        year = book.year?.toString().orEmpty(),
                        publisher = book.publisher.orEmpty(),
                        genre = book.genre.orEmpty(),
                        rating = book.rating,
                        coverPath = book.coverPath,
                        purchaseDate = book.purchaseDate?.let { formatDate(it) }.orEmpty(),
                        purchasePrice = book.purchasePrice?.let { formatPrice(it) }.orEmpty(),
                        loading = false,
                    )
                } else {
                    _state.value = _state.value.copy(loading = false)
                }
            }
        }
    }

    fun setTitle(value: String) { _state.value = _state.value.copy(title = value) }
    fun setAuthor(value: String) { _state.value = _state.value.copy(author = value) }
    fun setIsbn(value: String) { _state.value = _state.value.copy(isbn = value.filter { it.isDigit() || it == 'X' || it == 'x' }) }
    fun setPageCount(value: String) { _state.value = _state.value.copy(pageCount = value.filter { it.isDigit() }) }
    fun setYear(value: String) { _state.value = _state.value.copy(year = value.filter { it.isDigit() }) }
    fun setPublisher(value: String) { _state.value = _state.value.copy(publisher = value) }
    fun setGenre(value: String) { _state.value = _state.value.copy(genre = value) }
    fun setRating(rating: Int?) { _state.value = _state.value.copy(rating = rating?.coerceIn(1, 5)) }
    fun setPurchaseDate(value: String) { _state.value = _state.value.copy(purchaseDate = value) }
    fun setPurchasePrice(value: String) {
        val cleaned = value.replace(',', '.').filter { it.isDigit() || it == '.' }
        _state.value = _state.value.copy(purchasePrice = cleaned)
    }

    fun attachCover(uri: Uri) {
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
                    val ext = context.contentResolver.getType(uri)?.substringAfter("/")
                        ?: uri.lastPathSegment?.substringAfterLast('.', "")
                        ?: "jpg"
                    val tempId = -System.currentTimeMillis()
                    val target = File(coversDir, "cover_$tempId.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { out -> input.copyTo(out) }
                    } ?: return@runCatching null
                    target.absolutePath
                }.getOrNull()
            }
            if (saved != null) {
                _state.value = _state.value.copy(coverPath = saved)
            }
        }
    }

    fun clearCover() {
        _state.value.coverPath?.let { File(it).delete() }
        _state.value = _state.value.copy(coverPath = null)
    }

    fun newCameraTempUri(): Uri {
        val capturesDir = File(context.cacheDir, "captures").apply { mkdirs() }
        val target = File(capturesDir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    fun save(): Job? {
        val s = _state.value
        if (s.title.isBlank()) return null
        return viewModelScope.launch {
            val now = System.currentTimeMillis()
            val purchaseDate = parseDateInput(s.purchaseDate)
            val purchasePrice = parsePriceInput(s.purchasePrice)
            if (isEditing) {
                val existing = bookRepository.findById(editingBookId) ?: return@launch
                val finalCoverPath = renameCoverToBookId(existing.id, s.coverPath)
                bookRepository.update(
                    existing.copy(
                        title = s.title,
                        author = s.author.ifBlank { "(автор неизвестен)" },
                        isbn13 = s.isbn.takeIf { it.length == 13 },
                        pageCount = s.pageCount.toIntOrNull(),
                        year = s.year.toIntOrNull(),
                        publisher = s.publisher.takeUnless { it.isBlank() },
                        genre = s.genre.takeUnless { it.isBlank() },
                        rating = s.rating,
                        coverPath = finalCoverPath ?: existing.coverPath,
                        purchaseDate = purchaseDate,
                        purchasePrice = purchasePrice,
                        updatedAt = now,
                    ),
                )
            } else {
                val tempBook = BookEntity(
                    title = s.title,
                    author = s.author.ifBlank { "(автор неизвестен)" },
                    isbn13 = s.isbn.takeIf { it.length == 13 },
                    pageCount = s.pageCount.toIntOrNull(),
                    year = s.year.toIntOrNull(),
                    publisher = s.publisher.takeUnless { it.isBlank() },
                    genre = s.genre.takeUnless { it.isBlank() },
                    rating = s.rating,
                    status = BookStatus.WISHLIST,
                    createdAt = now,
                    updatedAt = now,
                    source = BookSource.MANUAL,
                    purchaseDate = purchaseDate,
                    purchasePrice = purchasePrice,
                )
                val newId = bookRepository.insert(tempBook)
                val finalCoverPath = renameCoverToBookId(newId, s.coverPath)
                if (finalCoverPath != null) {
                    bookRepository.update(tempBook.copy(id = newId, coverPath = finalCoverPath))
                }
            }
            _state.value = s.copy(saved = true)
        }
    }

    private fun renameCoverToBookId(bookId: Long, currentPath: String?): String? {
        if (currentPath == null) return null
        val src = File(currentPath)
        if (!src.exists()) return currentPath
        val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
        val ext = src.extension.ifEmpty { "jpg" }
        val target = File(coversDir, "$bookId.$ext")
        return try {
            // Удаляем все предыдущие обложки этой книги — могут быть с другим расширением.
            coversDir.listFiles { f -> f.isFile && f.name.startsWith("$bookId.") }
                ?.forEach { it.delete() }
            if (src.absolutePath != target.absolutePath) {
                src.copyTo(target, overwrite = true)
                src.delete()
            }
            target.absolutePath
        } catch (_: Exception) {
            currentPath
        }
    }

    private fun parseDateInput(raw: String): Long? {
        if (raw.isBlank()) return null
        for (pattern in listOf("dd.MM.yyyy", "yyyy-MM-dd")) {
            try {
                return java.time.LocalDate.parse(raw.trim(), java.time.format.DateTimeFormatter.ofPattern(pattern))
                    .atStartOfDay(java.time.ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    private fun parsePriceInput(raw: String): Double? = raw.toDoubleOrNull()

    private fun formatDate(epochMs: Long): String =
        java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneOffset.UTC).toLocalDate()
            .format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))

    private fun formatPrice(value: Double): String = "%.2f".format(value)

    companion object {
        const val KEY_BOOK_ID = "bookId"
    }
}
