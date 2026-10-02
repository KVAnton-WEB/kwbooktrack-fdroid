package ru.antonkw.kwbooktrack.ui.add

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
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookSource
import ru.antonkw.kwbooktrack.data.repository.BookRepository
import ru.antonkw.kwbooktrack.data.repository.PhotoRepository

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AddBookViewModelTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var bookRepository: BookRepository
    private val dispatcher = StandardTestDispatcher()

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

        val books = db.bookDao().observeAll().first()
        assertThat(books).hasSize(1)
        val book = books.first()
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

        val books = db.bookDao().observeAll().first()
        assertThat(books.first().isbn13).isNull() // only 13-digit ISBNs are stored
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

        val book = db.bookDao().observeAll().first().first()
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

        val book = db.bookDao().observeAll().first().first()
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

        val book = db.bookDao().observeAll().first().first()
        assertThat(book.printYear).isEqualTo(2018)
        assertThat(book.printPages).isEqualTo(32)
    }
}
