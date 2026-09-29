package ru.antonkw.kwbooktrack.data.db.entity

enum class BookStatus {
    WISHLIST,
    READING,
    FINISHED,
    ABANDONED,
}

enum class BookSource {
    MANUAL,
    TELLICO,
    BOOKMORY,
    CATALOG,
}

enum class NoteKind {
    NOTE,
    QUOTE,
    REVIEW,
}
