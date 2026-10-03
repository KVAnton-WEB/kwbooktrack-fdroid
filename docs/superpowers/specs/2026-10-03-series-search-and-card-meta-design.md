# Search by series + book-card meta line + series-number field height — design

Date: 2026-10-03
Project: kwbooktrack (Android, Kotlin, Compose, Room, Hilt)
Status: approved by user, implemented

## Goal

Four related fixes to how a book is identified at a glance:

1. The main-screen search box also matches series name and series number.
2. The list card shows the series (with number) in its meta line.
3. Year and publisher leave the card meta line; the status stops leaking the
   raw enum name (`WISHLIST`, `FINISHED`) into the UI.
4. The "Номер серии" input stops wrapping its label onto two lines, so the
   whole row is one height again.

## Non-goals (YAGNI)

- No series **filter dropdown**. Searching by series extends the existing text
  search; a second entry point is a second state to keep in sync.
- No `series_search` column, no Room migration 5→6. See "Decisions".
- No grid-card (`BookCoverCard`) changes. It has no meta line and no room next
  to cover + title + author + stars.
- No UI instrumentation tests for the card / field height — that needs
  `androidx.compose.ui:ui-test-junit4`, a new dependency. Manual check only.
- CSV export keeps `book.status.name`. It is a data interchange format with an
  importer on the other side; localizing it is a separate change.

## Decisions captured in brainstorming

| Question                                  | Answer                                                             |
| ----------------------------------------- | ------------------------------------------------------------------ |
| What goes in the card meta line           | **Series #number · Status (Russian)**. Year and publisher removed.  |
| What "search by series" means             | Extend the existing text search box. No new UI.                    |
| Series-number field label                 | `№` — one glyph, cannot wrap.                                      |

## Architecture

Four independent edits. No new state, no migration, no new dependency.

### 1. Search — `data/repository/BookRepository.kt`

Two `LIKE` positions added to the existing per-token condition:

```
(title_search LIKE ? OR author_search LIKE ?
 OR REPLACE(IFNULL(isbn13,''),'-','') LIKE ?
 OR LOWER(IFNULL(series,'')) LIKE ?
 OR LOWER(IFNULL(series_number,'')) LIKE ?)
```

and the bind count goes 3 → 5 per token.

**`LOWER()` is load-bearing.** SQLite `LIKE` is case-insensitive for ASCII
only; over Cyrillic it is case-sensitive. That is exactly why
`title_search` / `author_search` exist as separate pre-lowercased columns,
written by `withSearchKeys()`. `series` has no such column and stores whatever
the user typed ("Саги Кингера"), so the lowering has to happen at match time.

Rejected alternative: add a `series_search` column + migration + populate it in
`withSearchKeys()`. Faster on large tables, but pays a migration for a table of
a few hundred rows. Revisit if the library reaches 10k+ books.

Known consequence: searching `3` matches anything with a `3` in the series
number. Same behaviour the title search already has for digits.

### 2. Status labels — new `ui/common/BookStatusLabels.kt`

```kotlin
val BookStatus.labelRes: Int get() = when (this) {
    WISHLIST -> R.string.filter_wishlist   // К прочтению
    READING  -> R.string.filter_reading    // В процессе
    FINISHED -> R.string.filter_finished   // Прочитано
    ABANDONED -> R.string.filter_abandoned // Брошено  (new string)
}
```

Lives in the UI layer, not in `data/db/entity/Enums.kt`, so the data layer
does not depend on `R`. Reuses the `filter_*` strings that already exist for
the filter dropdown — one wording for one concept. `filter_abandoned` is the
only new string; `ABANDONED` had no Russian label anywhere in the app.

Consumers: `BookListScreen` card meta line, `BookDetailScreen` meta line.

### 3. Card meta line — `ui/books/BookListScreen.kt`

```kotlin
val meta = listOfNotNull(
    book.seriesLabel(),
    stringResource(book.status.labelRes),
).joinToString(" · ")
```

with

```kotlin
private fun BookEntity.seriesLabel(): String? =
    series?.takeUnless { it.isBlank() }?.let { s ->
        seriesNumber?.takeUnless { it.isBlank() }?.let { n -> "$s #$n" } ?: s
    }
```

`"$s #$n" ?: s` is the format `BookDetailScreen.kt:231` already uses, so the
app has one series format, not two. The meta line gets `maxLines = 1` +
`TextOverflow.Ellipsis` (it has neither today) so a long series cannot change
the card's height.

### 4. Series-number field — `ui/add/AddBookScreen.kt`

The label `«Номер серии»` wraps inside its `weight(0.5f)` slot and stretches
the whole `Row`. Replaced with `№`.

**New string, not an edit of the old one** — `field_series_number`
(«Номер серия») is still needed at `ui/common/BookInfoDialog.kt:126`. So
`field_series_number_short` = `№` is added alongside it.

Accepted debt: TalkBack announces "Серия", "Номер знака". The alternative label
`«Номер»` is more legible to a screen reader but reads less clearly as a
number next to "Серия". One-line switch if that trade goes the wrong way.

## Data flow

Search: `query → tokens (lowercased, punctuation-stripped) → AND per token
→ 5 LIKE positions each → BookDao.rawSearch`.

Card: `BookWithProgress.book.series/seriesNumber` → `seriesLabel()` →
`listOfNotNull(series, status) → " · "`.

## Error handling

Unchanged. `search()` still returns `observeAll()` for an empty token list,
still binds every value as an argument (no token text reaches the SQL string),
so the series addition cannot open an injection path.

`seriesLabel()` cannot throw: null and blank are both filtered before
interpolation.

## Testing

Extended `data/db/dao/BookSearchCaseInsensitivityTest` (Robolectric +
`TestDb.createInMemory`), which already exercises the real repository against
a real SQLite database:

- Cyrillic series, lowercased query finds a capitalized value — the
  `LOWER()` regression guard.
- The reverse case: uppercase query, lowercase stored value.
- Series number is searchable.
- A book with no series neither matches nor breaks the result set.
- The 7 existing tests still pass — this is what proves the AND tokenization
  survived the condition growing from 3 to 5 positions.

UI changes are verified by building and by hand on device, not by test.

## Review outcome (2026-10-03) — §1 was wrong, superseded

Code review after implementation overturned the "no `series_search` column"
decision above, and with it the `LOWER()` reasoning in §1.

`LOWER()` is not merely redundant on AOSP, it is **inert for Cyrillic**: that
SQLite build has no ICU, so `lower()` folds case for ASCII only. `Саги Кингера`
stays capitalized, the lowercased query token `сага` doesn't match, and the
search silently returns nothing. The unit tests passed because Robolectric's
native sqlite4java *is* built with ICU — the tests were validating behaviour
the app never exhibits on a device.

`series_search` now exists (migration 5→6, backfilled in Kotlin, populated by
`withSearchKeys()`), and the per-token condition is back to 3 LIKE positions on
a `series_search` key that holds `"series series_number"` lowercased. Same
mechanism as `title_search` / `author_search`, and the reason those two columns
were added in the first place.

Also from the review: `hasOverflow` in `ui/common/Scrollbar.kt` no longer comes
from the frozen mean estimate but from `canScrollForward || canScrollBackward`,
so the bar appears on `BookDetailScreen` when its single notes item grows past
the viewport; `seriesLabel()` moved to `ui/common/SeriesLabels.kt` as a public
extension used by both screens (§3's "one series format" was two copies); and
`ABANDONED`'s string is `status_abandoned`, not `filter_abandoned`, since
`BookFilterType` has no such filter.
