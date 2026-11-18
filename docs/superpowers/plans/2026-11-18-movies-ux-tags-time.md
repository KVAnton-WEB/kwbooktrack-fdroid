# План: фильмы — отметка просмотра, теги, поиск, время в статистике

**Goal:** Отмечать фильм просмотренным с карточки, редактировать теги на карточке
книги и фильма, искать по тегам, убрать «Поиск по» из подсказки, привести
длительность к «40ч 45мин», подписи графиков — к «4.45», разделить цвет
выбранных и пустых звёзд.

**Spec:** `docs/superpowers/specs/2026-11-18-movies-ux-tags-time-design.md`

**Без новых зависимостей и без миграций БД.**

## Task 1. Длительность без пробелов и формат «ч.мин»

- `res/values/strings.xml`, `values-en`: `duration_minutes` → `%1$dмин`,
  `duration_hours` → `%1$dч`, `duration_hours_minutes` → `%1$dч %2$dмин`.
- `ui/common/Duration.kt`: `formatDurationDot(minutes)` → `"4.45"` (часы.минуты,
  минуты двумя цифрами).
- `ui/common/DurationTest.kt` / новый тест: 0 → «0.00», 45 → «0.45», 285 → «4.45»,
  132 → «2.12».

## Task 2. Статистика: «Время» и подписи в ч.мин

- Строки: `stats_minutes_watched` = «Время: %1$s»,
  `stats_chart_minutes_by_month` = «Время по месяцам: %1$s»,
  `stats_chart_minutes_by_year` = «Время по годам».
- `StatisticsScreen.kt`: `ChartCard`/`BarChartWithLabels` получают
  `format: (Int) -> String = { it.toString() }`; график времени передаёт
  `::formatDurationDot`.

## Task 3. Теги: DAO, репозиторий

- `TagDao`: `observeTagNames(): Flow<List<String>>`, `observeTagsForAllMedia():
  Flow<List<MediaTagName>>`, `deleteCrossRefByName(mediaId, mediaType, name)`.
- `TagRepository`: `observeAllNames()`, `observeAll()`, `attach(mediaId,
  mediaType, name)`, `detach(mediaId, mediaType, name)`.
- Тесты `TagRepositoryTest`: attach/detach по имени, `observeAllNames`.

## Task 4. Поиск по тегам

- `data/media/TagQuery.kt`: `tagMatchedOwners(tags, query, mediaType)` — все
  слова запроса входят в набор тегов владельца; регистр складывается в Kotlin
  (SQLite не складывает кириллицу).
- `BookRepository.search(query, extraIds = emptySet())`,
  `MovieRepository.search(query, extraIds = emptySet())`: к условиям текста
  добавляется `OR id IN (...)`.
- `LibraryViewModel`: параметр `tagRepository`; `load()` комбинирует тип носителя
  и теги, id совпавших владельцев уходят в `search`.
- Тесты: `TagQueryTest` (кириллица, регистр, тип носителя), `LibraryTagSearchTest`
  (книга и фильм находятся по тегу, тег книги не подмешивается к фильмам).

## Task 5. Редактирование тегов на карточке

- `MediaTags.kt`: `MediaTagsSection(tags, suggestions, onAddTag, onRemoveTag)` —
  чипы `AssistChip` с крестиком, поле добавления с подсказками (как переплёт),
  добавление по IME Done или кнопке.
- `BookDetailViewModel`, `MovieDetailViewModel`: `allTags` в состоянии,
  `addTag(name)`, `removeTag(name)`.
- `BookDetailScreen` (секция тегов больше не скрывается при пустом списке),
  `MovieDetailScreen`.

## Task 6. Отметка просмотра на карточке фильма

- `data/media/MediaDates.kt`: `withMediaDate(primary, extra, date)` — без дублей,
  по возрастанию. Используется и в `BookDetailViewModel.markFinished`.
- `MovieDetailViewModel`: `database: KwBooktrackDatabase`, `addWatchDate(date)` —
  транзакция: обновить `watched_at` (минимум) и `media_dates` (остальные).
- `MovieDetailScreen`: кнопка «Отметить просмотр» / «Добавить просмотр» с
  календарём (сегодня по умолчанию).

## Task 7. Звёзды

- Пустые звёзды — `MaterialTheme.colorScheme.outline` (серый) в
  `MovieEditScreen.RatingPicker`, `AddBookScreen` (оценка), диалоге «Я прочитал»
  в `BookDetailScreen`.

## Task 8. Подсказка поиска

- `search_hint` = «Название, автор, ISBN, тег…»,
  `search_hint_movies` = «Название, режиссёр, актёр, тег…».

## Task 9. Версия и проверка

- `versionCode 109`, `versionName "0.9.2"`, changelog `109.txt` ru/en.
- `./gradlew :app:testDebugUnitTest`, `:app:assembleRelease`, установка на
  устройство, визуальная проверка.