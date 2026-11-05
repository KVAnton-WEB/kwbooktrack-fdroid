package ru.antonkw.kwbooktrack.data

import kotlinx.coroutines.CancellationException
import ru.antonkw.kwbooktrack.data.db.dao.BookDao
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity

/**
 * DAO, который отменяет импорт на чтении книги.
 *
 * Нужен, чтобы проверить, что отмена импорта не проглатывается общим
 * `catch (e: Exception)`: апплиер и импортёры обязаны пробросить
 * [CancellationException] дальше, а не считать запись «упавшей» и отчитаться
 * об успешном импорте.
 */
class CancellingBookDao(private val real: BookDao) : BookDao by real {
    override suspend fun findById(id: Long): BookEntity? = throw CancellationException("импорт отменён")
}
