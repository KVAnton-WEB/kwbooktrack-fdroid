package ru.antonkw.kwbooktrack.data.snapshot

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import ru.antonkw.kwbooktrack.data.db.entity.BookEntity
import ru.antonkw.kwbooktrack.data.db.entity.MovieEntity
import ru.antonkw.kwbooktrack.data.db.entity.NoteKind

/**
 * Снимок библиотеки — единая модель, из которой читаются оба формата
 * (родной Tellico `.tc` и zip-экспорт) и в которую пишется импорт.
 *
 * Снимок не привязан к id строк БД: [BookSnapshot.notes] и [BookSnapshot.photos]
 * связываются между собой через [PhotoSnapshot.noteIndex] — 1-based индекс
 * заметки в [BookSnapshot.notes]. Так снимок переживает пересоздание строк
 * при импорте.
 */
@Serializable
data class ExtraField(
    @SerialName("t") val title: String,
    @SerialName("v") val value: String,
)

data class NoteSnapshot(
    val kind: NoteKind,
    val body: String,
    val page: Int? = null,
    val createdAt: Long? = null,
)

data class SessionSnapshot(
    val startedAt: Long,
    val endedAt: Long? = null,
    val startPage: Int? = null,
    val endPage: Int? = null,
    val durationSec: Long? = null,
    val note: String? = null,
)

/**
 * Фото заметки.
 *
 * [archivePath] — путь внутри архива (`photos/{md5}.{ext}`), устойчивый между
 * экспортом и импортом: имя файла — MD5 от содержимого, поэтому повторный
 * импорт не плодит дубли. [localPath] — путь к файлу в приложении
 * (при импорте вычисляется апплиером).
 */
data class PhotoSnapshot(
    /** Путь в архиве (`photos/{md5}.{ext}`); заполняет [SnapshotBuilder.namePhotos]. */
    val archivePath: String = "",
    val localPath: String = archivePath,
    val noteIndex: Int? = null,
    val createdAt: Long? = null,
    /** id строки в БД — запасное имя, если файла на диске нет. */
    val photoId: Long = 0,
)

data class BookSnapshot(
    val book: BookEntity,
    /** Дополнительные даты прочтения — основная лежит в самой книге. */
    val dates: List<Long> = emptyList(),
    val notes: List<NoteSnapshot> = emptyList(),
    val sessions: List<SessionSnapshot> = emptyList(),
    val tags: List<String> = emptyList(),
    val photos: List<PhotoSnapshot> = emptyList(),
    val extraFields: Map<String, ExtraField> = emptyMap(),
)

/**
 * Снимок фильма — как [BookSnapshot], но без сессий чтения: трекера страниц
 * у фильма нет.
 *
 * Заметки, теги и фото — те же самые, что у книги: у фильма и книги общая
 * таблица заметок, и «владелец» различается парой (id, тип).
 */
data class MovieSnapshot(
    val movie: MovieEntity,
    /** Дополнительные даты просмотра — основная лежит в самом фильме. */
    val dates: List<Long> = emptyList(),
    val notes: List<NoteSnapshot> = emptyList(),
    val tags: List<String> = emptyList(),
    val photos: List<PhotoSnapshot> = emptyList(),
    val extraFields: Map<String, ExtraField> = emptyMap(),
)
