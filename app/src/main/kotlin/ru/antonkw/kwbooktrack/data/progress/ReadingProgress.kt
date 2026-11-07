package ru.antonkw.kwbooktrack.data.progress

import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity

/**
 * Сколько страниц прочитано в книге.
 *
 * Прогресс живёт в сессиях чтения (`end_page` последней сессии). Но сессий
 * может не быть вовсе: старые бэкапы их не выгружали, а книга помечена
 * прочитанной. Тогда прогресс известен из статуса: «прочитано» — значит
 * прочитано целиком, иначе книга выглядела бы как «прочитано 0 из 480».
 */
object ReadingProgress {

    /**
     * Прочитано страниц.
     *
     * @param book книга.
     * @param sessions сессии чтения этой книги.
     * @return сколько страниц прочитано.
     */
    fun pagesRead(book: BookEntity, sessions: List<ReadingSessionEntity>): Int {
        val bySessions = sessions.mapNotNull { it.endPage }.maxOrNull()
        if (bySessions != null) return bySessions
        // Номеров страниц в сессиях нет: у дочитанной книги прочитаны все
        // страницы, у остальных — неизвестно сколько.
        return if (book.status == BookStatus.FINISHED) book.pageCount ?: 0 else 0
    }
}
