package ru.antonkw.kwbooktrack.ui.add

import ru.antonkw.kwbooktrack.data.storage.CoverTranscoder
import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
import org.robolectric.Shadows
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository
import ru.antonkw.kwbooktrack.data.storage.CoverStorage
import ru.antonkw.kwbooktrack.data.storage.FileCoverStorage
import ru.antonkw.kwbooktrack.data.time.toEpochMs
import java.io.ByteArrayInputStream
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddBookViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private val dispatcher = StandardTestDispatcher()

    private val SEEDED_TITLE = "Dune"

    /**
     * The suspend Room writes inside save() run on Room's executor — a real
     * thread the test scheduler can't advance — so `advanceUntilIdle()` can
     * return before the row exists. Wait for it instead of racing it.
     */
    private suspend fun savedBook() = db.bookDao().observeAll().first { it.isNotEmpty() }.first()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        bookRepository = BookRepository(
            db.bookDao(),
            db.tagDao(),
            ru.antonkw.kwbooktrack.data.NoOpCoverStorage,
            PhotoRepository(db.photoDao(), ru.antonkw.kwbooktrack.data.NoOpPhotoStorage),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun saveInsertsBookWithManualSource() = runTest(dispatcher) {
        val vm = AddBookViewModel(androidx.lifecycle.SavedStateHandle(), bookRepository, ApplicationProvider.getApplicationContext(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage)
        vm.setTitle("Dune")
        vm.setAuthor("Frank Herbert")
        vm.setIsbn("9780441172719")
        vm.setPageCount("500")
        vm.save()
        advanceUntilIdle()

        val book = savedBook()
        assertThat(book.title).isEqualTo("Dune")
        assertThat(book.author).isEqualTo("Frank Herbert")
        assertThat(book.isbn13).isEqualTo("9780441172719")
        assertThat(book.pageCount).isEqualTo(500)
        assertThat(book.source).isEqualTo(BookSource.MANUAL)
    }

    @Test
    fun emptyTitleDoesNotSave() = runTest(dispatcher) {
        val vm = AddBookViewModel(androidx.lifecycle.SavedStateHandle(), bookRepository, ApplicationProvider.getApplicationContext(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage)
        vm.save()
        advanceUntilIdle()

        val books = db.bookDao().observeAll().first()
        assertThat(books).isEmpty()
    }

    @Test
    fun setIsbnStripsNonDigits() = runTest(dispatcher) {
        val vm = AddBookViewModel(androidx.lifecycle.SavedStateHandle(), bookRepository, ApplicationProvider.getApplicationContext(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage)
        vm.setIsbn("978-0-441-17271-X")
        assertThat(vm.state.value.isbn).isEqualTo("978044117271X")
    }

    @Test
    fun setPageCountStripsNonDigits() = runTest(dispatcher) {
        val vm = AddBookViewModel(androidx.lifecycle.SavedStateHandle(), bookRepository, ApplicationProvider.getApplicationContext(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage)
        vm.setPageCount("5x0x0")
        assertThat(vm.state.value.pageCount).isEqualTo("500")
    }

    @Test
    fun shortIsbnIsKeptAsIs() = runTest(dispatcher) {
        val vm = AddBookViewModel(androidx.lifecycle.SavedStateHandle(), bookRepository, ApplicationProvider.getApplicationContext(), ru.antonkw.kwbooktrack.data.NoOpCoverStorage)
        vm.setIsbn("12345")
        vm.setTitle("Test")
        vm.save()
        advanceUntilIdle()

        assertThat(savedBook().isbn13).isNull() // only 13-digit ISBNs are stored
    }

    @Test
    fun v5ExtendedFieldsRoundtripThroughSave() = runTest(dispatcher) {
        // v5 — confirm the AddBookViewModel setters and save() roundtrip
        // the full new field set: subtitles, series, gift/lent/sold, etc.
        val vm = AddBookViewModel(
            androidx.lifecycle.SavedStateHandle(),
            bookRepository,
            ApplicationProvider.getApplicationContext(),
            ru.antonkw.kwbooktrack.data.NoOpCoverStorage,
        )
        vm.setTitle("Hyperion")
        vm.setAuthor("Dan Simmons")
        vm.setSubtitle("Песни Гипериона")
        vm.setBinding("Твёрдый")
        vm.setSeries("Гиперион")
        vm.setSeriesNumber("1")
        vm.setCondition("Новое")
        vm.setTranslator("А. Клементьев")
        vm.setPrintYear("2018")
        vm.setPrintPages("32")
        vm.setOriginalLanguage("en")
        vm.setOriginalTitle("Hyperion")
        vm.setAnnotation("Далёкое будущее...")
        vm.setPurchasePlace("Лабиринт")
        vm.setIsGift(true)
        vm.setIsLent(false)
        vm.setIsSold(false)
        vm.save()
        advanceUntilIdle()

        val book = savedBook()
        assertThat(book.subtitle).isEqualTo("Песни Гипериона")
        assertThat(book.binding).isEqualTo("Твёрдый")
        assertThat(book.series).isEqualTo("Гиперион")
        assertThat(book.seriesNumber).isEqualTo("1")
        assertThat(book.condition).isEqualTo("Новое")
        assertThat(book.translator).isEqualTo("А. Клементьев")
        assertThat(book.printYear).isEqualTo(2018)
        assertThat(book.printPages).isEqualTo(32)
        assertThat(book.originalLanguage).isEqualTo("en")
        assertThat(book.originalTitle).isEqualTo("Hyperion")
        assertThat(book.annotation).isEqualTo("Далёкое будущее...")
        assertThat(book.purchasePlace).isEqualTo("Лабиринт")
        assertThat(book.isGift).isTrue()
        assertThat(book.isLent).isFalse()
        assertThat(book.isSold).isFalse()
    }

    @Test
    fun emptyV5FieldsCollapseToNullOnSave() = runTest(dispatcher) {
        // The "user clears the field" path: setters write "" to state, but
        // save() must map "" → null so the column stays NULL instead of
        // carrying an empty-string marker.
        val vm = AddBookViewModel(
            androidx.lifecycle.SavedStateHandle(),
            bookRepository,
            ApplicationProvider.getApplicationContext(),
            ru.antonkw.kwbooktrack.data.NoOpCoverStorage,
        )
        vm.setTitle("Foo")
        vm.setAuthor("Bar")
        vm.setSubtitle("")
        vm.setSeries("   ")
        vm.save()
        advanceUntilIdle()

        val book = savedBook()
        assertThat(book.subtitle).isNull()
        assertThat(book.series).isNull()
    }

    @Test
    fun printYearAndPrintPagesParseInt() = runTest(dispatcher) {
        val vm = AddBookViewModel(
            androidx.lifecycle.SavedStateHandle(),
            bookRepository,
            ApplicationProvider.getApplicationContext(),
            ru.antonkw.kwbooktrack.data.NoOpCoverStorage,
        )
        vm.setTitle("Foo")
        vm.setAuthor("Bar")
        vm.setPrintYear("20abc18")
        vm.setPrintPages("3.2")
        vm.save()
        advanceUntilIdle()

        val book = savedBook()
        assertThat(book.printYear).isEqualTo(2018)
        assertThat(book.printPages).isEqualTo(32)
    }
    // --- read flag + finished date ---------------------------------------

    private suspend fun seedBook(
        status: BookStatus = BookStatus.WISHLIST,
        startedAt: Long? = null,
        finishedAt: Long? = null,
    ): Long {
        val now = System.currentTimeMillis()
        return bookRepository.insert(
            BookEntity(
                title = SEEDED_TITLE,
                author = "Frank Herbert",
                status = status,
                startedAt = startedAt,
                finishedAt = finishedAt,
                createdAt = now,
                updatedAt = now,
            ),
        )
    }

    @Test
    fun tickingReadMakesBookFinishedWithPrefilledDate() = runTest(dispatcher) {
        val id = seedBook()
        val vm = editVm(id)
        vm.setIsRead(true)
        assertThat(vm.state.value.finishedDate).isNotEmpty()
        vm.save()?.join()

        val saved = bookRepository.findById(id)!!
        assertThat(saved.status).isEqualTo(BookStatus.FINISHED)
        assertThat(saved.finishedAt).isNotNull()
    }

    @Test
    fun untickingReadClearsFinishedDate() = runTest(dispatcher) {
        // The "marked as read by mistake" case: the date must go away with
        // the flag, otherwise the book still counts as finished in stats.
        val id = seedBook(status = BookStatus.FINISHED, finishedAt = System.currentTimeMillis())
        val vm = editVm(id)
        assertThat(vm.state.value.isRead).isTrue()
        vm.setIsRead(false)
        vm.save()?.join()

        val saved = bookRepository.findById(id)!!
        assertThat(saved.finishedAt).isNull()
        assertThat(saved.status).isEqualTo(BookStatus.WISHLIST)
    }

    @Test
    fun untickingReadKeepsStartedBookInReading() = runTest(dispatcher) {
        val id = seedBook(
            status = BookStatus.FINISHED,
            startedAt = System.currentTimeMillis(),
            finishedAt = System.currentTimeMillis(),
        )
        val vm = editVm(id)
        vm.setIsRead(false)
        vm.save()?.join()

        assertThat(bookRepository.findById(id)!!.status).isEqualTo(BookStatus.READING)
    }

    @Test
    fun finishedDateCanBeEdited() = runTest(dispatcher) {
        val id = seedBook(status = BookStatus.FINISHED, finishedAt = System.currentTimeMillis())
        val vm = editVm(id)
        vm.setFinishedDate("01.05.2024")
        vm.save()?.join()

        assertThat(bookRepository.findById(id)!!.finishedAt)
            .isEqualTo(java.time.LocalDate.of(2024, 5, 1).toEpochMs())
    }

    // --- cover lifecycle -------------------------------------------------
    //
    // These run against the real FileCoverStorage. The bugs they pin lived in
    // the interaction between the ViewModel's cleanup paths (attachCover /
    // clearCover / onCleared / save) and the files on disk, which the
    // NoOpCoverStorage used above cannot exercise at all.
    //
    // Note the waits: the edit-mode init and attachCover() both suspend on
    // Room's executor / Dispatchers.IO — real threads that the test scheduler
    // can't advance — so `advanceUntilIdle()` returns before they finish.
    // Wait for the observable effect instead (state emission / job join).

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private val coverStorage: CoverStorage get() = FileCoverStorage(context, CoverTranscoder())

    private val coversDir: File get() = File(context.filesDir, "covers").apply { mkdirs() }

    /** Feeds a fake picked image through ContentResolver so attachCover can copy it. */
    private fun pickedImage(
        name: String = "picked.jpg",
        bytes: ByteArray = byteArrayOf(0x11, 0x22),
    ): Uri = Uri.parse("content://test/$name").also {
        Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>().contentResolver)
            .registerInputStream(it, ByteArrayInputStream(bytes))
    }

    private suspend fun pickCover(vm: AddBookViewModel, name: String = "picked.jpg") {
        val before = vm.state.value.coverPath
        vm.attachCover(pickedImage(name))
        vm.state.first { it.coverPath != null && it.coverPath != before }
    }

    /** An edit-mode VM whose init coroutine has finished loading the book. */
    private suspend fun editVm(bookId: Long): AddBookViewModel {
        val vm = AddBookViewModel(
            androidx.lifecycle.SavedStateHandle(mapOf("bookId" to bookId)),
            bookRepository,
            context,
            coverStorage,
        )
        vm.state.first { it.title == SEEDED_TITLE }
        return vm
    }

    /** A book whose cover lives at the canonical `covers/{id}.jpg`. */
    private suspend fun bookWithCover(): Pair<Long, File> {
        val now = System.currentTimeMillis()
        val id = bookRepository.insert(
            BookEntity(
                title = SEEDED_TITLE,
                author = "Frank Herbert",
                createdAt = now,
                updatedAt = now,
            ),
        )
        val cover = File(coversDir, "$id.jpg").apply { writeBytes(byteArrayOf(0xCA.toByte())) }
        bookRepository.update(bookRepository.findById(id)!!.copy(coverPath = cover.absolutePath))
        return id to cover
    }

    private fun newBookVm() = AddBookViewModel(
        androidx.lifecycle.SavedStateHandle(),
        bookRepository,
        context,
        coverStorage,
    )

    private fun tempCovers(): List<File> =
        coversDir.listFiles()?.filter { it.name.startsWith("cover_") }.orEmpty()

    @Test
    fun editModeBackWithoutSavingKeepsOriginalCover() = runTest(dispatcher) {
        // v0.5.9 regression: onCleared() unlinked the *persisted* cover, so the
        // row was left pointing at a file that no longer existed.
        val (id, cover) = bookWithCover()
        val vm = editVm(id)
        pickCover(vm)
        vm.onCleared()

        assertThat(cover.exists()).isTrue()
        assertThat(bookRepository.findById(id)!!.coverPath).isEqualTo(cover.absolutePath)
        // The temp copy of the new pick is cleaned up; the original is not.
        assertThat(tempCovers()).isEmpty()
    }

    @Test
    fun editModeSaveWithoutCoverChangeKeepsCoverFile() = runTest(dispatcher) {
        val (id, cover) = bookWithCover()
        val vm = editVm(id)
        vm.setTitle("Dune Messiah")
        vm.save()?.join()

        assertThat(cover.exists()).isTrue()
        assertThat(bookRepository.findById(id)!!.coverPath).isEqualTo(cover.absolutePath)
    }

    @Test
    fun editModeSaveWithNewCoverPromotesItAndDropsTheOldFile() = runTest(dispatcher) {
        val (id, oldCover) = bookWithCover()
        val vm = editVm(id)
        pickCover(vm, "new.png")
        vm.save()?.join()

        val saved = bookRepository.findById(id)!!
        // saveTemp transcodes, so a decodable pick always lands as .webp
        // whatever extension it arrived with.
        assertThat(saved.coverPath).isEqualTo(File(coversDir, "$id.webp").absolutePath)
        assertThat(File(saved.coverPath!!).exists()).isTrue()
        assertThat(oldCover.exists()).isFalse()
        assertThat(tempCovers()).isEmpty()
    }

    @Test
    fun clearCoverThenSaveDropsTheCoverFromRowAndDisk() = runTest(dispatcher) {
        // Regression: save() did `coverPath = promoteToBook(...) ?: existing.coverPath`,
        // and promoteToBook returns null both for "cleared" and for "untouched",
        // so the removed cover came back.
        val (id, cover) = bookWithCover()
        val vm = editVm(id)
        vm.clearCover()
        assertThat(cover.exists()).isTrue() // Discard must still be able to roll back
        vm.save()?.join()

        assertThat(bookRepository.findById(id)!!.coverPath).isNull()
        assertThat(cover.exists()).isFalse()
    }

    @Test
    fun clearCoverThenDiscardKeepsTheFile() = runTest(dispatcher) {
        val (id, cover) = bookWithCover()
        val vm = editVm(id)
        vm.clearCover()
        vm.onCleared()

        assertThat(cover.exists()).isTrue()
        assertThat(bookRepository.findById(id)!!.coverPath).isEqualTo(cover.absolutePath)
    }

    @Test
    fun editModeSaveWithClearedThenRepickedCoverKeepsTheNewOne() = runTest(dispatcher) {
        val (id, oldCover) = bookWithCover()
        val vm = editVm(id)
        vm.clearCover()
        // Different source name so the pick isn't a no-op; the stored file is
        // .webp either way because saveTemp transcodes.
        pickCover(vm, "again.png")
        vm.save()?.join()

        val saved = bookRepository.findById(id)!!
        assertThat(saved.coverPath).isEqualTo(File(coversDir, "$id.webp").absolutePath)
        assertThat(File(saved.coverPath!!).exists()).isTrue()
        assertThat(oldCover.exists()).isFalse()
    }

    @Test
    fun addModeSaveLeavesNoTempCoverBehind() = runTest(dispatcher) {
        val vm = newBookVm()
        vm.setTitle("Neuromancer")
        pickCover(vm)
        vm.save()?.join()
        vm.onCleared()

        val saved = savedBook()
        assertThat(File(saved.coverPath!!).exists()).isTrue()
        assertThat(tempCovers()).isEmpty()
    }

    @Test
    fun addModeDiscardLeavesNoTempCoverBehind() = runTest(dispatcher) {
        val vm = newBookVm()
        vm.setTitle("Neuromancer")
        pickCover(vm, "a.jpg")
        pickCover(vm, "b.jpg")
        vm.onCleared()

        assertThat(tempCovers()).isEmpty()
    }

}
