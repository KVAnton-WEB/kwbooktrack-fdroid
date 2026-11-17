package ru.antonkw.kwbooktrack.data.db.entity

enum class BookStatus {
    WISHLIST,
    READING,
    FINISHED,
    ABANDONED,
}

enum class MediaSource {
    MANUAL,
    TELLICO,
    /**
     * Импорт из Bookmory удалён, но значение осталось в базах пользователей:
     * без него старые строки читались бы как MANUAL.
     */
    BOOKMORY,
    CATALOG,
}

enum class NoteKind {
    NOTE,
    QUOTE,
    REVIEW,
}

/**
 * Тип носителя: книга или фильм.
 *
 * Владелец заметки, цитаты, тега и фото — не обязательно книга: фильмы
 * умеют то же самое. Поэтому «владелец» — это пара (`media_id`,
 * `media_type`), а не один id.
 */
enum class MediaType {
    BOOKS,
    MOVIES,
}
