package ru.antonkw.kwbooktrack.data.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.db.entity.MediaDateEntity
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

/**
 * Даты владельца: основная плюс дополнительные.
 *
 * Основная дата — самая ранняя, и в списке дат она тоже первая. Отсюда
 * «первая дата» для Tellico и неизменность фильтра «Просмотрено».
 */
class MediaDatesTest {

    @Test
    fun additionalDatesAreSorted() {
        assertThat(mediaDates(null, listOf(2L, 1L))).containsExactly(1L, 2L).inOrder()
    }

    @Test
    fun primaryDateJoinsTheList() {
        assertThat(mediaDates(5L, listOf(1L))).containsExactly(1L, 5L).inOrder()
    }

    @Test
    fun noDatesGiveEmptyList() {
        assertThat(mediaDates(null, emptyList())).isEmpty()
    }

    @Test
    fun equalDatesAreKept() {
        // Два просмотра в один день — бывает, и это два просмотра.
        assertThat(mediaDates(null, listOf(3L, 3L))).containsExactly(3L, 3L)
    }

    @Test
    fun additionalDatesAreGroupedByOwner() {
        val extras = listOf(
            MediaDateEntity(mediaId = 1L, mediaType = MediaType.BOOKS, date = 10L),
            MediaDateEntity(mediaId = 1L, mediaType = MediaType.BOOKS, date = 20L),
            // id книг и фильмов считаются независимо и совпадают.
            MediaDateEntity(mediaId = 1L, mediaType = MediaType.MOVIES, date = 30L),
        )

        val grouped = extrasByOwner(extras)

        assertThat(grouped[1L to MediaType.BOOKS]).containsExactly(10L, 20L)
        assertThat(grouped[1L to MediaType.MOVIES]).containsExactly(30L)
        assertThat(grouped[2L to MediaType.BOOKS]).isNull()
    }
}
