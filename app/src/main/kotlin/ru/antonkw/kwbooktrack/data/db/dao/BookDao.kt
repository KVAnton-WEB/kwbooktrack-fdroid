package ru.antonkw.kwbooktrack.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Transaction
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import kotlinx.coroutines.flow.Flow
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY updated_at DESC")
    fun observeAll(): Flow<List<BookEntity>>

    /** Книга по id потоком — для экрана карточки. */
    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    fun observeById(id: Long): Flow<BookEntity?>

    /**
     * Все книги разом — для снимка библиотеки.
     *
     * Suspend, а не Flow: Flow-запрос Room выполняет вне транзакции, и снимок
     * собрался бы из разных состояний БД.
     */
    @Query("SELECT * FROM books ORDER BY updated_at DESC")
    suspend fun getAll(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun findById(id: Long): BookEntity?

    @Query("SELECT * FROM books WHERE isbn13 = :isbn13 LIMIT 1")
    suspend fun findByIsbn(isbn13: String): BookEntity?

    @Query(
        """
        SELECT * FROM books WHERE title = :title AND author = :author 
        ORDER BY updated_at DESC LIMIT 1
        """,
    )
    suspend fun findByTitleAndAuthor(title: String, author: String): BookEntity?

    @Query("SELECT * FROM books WHERE status = :status ORDER BY updated_at DESC")
    fun observeByStatus(status: BookStatus): Flow<List<BookEntity>>

    @RawQuery(observedEntities = [BookEntity::class])
    fun rawSearch(query: SupportSQLiteQuery): Flow<List<BookEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(book: BookEntity): Long

    @Update
    suspend fun update(book: BookEntity): Int

    @Delete
    suspend fun delete(book: BookEntity): Int

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    /**
     * Счётчики книг потоком — экран «О приложении» считает, а не грузит.
     *
     * Оба числа в одном запросе: раздельные счётчики обновлялись бы в разные
     * моменты, и экран показывал бы «6 книг, 0 прочитано» вместо «6 и 1».
     */
    @Query(
        """
        SELECT COUNT(*) AS total,
            COALESCE(SUM(CASE WHEN status = :status THEN 1 ELSE 0 END), 0) AS finished
        FROM books
        """,
    )
    fun observeCounts(status: BookStatus): Flow<BookCounts>

    /**
     * Upsert by ISBN, merging non-null fields so manual user data
     * (purchaseDate, custom genre, rating) is not wiped by an import.
     * Wrapped in @Transaction to prevent two concurrent calls both
     * passing findByIsbn with null and double-inserting.
     */
    @Transaction
    suspend fun upsertByIsbn(book: BookEntity): Long {
        val existing = book.isbn13?.let { findByIsbn(it) }
        return if (existing != null) {
            val merged = book.mergeWith(existing)
            update(merged)
            existing.id
        } else {
            insert(book)
        }
    }

    /**
     * Upsert by ISBN first, then by title+author.
     *
     * @param snapshotId id строки в архиве (свой zip-бэкап). По нему запись
     *   обновляется точно: две книги с одинаковым названием и автором (два
     *   экземпляра без ISBN) остаются двумя книгами, а не схлопываются.
     */
    @Transaction
    suspend fun upsertFromImport(book: BookEntity, snapshotId: Long = 0L): Long {
        // Точное совпадение по id архива: та же самая строка, её надо обновить.
        if (snapshotId > 0L) {
            val byId = findById(snapshotId)
            if (byId != null && byId.title == book.title) {
                update(book.mergeWith(byId))
                return byId.id
            }
        }
        val byIsbn = book.isbn13?.let { findByIsbn(it) }
        if (byIsbn != null) {
            // Сильный ключ — ISBN: это тот же товар, импорт обновляет поля.
            update(book.mergeWith(byIsbn))
            return byIsbn.id
        }
        // Слабый ключ — название и автор: так совпадают и две разные книги
        // (два экземпляра без ISBN). Заполненные поля существующей строки не
        // трогаем — иначе вторая книга затирала бы первую.
        val existing = findByTitleAndAuthor(book.title, book.author)
        return if (existing != null) {
            update(book.mergeIntoExisting(existing))
            existing.id
        } else {
            // Новая строка: id из архива сохраняем, если он свободен — тогда
            // следующий импорт найдёт эту книгу точно, а не по названию.
            insert(if (snapshotId > 0L && findById(snapshotId) == null) {
                book.copy(id = snapshotId)
            } else {
                book
            })
        }
    }

    /**
     * Удалить книгу вместе с её заметками, фото и связями тегов.
     *
     * Внешнего ключа на `books` у заметок, фото и тегов больше нет: владельцем
     * может быть и фильм, а фильма в `books` нет, и внешний ключ его бы не
     * пустил. Поэтому каскада не будет — содержимое удаляем сами, одной
     * транзакцией, чтобы не оставить книгу без заметок на полпути.
     *
     * Сессии чтения не трогаем: у них внешний ключ на `books` остался, и они
     * уйдут каскадом сами.
     */
    @Transaction
    suspend fun deleteWithContent(bookId: Long): Int {
        deleteTagRefs(bookId)
        deletePhotos(bookId)
        deleteNotes(bookId)
        deleteDates(bookId)
        return deleteById(bookId)
    }

    @Query("DELETE FROM media_tags WHERE media_id = :bookId AND media_type = 'BOOKS'")
    suspend fun deleteTagRefs(bookId: Long): Int

    @Query("DELETE FROM photos WHERE media_id = :bookId AND media_type = 'BOOKS'")
    suspend fun deletePhotos(bookId: Long): Int

    @Query("DELETE FROM notes WHERE media_id = :bookId AND media_type = 'BOOKS'")
    suspend fun deleteNotes(bookId: Long): Int

    /** Дополнительные даты прочтения — внешнего ключа на владельца нет. */
    @Query("DELETE FROM media_dates WHERE media_id = :bookId AND media_type = 'BOOKS'")
    suspend fun deleteDates(bookId: Long): Int
}

