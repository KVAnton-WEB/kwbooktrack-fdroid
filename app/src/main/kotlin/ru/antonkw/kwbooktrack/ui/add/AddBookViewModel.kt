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
import ru.antonkw.kwbooktrack.data.storage.CoverStorage
import ru.antonkw.kwbooktrack.data.text.formatDecimal
import ru.antonkw.kwbooktrack.data.text.parseDecimal
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import ru.antonkw.kwbooktrack.data.time.toLocalDate
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
    // "Прочитано" — the finished flag and the date it was finished on.
    val isRead: Boolean = false,
    val finishedDate: String = "",
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
    private val coverStorage: CoverStorage,
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
    // Save, because state.coverPath doesn't track CoverStorage.promoteToBook()).
    // Mutated only from init() and save() — read from the cleanup paths.
    private var originalCoverPath: String? = null

    // True once the user explicitly hit "remove cover" on a book that had
    // one. Needed because promoteToBook() returns null for BOTH "cleared"
    // and "left alone", and save() needs to tell them apart to honour the
    // removal. Cleared again by attachCover().
    private var coverRemoved = false

    // Per-suggestion flows: only the field they depend on is `distinctUntilChanged`d
    // so typing in `author` doesn't recompute `genreSuggestions` and vice versa.
    private val booksFlow = bookRepository.observeAll()

    /** Already-known values of [get], filtered by what the user typed into [field]. */
    private fun suggestions(
        field: (AddBookState) -> String,
        get: (BookEntity) -> String?,
    ): StateFlow<List<String>> = combine(
        booksFlow,
        _state.map(field).distinctUntilChanged(),
    ) { books, prefix ->
        suggest(books, prefix, get)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val authorSuggestions = suggestions({ it.author }, { it.author })
    val genreSuggestions = suggestions({ it.genre }, { it.genre })
    val publisherSuggestions = suggestions({ it.publisher }, { it.publisher })
    val seriesSuggestions = suggestions({ it.series }, { it.series })
    val bindingSuggestions = suggestions({ it.binding }, { it.binding })
    val translatorSuggestions = suggestions({ it.translator }, { it.translator })
    val originalLanguageSuggestions = suggestions({ it.originalLanguage }, { it.originalLanguage })
    val purchasePlaceSuggestions = suggestions({ it.purchasePlace }, { it.purchasePlace })

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
                        isRead = book.status == BookStatus.FINISHED,
                        finishedDate = book.finishedAt?.let { formatDate(it) }.orEmpty(),
                        subtitle = book.subtitle.orEmpty(),
                        binding = book.binding.orEmpty(),
                        series = book.series.orEmpty(),
                        seriesNumber = book.seriesNumber.orEmpty(),
                        condition = book.condition.orEmpty(),
                        translator = book.translator.orEmpty(),
                        printYear = book.printYear?.toString().orEmpty(),
                        printPages = book.printPages?.let { formatDecimal(it) }.orEmpty(),
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
    fun setIsRead(value: Boolean) {
        val s = _state.value
        _state.value = s.copy(
            isRead = value,
            // Prefill today the first time the book is ticked as read, so
            // finished_at gets a value without the user typing a date.
            finishedDate = if (value && s.finishedDate.isBlank()) formatDate(System.currentTimeMillis()) else s.finishedDate,
        )
    }
    fun setFinishedDate(value: String) { _state.value = _state.value.copy(finishedDate = value) }
    fun setSubtitle(value: String) { _state.value = _state.value.copy(subtitle = value) }
    fun setBinding(value: String) { _state.value = _state.value.copy(binding = value) }
    fun setSeries(value: String) { _state.value = _state.value.copy(series = value) }
    fun setSeriesNumber(value: String) { _state.value = _state.value.copy(seriesNumber = value) }
    fun setCondition(value: String) { _state.value = _state.value.copy(condition = value) }
    fun setTranslator(value: String) { _state.value = _state.value.copy(translator = value) }
    fun setPrintYear(value: String) { _state.value = _state.value.copy(printYear = value.filter { it.isDigit() }) }
    fun setPrintPages(value: String) {
        // Дробное число: цифры и один разделитель (запятая).
        val parts = value.replace('.', ',').split(',')
        val cleaned = parts[0].filter { it.isDigit() } +
            if (parts.size > 1) "," + parts[1].filter { it.isDigit() } else ""
        _state.value = _state.value.copy(printPages = cleaned)
    }
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
                    val ext = context.contentResolver.getType(uri)?.substringAfter("/")
                        ?: uri.lastPathSegment?.substringAfterLast('.', "")
                        ?: "jpg"
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        coverStorage.saveTemp(input, ext)
                    } ?: return@runCatching null
                }.getOrNull()
            }
            if (saved != null) {
                if (previous != null && previous != saved && previous != originalCoverPath) {
                    runCatching { File(previous).delete() }
                }
                coverRemoved = false
                _state.value = _state.value.copy(coverPath = saved)
            }
        }
    }

    fun clearCover() {
        // Same protection as attachCover() / onCleared(): never unlink the
        // currently-persisted cover when the user is editing — Discard must
        // leave the book's actual file on disk so the cover survives the
        // cancel. Only delete when clearing a temp selection. The removal is
        // recorded instead: save() unlinks the persisted file and nulls the
        // row's coverPath then.
        if (_state.value.coverPath != null && _state.value.coverPath != originalCoverPath) {
            _state.value.coverPath?.let { runCatching { File(it).delete() } }
        }
        coverRemoved = _state.value.coverPath != null
        _state.value = _state.value.copy(coverPath = null)
    }

    fun newCameraTempUri(): Uri {
        val capturesDir = File(context.cacheDir, "captures").apply { mkdirs() }
        val target = File(capturesDir, "capture_${System.currentTimeMillis()}.jpg")
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
    }

    // Public (not protected) so tests can drive the Back / Discard path
    // directly — the v0.5.9 "cover vanished" bug lived here and the only way
    // to reach it from a unit test is to call it.
    public override fun onCleared() {
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
        // Snapshot alongside [s]: clearCover() could flip the flag between the
        // save() call and this coroutine actually running.
        val removedCover = coverRemoved
        return viewModelScope.launch {
            val now = System.currentTimeMillis()
            val purchaseDate = parseDateInput(s.purchaseDate)
            val purchasePrice = parsePriceInput(s.purchasePrice)
            val finishedDate = parseDateInput(s.finishedDate)
            val printYear = s.printYear.toIntOrNull()
            val printPages = parseDecimal(s.printPages)
            if (isEditing) {
                val existing = bookRepository.findById(editingBookId) ?: return@launch
                // "Прочитано" is a checkbox, so the finished date travels with
                // it: ticking it makes the book FINISHED on the date in the
                // field, unticking it clears the date and drops the book back
                // to "in progress" (or "to read" if it was never started).
                // Any other status is left alone.
                val status = when {
                    s.isRead -> BookStatus.FINISHED
                    existing.status == BookStatus.FINISHED ->
                        if (existing.startedAt != null) BookStatus.READING else BookStatus.WISHLIST
                    else -> existing.status
                }
                val finishedAt = if (s.isRead) finishedDate else null
                // What the row will point at from now on. promoteToBook()
                // returns null for both "left alone" and "cleared", so
                // removedCover is what tells them apart — without it the
                // ?: existing.coverPath fallback silently restored the cover
                // the user had just removed.
                val finalCoverPath: String? = if (removedCover) {
                    coverStorage.deleteForBook(existing.id)
                    null
                } else {
                    coverStorage.promoteToBook(existing.id, s.coverPath) ?: existing.coverPath
                }
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
                        status = status,
                        finishedAt = finishedAt,
                        coverPath = finalCoverPath,
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
                // Discard could roll it back) is now orphaned by the promotion:
                // promoteToBook() moved the new selection into its slot at
                // finalCoverPath. deleteForBook() above already unlinked it when
                // the cover was removed. Both no-op cases — nothing picked, and a
                // re-promote of the cover already at that path — resolve
                // finalCoverPath to the path the row already had, so the file is
                // still the file at the same path and there is nothing to unlink.
                if (finalCoverPath != null && originalCoverPath != null && originalCoverPath != finalCoverPath) {
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
                    status = if (s.isRead) BookStatus.FINISHED else BookStatus.WISHLIST,
                    finishedAt = if (s.isRead) finishedDate else null,
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
                val finalCoverPath = coverStorage.promoteToBook(newId, s.coverPath)
                if (finalCoverPath != null) {
                    bookRepository.update(tempBook.copy(id = newId, coverPath = finalCoverPath))
                }
                originalCoverPath = finalCoverPath
            }
            _state.value = s.copy(saved = true)
        }
    }

    private fun parseDateInput(raw: String): Long? {
        if (raw.isBlank()) return null
        for (pattern in listOf("dd.MM.yyyy", "yyyy-MM-dd")) {
            try {
                return java.time.LocalDate.parse(raw.trim(), java.time.format.DateTimeFormatter.ofPattern(pattern))
                    .toEpochMs()
            } catch (_: Exception) { /* try next */ }
        }
        return null
    }

    private fun parsePriceInput(raw: String): Double? = raw.toDoubleOrNull()

    private fun formatDate(epochMs: Long): String =
        epochMs.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))

    private fun formatPrice(value: Double): String = "%.2f".format(value)

    companion object {
        const val KEY_BOOK_ID = "bookId"
    }
}
