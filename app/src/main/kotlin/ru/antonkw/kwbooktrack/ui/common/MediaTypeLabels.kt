package ru.antonkw.kwbooktrack.ui.common

import androidx.annotation.StringRes
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.db.entity.MediaType

/**
 * Подпись типа носителя.
 *
 * Живёт в UI-слое, а не в `data/db/entity/Enums.kt`, — там слою данных не
 * полагается знать про `R`.
 */
@get:StringRes
val MediaType.mediaLabelRes: Int
    get() = when (this) {
        MediaType.BOOKS -> R.string.media_type_books
        MediaType.MOVIES -> R.string.media_type_movies
    }
