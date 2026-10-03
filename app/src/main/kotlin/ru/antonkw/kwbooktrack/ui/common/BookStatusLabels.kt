package ru.antonkw.kwbooktrack.ui.common

import androidx.annotation.StringRes
import ru.antonkw.kwbooktrack.R
import ru.antonkw.kwbooktrack.data.db.entity.BookStatus

/**
 * Display label for a [BookStatus].
 *
 * Lives in the UI layer rather than in `data/db/entity/Enums.kt` so the data
 * layer never depends on `R`. `BookStatus.name` never reaches the UI as
 * "WISHLIST" / "FINISHED".
 *
 * Three of the four reuse the `filter_*` strings the filter dropdown already
 * renders — same concept, same wording, one string to translate. ABANDONED
 * has no `filter_*` counterpart because it isn't a filter, so it's a
 * `status_*` string; it doesn't get promoted to a filter just to borrow a name.
 */
@get:StringRes
val BookStatus.labelRes: Int
    get() = when (this) {
        BookStatus.WISHLIST -> R.string.filter_wishlist
        BookStatus.READING -> R.string.filter_reading
        BookStatus.FINISHED -> R.string.filter_finished
        BookStatus.ABANDONED -> R.string.status_abandoned
    }
