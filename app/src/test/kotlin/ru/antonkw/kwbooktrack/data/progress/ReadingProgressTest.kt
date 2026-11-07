package ru.antonkw.kwbooktrack.data.progress

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus
import ru.antonkw.kwbooktrack.data.db.entity.ReadingSessionEntity

class ReadingProgressTest {

    @Test
    fun finishedBookWithoutSessionsIsReadToTheEnd() {
        // Старый бэкап не выгружал сессии: дочитанная книга без них —
        // прочитанная целиком, а не «0 из 480».
        val book = book(status = BookStatus.FINISHED, pageCount = 480)

        assertThat(ReadingProgress.pagesRead(book, emptyList())).isEqualTo(480)
    }

    @Test
    fun sessionPageWinsOverStatus() {
        // Явная страница из сессии — точнее, чем статус.
        val book = book(status = BookStatus.FINISHED, pageCount = 480)
        val sessions = listOf(ReadingSessionEntity(bookId = 1, startedAt = 1L, endPage = 300))

        assertThat(ReadingProgress.pagesRead(book, sessions)).isEqualTo(300)
    }

    @Test
    fun unreadBookWithoutSessionsHasNoProgress() {
        val book = book(status = BookStatus.WISHLIST, pageCount = 480)

        assertThat(ReadingProgress.pagesRead(book, emptyList())).isEqualTo(0)
    }

    @Test
    fun finishedBookWithoutPageCountHasNoProgress() {
        val book = book(status = BookStatus.FINISHED, pageCount = null)

        assertThat(ReadingProgress.pagesRead(book, emptyList())).isEqualTo(0)
    }

    private fun book(status: BookStatus, pageCount: Int?) = BookEntity(
        title = "Книга",
        author = "Автор",
        pageCount = pageCount,
        status = status,
        createdAt = 0L,
        updatedAt = 0L,
    )
}
