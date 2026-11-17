package ru.antonkw.kwbooktrack.data.snapshot

import kotlinx.serialization.Serializable

/**
 * Формат `extra.json` — части своего zip-бэкапа (`manifest.json` + `books.csv` +
 * `movies.csv` + `extra.json` + `covers/` + `photos/`).
 *
 * DTO — общие для экспортера и импортёра: формат объявлен в одном месте, иначе
 * копии в двух файлах разъедутся, а поймает это только тест.
 *
 * Значения по умолчанию нужны импортёру (старый или частичный `extra.json`
 * должен читаться), а экспортер пишет с `encodeDefaults = true` — тогда ключи
 * в JSON есть всегда, и «вид» записи не зависит от значений по умолчанию.
 */
@Serializable
data class ExtraArchive(
    val books: Map<String, BookExtras> = emptyMap(),
    /** Данные фильмов — по id фильма строкой. */
    val movies: Map<String, MovieExtras> = emptyMap(),
)

/** Данные книги из `extra.json`. */
@Serializable
data class BookExtras(
    /** Дополнительные даты прочтения (основная — в колонке `finished_at`). */
    val dates: List<Long> = emptyList(),
    val notes: List<NoteJson> = emptyList(),
    val sessions: List<SessionJson> = emptyList(),
    val tags: List<String> = emptyList(),
    val photos: List<PhotoJson> = emptyList(),
    val extraFields: Map<String, ExtraField> = emptyMap(),
)

/**
 * Данные фильма из `extra.json`.
 *
 * Как у книги, но без сессий чтения: трекера страниц у фильма нет.
 */
@Serializable
data class MovieExtras(
    /** Дополнительные даты просмотра (основная — в колонке `watched_at`). */
    val dates: List<Long> = emptyList(),
    val notes: List<NoteJson> = emptyList(),
    val tags: List<String> = emptyList(),
    val photos: List<PhotoJson> = emptyList(),
    val extraFields: Map<String, ExtraField> = emptyMap(),
)

/** Заметка книги: вид (`NOTE`/`QUOTE`/`REVIEW`), страница, текст и дата. */
@Serializable
data class NoteJson(
    val kind: String = "NOTE",
    val page: Int? = null,
    val body: String = "",
    val createdAt: Long? = null,
)

/** Сессия чтения. */
@Serializable
data class SessionJson(
    val startedAt: Long = 0L,
    val endedAt: Long? = null,
    val startPage: Int? = null,
    val endPage: Int? = null,
    val durationSec: Long? = null,
    val note: String? = null,
)

/** Фото заметки: путь файла внутри архива, индекс заметки и дата. */
@Serializable
data class PhotoJson(
    val file: String = "",
    val noteIndex: Int? = null,
    val createdAt: Long? = null,
)
