package ru.antonkw.kwbooktrack.data.db.dao

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import ru.antonkw.kwbooktrack.data.TestDb
import ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.snapshot.ExtraField

/**
 * BookDao is the only place that mutates the books table on import. Tests
 * here pin the merge semantics so a future refactor can't silently wipe
 * user-set fields when an importer reruns.
 */
@RunWith(RobolectricTestRunner::class)
class BookDaoMergeTest {
    private lateinit var db: KwBooktrackDatabase
    private lateinit var dao: BookDao

    @Before
    fun setUp() {
        db = TestDb.createInMemory(ApplicationProvider.getApplicationContext())
        dao = db.bookDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun v5NullableFieldsArePreservedWhenIncomingIsNull() = runBlocking {
        // User sets binding manually, then re-imports a Tellico row that
        // doesn't have a Переплёт cell. Without the merge fix, binding would
        // get nulled out.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                binding = "Твёрдый",
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                binding = null, // empty CSV cell
            ),
        )

        val row = dao.observeAll().first()
        assertThat(row).isNotEmpty()
        assertThat(row.first().binding).isEqualTo("Твёрдый")
    }

    @Test
    fun v5BooleansAreORedAcrossImports() = runBlocking {
        // User marked the book as a gift. A re-import that doesn't know
        // about it (Bookmory row, blank Tellico cell) must not silently
        // flip isGift back to false.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                isGift = true,
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                isGift = false, // empty CSV cell
            ),
        )

        assertThat(dao.observeAll().first().first().isGift).isTrue()
    }

    @Test
    fun v5IncomingValueDoesNotOverwriteExistingOnWeakKey() = runBlocking {
        // Название и автор — не уникальный ключ: так совпадают и две разные
        // книги (два экземпляра без ISBN). Вторая запись не должна затирать
        // поля первой: у пользователя было «Твёрдый», а после импорта
        // осталось бы «Мягкий» — а первая книга потеряла бы своё состояние.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                binding = "Твёрдый",
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                binding = "Мягкий",
            ),
        )

        assertThat(dao.observeAll().first().first().binding).isEqualTo("Твёрдый")
    }

    @Test
    fun strongKeyIsbnLetsIncomingValueWin() = runBlocking {
        // ISBN — сильный ключ: это тот же самый товар, и импорт обновляет поля.
        // E.g. у пользователя «Твёрдый», в новой выгрузке Tellico «Мягкий» —
        // новое значение авторитетно.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                isbn13 = "9780441013593",
                createdAt = 0L,
                updatedAt = 0L,
                binding = "Твёрдый",
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                isbn13 = "9780441013593",
                createdAt = 0L,
                updatedAt = 0L,
                binding = "Мягкий",
            ),
        )

        val row = dao.observeAll().first().single()
        assertThat(row.binding).isEqualTo("Мягкий")
    }

    @Test
    fun snapshotIdUpdatesExactlyThatRow() = runBlocking {
        // Свой zip-бэкап знает id строки: импорт обновляет ровно её, а не
        // «любую книгу с таким названием». Две книги с одинаковым названием и
        // автором остаются двумя.
        dao.insert(
            BookEntity(
                title = "Мастер и Маргарит",
                author = "Булгаков",
                createdAt = 0L,
                updatedAt = 0L,
                rating = 5,
            ),
        )
        val id = dao.observeAll().first().single().id

        dao.upsertFromImport(
            BookEntity(
                title = "Мастер и Маргарит",
                author = "Булгаков",
                createdAt = 0L,
                updatedAt = 0L,
                rating = 3,
            ),
            snapshotId = id,
        )

        val rows = dao.observeAll().first()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().rating).isEqualTo(3)
    }

    @Test
    fun sourceIsPreservedAcrossImport() = runBlocking {
        // Originally imported via Bookmory; a later Tellico import must
        // not flip source back to TELLICO.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                source = MediaSource.BOOKMORY,
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                source = MediaSource.TELLICO,
            ),
        )

        assertThat(dao.observeAll().first().first().source).isEqualTo(MediaSource.BOOKMORY)
    }

    @Test
    fun statusUpgradeFromWishlistKeepsExistingFinished() = runBlocking {
        // User marks the book as FINISHED. A re-import with no status info
        // (defaults to WISHLIST in the mapper) must not downgrade it.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                status = BookStatus.FINISHED,
                finishedAt = 1_700_000_000_000L,
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                status = BookStatus.WISHLIST,
            ),
        )

        val row = dao.observeAll().first().first()
        assertThat(row.status).isEqualTo(BookStatus.FINISHED)
        assertThat(row.finishedAt).isEqualTo(1_700_000_000_000L)
    }

    @Test
    fun extraFieldsAreMergedByKeyOnImport() = runBlocking {
        // Чужие поля Tellico: импорт побеждает по ключу, а ключи, которых
        // в импорте нет, сохраняются — пустой импорт не стирает данные.
        dao.insert(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                extraFields = mapOf(
                    "чтец" to ExtraField("Чтец", "Иванова"),
                    "переводчик" to ExtraField("Переводчик", "Сидоров"),
                ),
            ),
        )
        dao.upsertFromImport(
            BookEntity(
                title = "Hyperion",
                author = "Dan Simmons",
                createdAt = 0L,
                updatedAt = 0L,
                extraFields = mapOf("чтец" to ExtraField("Чтец", "Петрова")),
            ),
        )

        val fields = dao.observeAll().first().first().extraFields
        assertThat(fields["чтец"]?.value).isEqualTo("Петрова")
        assertThat(fields["переводчик"]?.value).isEqualTo("Сидоров")
    }
}