/**
 * Проекция счётчиков: сколько книг всего и сколько прочитано.
 *
 * Не сущность: результат одного запроса `SELECT COUNT(*) …`.
 */
data class BookCounts(
    val total: Int,
    val finished: Int,
)

/**
 * Merge for imports: keep the existing row's non-null fields when the
 * incoming value is null/default. Source is preserved so re-importing via
 * Tellico does not overwrite Bookmory-sourced rows (and vice versa).
 *
 * Booleans are merged too, by "or": Tellico's `Подарок` cell is "да"/"нет"/blank,
 * and "да"→true is the only state we care about. A re-import with a blank
 * cell must not flip a flag the user set by hand.
 */
private fun BookEntity.mergeWith(existing: BookEntity): BookEntity = copy(
    id = existing.id,
    createdAt = existing.createdAt,
    // Метка «изменено» — из источника (`mdate` в Tellico, `updated_at` в
    // zip-бэкапе), а не «сейчас»: книгу изменили не сейчас, а когда её
    // редактировали в источнике.
    updatedAt = updatedAt.takeIf { it > 0L } ?: existing.updatedAt,
    isbn13 = isbn13 ?: existing.isbn13,
    subtitle = subtitle ?: existing.subtitle,
    pageCount = pageCount ?: existing.pageCount,
    language = language.takeIf { it.isNotBlank() && it != "ru" } ?: existing.language,
    genre = genre ?: existing.genre,
    publisher = publisher ?: existing.publisher,
    year = year ?: existing.year,
    coverPath = coverPath ?: existing.coverPath,
    rating = rating ?: existing.rating,
    status = if (status == BookStatus.WISHLIST && existing.status != BookStatus.WISHLIST) existing.status else status,
    startedAt = startedAt ?: existing.startedAt,
    finishedAt = finishedAt ?: existing.finishedAt,
    source = existing.source,
    purchaseDate = purchaseDate ?: existing.purchaseDate,
    purchasePrice = purchasePrice ?: existing.purchasePrice,
    // v5 — every nullable field falls back to the existing value so an
    // empty CSV cell (Bookmory importer, malformed row, omitted column)
    // doesn't overwrite a field the user set by hand.
    binding = binding ?: existing.binding,
    purchasePlace = purchasePlace ?: existing.purchasePlace,
    series = series ?: existing.series,
    seriesNumber = seriesNumber ?: existing.seriesNumber,
    condition = condition ?: existing.condition,
    translator = translator ?: existing.translator,
    printYear = printYear ?: existing.printYear,
    printPages = printPages ?: existing.printPages,
    originalLanguage = originalLanguage ?: existing.originalLanguage,
    originalTitle = originalTitle ?: existing.originalTitle,
    annotation = annotation ?: existing.annotation,
    isGift = isGift.takeIf { it } ?: existing.isGift,
    isLent = isLent.takeIf { it } ?: existing.isLent,
    isSold = isSold.takeIf { it } ?: existing.isSold,
    // Чужие поля Tellico: импорт побеждает по ключу, а ключи, которых в
    // импорте нет, остаются (пустой импорт не стирает данные).
    extraFields = existing.extraFields + extraFields,
)

