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
import kotlinx.coroutines.flow.distinctUntilChanged
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
    // --- v5: extended Tellico fields ---
    val subtitle: String = "",
    val binding: String = "",
    val series: String = "",
    val seriesNumber: String = "",
    val condition: String = "",
    val translator: String = "",
    val printYear: String = "",
    val printPages: String = "",
    val originalLanguage: String = "",
    val originalTitle: String = "",
    val annotation: String = "",
    val purchasePlace: String = "",
    val isGift: Boolean = false,
    val isLent: Boolean = false,
    val isSold: Boolean = false,
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

    // Cache the initial snapshot once. The old impl rebuilt two AddBookState
    // copies on every getter access (`get() = _state.value.copy(...) != ...`),
    // which is both wasted work and a latent race: a second `init` could
    // replace _state before the dirty check ran.
    private val initialSnapshot: AddBookState = _state.value

    // The path of the cover that is *currently* persisted for the book being
    // edited (or null in add-mode). Used to distinguish the "real" persisted
    // cover from temp copies made by attachCover() — temp copies are safe to
    // unlink on cancel, the persisted cover is not. Without this distinction
    // onCleared()/attachCover()/clearCover() would happily delete the
    // book's actual cover when the user hits Back or Discard (and even on
    // Save, because state.coverPath doesn't track renameCoverToBookId()).
    // Mutated only from init() and save() — read from the cleanup paths.
    private var originalCoverPath: String? = null

    // Per-suggestion flows: only the field they depend on is `distinctUntilChanged`d
    // so typing in `author` doesn't recompute `genreSuggestions` and vice versa.
    private val authorField = _state.map { it.author }.distinctUntilChanged()
    private val genreField = _state.map { it.genre }.distinctUntilChanged()
    private val publisherField = _state.map { it.publisher }.distinctUntilChanged()

    private val booksFlow = bookRepository.observeAll()

    val authorSuggestions: StateFlow<List<String>> = combine(
        booksFlow,
        authorField,
    ) { books, author ->
        suggest(books, author) { it.author }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val genreSuggestions: StateFlow<List<String>> = combine(
        booksFlow,
        genreField,
    ) { books, genre ->
        suggest(books, genre) { it.genre }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val publisherSuggestions: StateFlow<List<String>> = combine(
        booksFlow,
        publisherField,
    ) { books, publisher ->
        suggest(books, publisher) { it.publisher }
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
        get() = _state.value.copy(saved = false, loading = false) !=
            initialSnapshot.copy(saved = false, loading = false)

    init {
        if (isEditing) {
            viewModelScope.launch {
                val book = bookRepository.findById(editingBookId)
                if (book != null) {
                    originalCoverPath = book.coverPath
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
                        subtitle = book.subtitle.orEmpty(),
                        binding = book.binding.orEmpty(),
                        series = book.series.orEmpty(),
                        seriesNumber = book.seriesNumber.orEmpty(),
                        condition = book.condition.orEmpty(),
                        translator = book.translator.orEmpty(),
                        printYear = book.printYear?.toString().orEmpty(),
                        printPages = book.printPages?.toString().orEmpty(),
                        originalLanguage = book.originalLanguage.orEmpty(),
                        originalTitle = book.originalTitle.orEmpty(),
                        annotation = book.annotation.orEmpty(),
                        purchasePlace = book.purchasePlace.orEmpty(),
                        isGift = book.isGift,
                        isLent = book.isLent,
                        isSold = book.isSold,
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
    fun setSubtitle(value: String) { _state.value = _state.value.copy(subtitle = value) }
    fun setBinding(value: String) { _state.value = _state.value.copy(binding = value) }
    fun setSeries(value: String) { _state.value = _state.value.copy(series = value) }
    fun setSeriesNumber(value: String) { _state.value = _state.value.copy(seriesNumber = value) }
    fun setCondition(value: String) { _state.value = _state.value.copy(condition = value) }
    fun setTranslator(value: String) { _state.value = _state.value.copy(translator = value) }
    fun setPrintYear(value: String) { _state.value = _state.value.copy(printYear = value.filter { it.isDigit() }) }
    fun setPrintPages(value: String) { _state.value = _state.value.copy(printPages = value.filter { it.isDigit() }) }
    fun setOriginalLanguage(value: String) { _state.value = _state.value.copy(originalLanguage = value) }
    fun setOriginalTitle(value: String) { _state.value = _state.value.copy(originalTitle = value) }
    fun setAnnotation(value: String) { _state.value = _state.value.copy(annotation = value) }
    fun setPurchasePlace(value: String) { _state.value = _state.value.copy(purchasePlace = value) }
    fun setIsGift(value: Boolean) { _state.value = _state.value.copy(isGift = value) }
    fun setIsLent(value: Boolean) { _state.value = _state.value.copy(isLent = value) }
    fun setIsSold(value: Boolean) { _state.value = _state.value.copy(isSold = value) }

    fun attachCover(uri: Uri) {
        viewModelScope.launch {
            // Drop the previously attached *temp* file before overwriting state
            // — otherwise picking A then B leaks A in covers/ until the screen
            // is dismissed. BUT: skip the delete when `previous` is the book's
            // currently-persisted cover (originalCoverPath), so that a Back /
            // Discard after picking a new image doesn't also lose the original.
            // The original is only released after save() promotes the new
            // cover into its place.
            val previous = _state.value.coverPath
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
                if (previous != null && previous != saved && previous != originalCoverPath) {
                    runCatching { File(previous).delete() }
                }
                _state.value = _state.value.copy(coverPath = saved)
            }
        }
    }

    fun clearCover() {
        // Same protection as attachCover() / onCleared(): never unlink the
        // currently-persisted cover when the user is editing — Discard must
        // leave the book's actual file on disk so the cover survives the
        // cancel. Only delete when clearing a temp selection.
        if (_state.value.coverPath != null && _state.value.coverPath != originalCoverPath) {
            _state.value.coverPath?.let { runCatching { File(it).delete() } }
        }
        _state.value = _state.value.copy(coverPath = null)
    }

    fun newCameraTempUri(): Uri {
        val capturesDir = File(context.cacheDir, "captures").apply { mkdirs() }
        val target = File(capturesDir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    override fun onCleared() {
        super.onCleared()
        // Only unlink temp copies. The persisted book cover (originalCoverPath)
        // is the source of truth — Back / Discard must never delete it. In
        // edit-mode without cover changes state.coverPath == originalCoverPath
        // so nothing happens; in add-mode originalCoverPath is null and any
        // temp picked but not saved gets cleaned up here.
        if (_state.value.coverPath != null && _state.value.coverPath != originalCoverPath) {
            _state.value.coverPath?.let { runCatching { File(it).delete() } }
        }
    }

    fun save(): Job? {
        val s = _state.value
        if (s.title.isBlank()) return null
        return viewModelScope.launch {
            val now = System.currentTimeMillis()
            val purchaseDate = parseDateInput(s.purchaseDate)
            val purchasePrice = parsePriceInput(s.purchasePrice)
            val printYear = s.printYear.toIntOrNull()
            val printPages = s.printPages.toIntOrNull()
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
                        // v5 — explicit fields. takeUnless(blank) collapses empty
                        // strings to null so the row's NULL is preserved when
                        // the user clears the field.
                        subtitle = s.subtitle.takeUnless { it.isBlank() },
                        binding = s.binding.takeUnless { it.isBlank() },
                        series = s.series.takeUnless { it.isBlank() },
                        seriesNumber = s.seriesNumber.takeUnless { it.isBlank() },
                        condition = s.condition.takeUnless { it.isBlank() },
                        translator = s.translator.takeUnless { it.isBlank() },
                        printYear = printYear,
                        printPages = printPages,
                        originalLanguage = s.originalLanguage.takeUnless { it.isBlank() },
                        originalTitle = s.originalTitle.takeUnless { it.isBlank() },
                        annotation = s.annotation.takeUnless { it.isBlank() },
                        purchasePlace = s.purchasePlace.takeUnless { it.isBlank() },
                        isGift = s.isGift,
                        isLent = s.isLent,
                        isSold = s.isSold,
                        updatedAt = now,
                    ),
                )
                // The previous persisted cover (kept alive across the edit so
                // Discard could roll it back) is now orphaned: renameCoverToBookId
                // promoted the new selection into its slot. Drop it. (For the
                // no-cover-change case renameCoverToBookId() returns the same
                // path, so the inequality check skips the delete — we just
                // refresh the recorded path to the post-rename value.)
                if (originalCoverPath != null && originalCoverPath != finalCoverPath) {
                    runCatching { File(originalCoverPath!!).delete() }
                }
                originalCoverPath = finalCoverPath
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
                    subtitle = s.subtitle.takeUnless { it.isBlank() },
                    binding = s.binding.takeUnless { it.isBlank() },
                    series = s.series.takeUnless { it.isBlank() },
                    seriesNumber = s.seriesNumber.takeUnless { it.isBlank() },
                    condition = s.condition.takeUnless { it.isBlank() },
                    translator = s.translator.takeUnless { it.isBlank() },
                    printYear = printYear,
                    printPages = printPages,
                    originalLanguage = s.originalLanguage.takeUnless { it.isBlank() },
                    originalTitle = s.originalTitle.takeUnless { it.isBlank() },
                    annotation = s.annotation.takeUnless { it.isBlank() },
                    purchasePlace = s.purchasePlace.takeUnless { it.isBlank() },
                    isGift = s.isGift,
                    isLent = s.isLent,
                    isSold = s.isSold,
                )
                val newId = bookRepository.insert(tempBook)
                val finalCoverPath = renameCoverToBookId(newId, s.coverPath)
                if (finalCoverPath != null) {
                    bookRepository.update(tempBook.copy(id = newId, coverPath = finalCoverPath))
                }
                originalCoverPath = finalCoverPath
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
