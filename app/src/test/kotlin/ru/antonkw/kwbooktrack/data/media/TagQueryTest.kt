package ru.antonkw.kwbooktrack.data.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.db.entity.MediaTagName
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

/**
 * Поиск по тегам: совпадение частичное, без учёта регистра, все слова — И.
 *
 * Регистр складывается в Kotlin, а не в SQLite: на AOSP `LIKE` и `lower()`
 * складывают только ASCII, поэтому «сага» по «Сага» в SQL не нашлась бы.
 */
class TagQueryTest {

    private fun tag(id: Long, name: String, type: MediaType = MediaType.BOOKS) =
        MediaTagName(mediaId = id, mediaType = type, name = name)

    @Test
    fun cyrillicTagIsFoundRegardlessOfCase() {
        val tags = listOf(tag(1, "Сага"), tag(2, "Драма"))

        assertThat(tagMatchedOwners(tags, "сага", MediaType.BOOKS)).containsExactly(1L)
        assertThat(tagMatchedOwners(tags, "САГА", MediaType.BOOKS)).containsExactly(1L)
    }

    @Test
    fun partialMatchWorks() {
        val tags = listOf(tag(1, "перечитал"), tag(2, "детектив"))

        assertThat(tagMatchedOwners(tags, "перечит", MediaType.BOOKS)).containsExactly(1L)
    }

    @Test
    fun allWordsMustMatch() {
        val tags = listOf(tag(1, "сага"), tag(1, "фэнтези"), tag(2, "сага"))

        assertThat(tagMatchedOwners(tags, "сага фэнтези", MediaType.BOOKS))
            .containsExactly(1L)
    }

    @Test
    fun mediaTypeSeparatesBooksAndMoviesWithSameId() {
        val tags = listOf(tag(1, "сага", MediaType.BOOKS), tag(1, "нуар", MediaType.MOVIES))

        assertThat(tagMatchedOwners(tags, "сага", MediaType.MOVIES)).isEmpty()
        assertThat(tagMatchedOwners(tags, "нуар", MediaType.MOVIES)).containsExactly(1L)
    }

    @Test
    fun blankQueryMatchesNothing() {
        val tags = listOf(tag(1, "сага"))

        assertThat(tagMatchedOwners(tags, "   ", MediaType.BOOKS)).isEmpty()
    }
}