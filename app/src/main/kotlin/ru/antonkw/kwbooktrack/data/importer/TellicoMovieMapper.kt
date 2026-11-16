package ru.antonkw.kwbooktrack.data.importer

import ru.antonkw.kwbooktrack.data.db.entity.MediaSource
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.snapshot.MovieSnapshot
import ru.antonkw.kwbooktrack.data.text.HtmlToMarkdown
import ru.antonkw.kwbooktrack.data.tellico.TellicoRecord
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Маппер Tellico: [TellicoRecord] → [MovieSnapshot].
 *
 * Ключи полей — **внутренние имена Tellico** (`title`, `running-time`), а не
 * русские заголовки: именно они лежат в `tellico.xml` и в `.tc`.
 *
 * Рецензия фильма — та же заметка REVIEW, что и у книги, и теги, цитаты,
 * заметки и фото — те же самые поля: мы объявляем их в коллекции фильмов
 * при экспорте, иначе Tellico молча выбросил бы значения.
 */
@Singleton
class TellicoMovieMapper @Inject constructor() {

    fun map(record: TellicoRecord): MovieSnapshot? {
        val title = TellicoCommon.f(record, "title").orEmpty()
        // Без названия фильм в списке не опознаётся — подставляем заглушку.
        if (title.isEmpty()) return null

        val now = System.currentTimeMillis()
        val movie = MovieEntity(
            title = title,
            originalTitle = TellicoCommon.f(record, "оригинальное-название"),
            year = TellicoCommon.f(record, "year")?.toIntOrNull(),
            genre = TellicoCommon.f(record, "genre"),
            nationality = TellicoCommon.f(record, "nationality"),
            director = TellicoCommon.f(record, "director"),
            cast = TellicoCommon.f(record, "cast"),
            durationMin = TellicoCommon.f(record, "running-time")?.toIntOrNull(),
            rating = TellicoCommon.f(record, "rating")?.toIntOrNull()?.takeIf { it in 1..5 },
            watchedAt = TellicoCommon.parseDate(TellicoCommon.f(record, "дата-просмотра")),
            coverPath = TellicoCommon.f(record, "cover"),
            plot = TellicoCommon.f(record, "plot")?.let { HtmlToMarkdown.convert(it) }?.takeUnless { it.isBlank() },
            comments = TellicoCommon.f(record, "comments")?.let { HtmlToMarkdown.convert(it) }?.takeUnless { it.isBlank() },
            url = TellicoCommon.f(record, "кинопоиск"),
            isDownloaded = TellicoCommon.isTrue(TellicoCommon.f(record, "скачан"), "скачан"),
            createdAt = TellicoCommon.parseDate(TellicoCommon.f(record, "cdate")) ?: now,
            updatedAt = TellicoCommon.parseDate(TellicoCommon.f(record, "mdate")) ?: now,
            source = MediaSource.TELLICO,
        )
        return MovieSnapshot(
            movie = movie,
            notes = TellicoCommon.quoteNotes(record) +
                TellicoCommon.reviewNote(record) +
                TellicoCommon.noteNotes(record),
            tags = TellicoCommon.tags(record),
            photos = TellicoCommon.photos(record),
            // Чужие поля Tellico плюс «Ключевые слова» — для них в приложении
            // нет колонки, и без extra_fields они бы просто потерялись.
            extraFields = record.extra + TellicoCommon.keywordExtra(record),
        )
    }
}
