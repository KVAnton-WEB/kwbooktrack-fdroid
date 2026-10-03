package ru.antonkw.kwbooktrack.ui.common

import ru.antonkw.kwbooktrack.data.db.entity.BookEntity

/**
 * "Саги Кингера #7" / "Саги Кингера" / null.
 *
 * One definition for both the list card's meta line and the detail screen's
 * series line — including the blank check, so a series of `"   "` doesn't
 * render as `"   #3"` on one screen and vanish on the other.
 */
fun BookEntity.seriesLabel(): String? =
    series?.takeUnless { it.isBlank() }?.let { s ->
        seriesNumber?.takeUnless { it.isBlank() }?.let { n -> "$s #$n" } ?: s
    }