/**
 * Слияние по слабому ключу (название и автор): заполненные поля существующей
 * строки не трогаем.
 *
 * Название и автор — не уникальный ключ: так совпадают и две разные книги
 * (два экземпляра без ISBN). Раньше вторая запись затирала рейтинг, даты и
 * число страниц первой, а заметки и фото обеих склеивались в одну книгу.
 *
 * Пустые места заполняются данными импорта, флаги объединяются «или».
 */
private fun BookEntity.mergeIntoExisting(existing: BookEntity): BookEntity = existing.copy(
    // Метка «изменено» — из источника (`mdate` в Tellico, `updated_at` в
    // zip-бэкапе), а не «сейчас»: книгу изменили не сейчас, а когда её
    // редактировали в источнике.
    updatedAt = updatedAt.takeIf { it > 0L } ?: existing.updatedAt,
    isbn13 = isbn13 ?: existing.isbn13,
    subtitle = existing.subtitle ?: subtitle,
    pageCount = existing.pageCount ?: pageCount,
    language = language.takeIf { it.isNotBlank() && it != "ru" } ?: existing.language,
    genre = existing.genre ?: genre,
    publisher = existing.publisher ?: publisher,
    year = existing.year ?: year,
    coverPath = existing.coverPath ?: coverPath,
    rating = existing.rating ?: rating,
    // Статус улучшаем только из «в wishlist» — уменьшать его нельзя.
    status = if (existing.status == BookStatus.WISHLIST && status != BookStatus.WISHLIST) {
        status
    } else {
        existing.status
    },
    startedAt = existing.startedAt ?: startedAt,
    finishedAt = existing.finishedAt ?: finishedAt,
    source = existing.source,
    purchaseDate = existing.purchaseDate ?: purchaseDate,
    purchasePrice = existing.purchasePrice ?: purchasePrice,
    binding = existing.binding ?: binding,
    purchasePlace = existing.purchasePlace ?: purchasePlace,
    series = existing.series ?: series,
    seriesNumber = existing.seriesNumber ?: seriesNumber,
    condition = existing.condition ?: condition,
    translator = existing.translator ?: translator,
    printYear = existing.printYear ?: printYear,
    printPages = existing.printPages ?: printPages,
    originalLanguage = existing.originalLanguage ?: originalLanguage,
    originalTitle = existing.originalTitle ?: originalTitle,
    annotation = existing.annotation ?: annotation,
    isGift = existing.isGift || isGift,
    isLent = existing.isLent || isLent,
    isSold = existing.isSold || isSold,
    extraFields = existing.extraFields + extraFields,
)
