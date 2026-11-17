package ru.antonkw.kwbooktrack.data.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import ru.antonkw.kwbooktrack.data.time.toDayDate
import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind
import ru.antonkw.kwbooktrack.data.tellico.TellicoFields
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord

class TellicoMovieMapperTest {

    private val mapper = TellicoMovieMapper()

    @Test
    fun mapsBasicFields() {
        val snapshot = mapper.map(
            record(
                "title" to "Малхолланд Драйв",
                "year" to "1999",
                "genre" to "Триллер; Драма",
                "nationality" to "США",
                "director" to "Линч, Дэвид",
                "running-time" to "147",
                "rating" to "5",
                "кинопоиск" to "https://www.kinopoisk.ru/film/466/",
                "скачан" to "true",
            ),
        )!!

        val movie = snapshot.movie
        assertThat(movie.title).isEqualTo("Малхолланд Драйв")
        assertThat(movie.year).isEqualTo(1999)
        assertThat(movie.genre).isEqualTo("Триллер; Драма")
        assertThat(movie.nationality).isEqualTo("США")
        assertThat(movie.director).isEqualTo("Линч, Дэвид")
        assertThat(movie.durationMin).isEqualTo(147)
        assertThat(movie.rating).isEqualTo(5)
        assertThat(movie.url).isEqualTo("https://www.kinopoisk.ru/film/466/")
        assertThat(movie.isDownloaded).isTrue()
        assertThat(movie.source).isEqualTo(MediaSource.TELLICO)
    }

    @Test
    fun mapsWatchedDate() {
        val snapshot = mapper.map(record("title" to "Фильм", "дата-просмотра" to "2024-02-22"))!!

        assertThat(snapshot.movie.watchedAt).isNotNull()
        assertThat(snapshot.movie.watchedAt!!.toDayDate().toString())
            .isEqualTo("2024-02-22")
    }

    @Test
    fun mapsYearOnlyWatchedDate() {
        // В коллекции фильмов Tellico есть записи с датой «только год»:
        // фильм-то посмотрен, просто день и месяц неизвестны. Дополняем
        // 1 января — как книжный parseDate.
        val snapshot = mapper.map(record("title" to "Фильм", "дата-просмотра" to "2024"))!!

        assertThat(snapshot.movie.watchedAt).isNotNull()
        assertThat(snapshot.movie.watchedAt!!.toDayDate().toString())
            .isEqualTo("2024-01-01")
    }

    @Test
    fun mapsCastKeepingCellBoundary() {
        val snapshot = mapper.map(
            record(
                "title" to "Фильм",
                "cast" to "Уоттс, Наоми::Роль\nЛинч, Дэвид::Сам",
            ),
        )!!

        assertThat(snapshot.movie.cast).isEqualTo("Уоттс, Наоми::Роль\nЛинч, Дэвид::Сам")
    }

    @Test
    fun mapsReviewToReviewNote() {
        val snapshot = mapper.map(record("title" to "Фильм", "рецензия" to "Отличный фильм."))!!

        val review = snapshot.notes.single()
        assertThat(review.kind).isEqualTo(NoteKind.REVIEW)
        assertThat(review.body).isEqualTo("Отличный фильм.")
    }

    @Test
    fun mapsTagsToTagsList() {
        val snapshot = mapper.map(record("title" to "Фильм", "теги" to "классика; ретро"))!!

        assertThat(snapshot.tags).containsExactly("классика", "ретро").inOrder()
    }

    @Test
    fun mapsUnknownFieldsToExtraFields() {
        val snapshot = mapper.map(
            TellicoRecord(
                fields = mapOf("title" to "Фильм"),
                extra = mapOf("widescreen" to ru.antonkw.kwbooktrack.data.snapshot.ExtraField("Широкий экран", "true")),
            ),
        )!!

        assertThat(snapshot.extraFields["widescreen"]?.value).isEqualTo("true")
    }

    @Test
    fun mapsKeywordToExtraFields() {
        val snapshot = mapper.map(record("title" to "Фильм", "keyword" to "нуар; сон"))!!

        assertThat(snapshot.extraFields["keyword"]?.value).isEqualTo("нуар; сон")
    }

    @Test
    fun returnsNullForEmptyTitle() {
        val snapshot = mapper.map(record("year" to "1999"))

        assertThat(snapshot).isNull()
    }

    @Test
    fun ignoresRatingOutOfRange() {
        val snapshot = mapper.map(record("title" to "Фильм", "rating" to "9"))!!

        assertThat(snapshot.movie.rating).isNull()
    }

    private fun record(vararg fields: Pair<String, String>): TellicoRecord =
        TellicoRecord(
            fields = fields.toMap(),
            collectionType = TellicoFields.COLLECTION_MOVIES,
        )
}
