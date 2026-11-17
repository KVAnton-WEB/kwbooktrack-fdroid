# Несколько дат: пересмотры фильмов и перечитывания книг — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to
> implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Фильм и книга хранят не одну дату, а список: пересмотры и перечитывания
теряются в календаре, статистике и выгрузке.

**Architecture:** Основная дата остаётся в `movies.watched_at` / `books.finished_at`.
Дополнительные даты — новая таблица `media_dates` (миграция 11→12). Инвариант:
**основная дата = самая ранняя**, поэтому Tellico-экспорт и фильтр «Просмотрено»
в списке продолжают работать без изменений. Списком дат владеет
`MediaDateRepository`; список в редакторе сортируется по дате.

**Tech Stack:** Kotlin, Room 2.6.1 (ручные миграции), Hilt, Compose (Material 3),
Robolectric + Truth для тестов.

**Spec:** `docs/superpowers/specs/2026-11-17-multi-dates-design.md`

## Global Constraints

- `minSdk = 31`, Kotlin, Room 2.6.1, Compose BOM 2024.09.02, Hilt — без новых зависимостей.
- Миграции ручные, `exportSchema = false`, `fallbackToDestructiveMigration` не используется.
- Дата — epoch-миллисекунды полночи в местной зоне (`data/time/Epoch.kt`: `LocalDate.toEpochMs()`).
- Основная дата = самая ранняя из всех дат владельца; она лежит в `movies.watched_at` / `books.finished_at`.
- Tellico-формат не меняется: одна дата, самая ранняя.
- Строки — в `values/strings.xml` и `values-en/strings.xml`, ключи одинаковые.
- В редакторе новое поле даты пустое — дату выбирает пользователь.
- Равные даты разрешены (два просмотра в один день).

## Review Focus

- Пересмотр в другом месяце должен попасть в календарь и в фильтр по периоду за этот месяц.
- Два просмотра в один день: две длительности в ячейке, одна плитка обложки.
- Импорт своего бэкапа должен восстановить все даты, а не одну.
- Удаление фильма или книги не должно оставлять даты-сироты.
- Повторный импорт не должен ломать инвариант «основная = самая ранняя».

---

### Task 1: Хранилище дат

**Files:**
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/entity/MediaDateEntity.kt`
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/dao/MediaDateDao.kt`
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/migration/Migrate11To12.kt`
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/repository/MediaDateRepository.kt`
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/media/MediaDates.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/KwBooktrackDatabase.kt` (version 12 + entity)
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/di/DatabaseModule.kt` (миграция + провайдер DAO)
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/dao/BookDao.kt`, `MovieDao.kt` (удаление строк дат)
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/db/migration/Migrate11To12Test.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/media/MediaDatesTest.kt`

**Interfaces:**
- Produces `data.db.entity.MediaDateEntity(id: Long = 0, mediaId: Long, mediaType: MediaType, date: Long)`
- Produces `data.repository.MediaDateRepository.observeAll(): Flow<List<MediaDateEntity>>`,
  `observeFor(mediaId: Long, mediaType: MediaType): Flow<List<MediaDateEntity>>`,
  `getFor(mediaId: Long, mediaType: MediaType): List<Long>`,
  `replaceFor(mediaId: Long, mediaType: MediaType, dates: List<Long>)`,
  `deleteFor(mediaId: Long, mediaType: MediaType)`
- Produces `data.media.mediaDates(primary: Long?, extra: List<Long>): List<Long>` —
  все даты по возрастанию; `data.media.extrasByOwner(extras: List<MediaDateEntity>): Map<Pair<Long, MediaType>, List<Long>>`

- [ ] **Step 1: Тест миграции.** `Migrate11To12Test`: база v11 создаётся руками (как в
  `MigrationsSchemaTest`), миграция применяется, Room открывает базу и проверяет
  схему; таблица `media_dates` есть, данные `books`/`movies` на месте.
- [ ] **Step 2: Запустить — должно упасть** (`./gradlew :app:testDebugUnitTest --tests "*Migrate11To12Test"`).
- [ ] **Step 3: Реализовать.** `MediaDateEntity` (таблица `media_dates`, индексы по
  `media_id` и `media_type`, БЕЗ внешнего ключа — владельцем может быть и книга,
  и фильм). `Migrate11To12` создаёт таблицу:
  `CREATE TABLE IF NOT EXISTS media_dates (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, media_id INTEGER NOT NULL, media_type TEXT NOT NULL, date INTEGER NOT NULL)`
  + индексы `index_media_dates_media_id`, `index_media_dates_media_type`.
  Версия БД → 12, миграция зарегистрирована в `DatabaseModule`.
- [ ] **Step 4: DAO и репозиторий.** `MediaDateDao`: `insert`, `deleteById`,
  `deleteFor(mediaId, mediaType)`, `getFor(mediaId, mediaType)`, `getAll`,
  `observeAll`, `observeFor`. `MediaDateRepository.replaceFor` = удалить всё для
  владельца + вставить новый список (так сохраняет редактор: список целиком).
- [ ] **Step 5: Тест `MediaDatesTest`.** `mediaDates(null, listOf(2L, 1L))` → `[1L, 2L]`;
  `mediaDates(5L, listOf(1L))` → `[1L, 5L]`; `mediaDates(null, emptyList())` → `[]`.
- [ ] **Step 6: Удаление сирот.** `BookDao.deleteWithContent` и `MovieDao.deleteWithContent`
  дополнительно удаляют строки `media_dates` владельца (в той же `@Transaction`).
- [ ] **Step 7: Прогнать тесты** — `./gradlew :app:testDebugUnitTest`.
- [ ] **Step 8: Коммит** — `git add` перечисленные файлы, `git commit -m "feat: хранение дополнительных дат просмотра и прочтения"`.

### Task 2: Редактор фильма — список дат

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/movies/MovieEditViewModel.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/movies/MovieEditScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `values-en/strings.xml`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/ui/movies/MovieEditViewModelTest.kt`

**Interfaces:**
- Consumes `MediaDateRepository` (Task 1), `MediaType.MOVIES`
- Produces `MovieEditState.watchedDates: List<String>` (вместо `watchedDate: String`)

- [ ] **Step 1: Тест.** Редактор фильма с двумя датами: `state.watchedDates` содержит
  обе (по возрастанию); после `save()` в БД у фильма `watched_at` = самая ранняя,
  а в `media_dates` — вторая.
- [ ] **Step 2: Запустить — должно упасть.**
- [ ] **Step 3: ViewModel.** Состояние: `watchedDates: List<String>`. Загрузка: основная
  дата фильма + дополнительные, отсортированные. Методы: `setWatchedDate(index, value)`,
  `addWatchedDate()`, `removeWatchedDate(index)`. Сохранение: непустые даты по
  возрастанию → первая в `watched_at`, остальные → `media_dates`.
- [ ] **Step 4: Экран.** Список `DatePickerField` (значение — строка `dd.MM.yyyy`) +
  `TextButton` «Добавить просмотр». Строки: `movie_add_watching`, `action_add_watching`.
- [ ] **Step 5: Прогнать тесты.**
- [ ] **Step 6: Коммит.**

### Task 3: Редактор книги — список дат

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/add/AddBookViewModel.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/add/AddBookScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`, `values-en/strings.xml`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/ui/add/AddBookViewModelTest.kt`

**Interfaces:**
- Consumes `MediaDateRepository` (Task 1), `MediaType.BOOKS`
- Produces `AddBookState.finishedDates: List<String>` (вместо `finishedDate: String`)

- [ ] **Step 1: Тест.** Книга с двумя датами «прочитано»: после `save()` `finished_at` =
  самая ранняя, в `media_dates` — вторая. Снятый чекбокс «Прочитано» даты не сохраняет.
- [ ] **Step 2: Запустить — должно упасть.**
- [ ] **Step 3: ViewModel.** Состояние `finishedDates: List<String>`; загрузка —
  `finished_at` + дополнительные; сохранение — первая дата в `finished_at`, остальные
  в `media_dates`. Чекбокс `isRead` без изменений: снят → даты не пишутся.
- [ ] **Step 4: Экран.** Список полей даты под чекбоксом + кнопка «Добавить прочтение».
- [ ] **Step 5: Прогнать тесты.**
- [ ] **Step 6: Коммит.**

### Task 4: Календарь и статистика — все даты

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/calendar/CalendarViewModel.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/stats/MovieStatsCalculator.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/stats/StatsCalculator.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/stats/StatisticsViewModel.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/stats/MovieStatsCalculatorTest.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/ui/calendar/CalendarViewModelTest.kt`

**Interfaces:**
- Consumes `MediaDateRepository`, `data.media.mediaDates`, `data.media.extrasByOwner`
- Produces `MovieStatsCalculator.compute(movies: List<MovieEntity>, datesByMovie: Map<Long, List<Long>>): MovieStats`
- Produces `StatsCalculator.compute(sessions, finishedBookDates: List<Long>, purchasedBooks)` —
  сигнатура прежняя, на вход подаются все даты «прочитано»

- [ ] **Step 1: Тесты.** Фильм с двумя датами: в статистике `totalMovies` = 2,
  `totalMinutes` = 2 × длительность; в календаре фильм стоит в ячейках обеих дат,
  минуты в ячейке = длительность.
- [ ] **Step 2: Запустить — должно упасть.**
- [ ] **Step 3: Статистика фильмов.** Просмотр = одна дата: `totalMovies` — число дат,
  `totalMinutes` — сумма длительностей по всем датам, месяцы/годы — по каждой дате.
- [ ] **Step 4: Статистика книг.** В `finishedBookDates` — все даты «прочитано»
  (основная + дополнительные) вместо одной.
- [ ] **Step 5: Календарь.** Фильмы: дата просмотра = основная + дополнительные;
  в ячейке — одна плитка на фильм (distinct), минуты — сумма по просмотрам дня.
  Книги: даты «прочитано» + даты сессий журнала (как сейчас).
- [ ] **Step 6: Прогнать тесты.**
- [ ] **Step 7: Коммит.**

### Task 5: Карточки — список дат

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/movies/MovieDetailViewModel.kt`,
  `MovieDetailScreen.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/books/BookDetailViewModel.kt`,
  `BookDetailScreen.kt`, `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/common/BookInfoDialog.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/ui/books/BookDetailViewModelTest.kt`

**Interfaces:**
- Consumes `MediaDateRepository`
- Produces в состоянии карточек: `dates: List<Long>` — все даты по возрастанию

- [ ] **Step 1: Тест.** Карточка фильма с двумя датами: в состоянии обе.
  «Отметить прочитанным» у книги с январской датой добавляет вторую, январская
  остаётся основной.
- [ ] **Step 2: Запустить — должно упасть.**
- [ ] **Step 3: ViewModel'ы.** В состояние добавить `dates`; «отметить прочитанным»
  (`BookDetailViewModel.markFinished`) добавляет дату «сегодня», а не заменяет.
- [ ] **Step 4: Экраны.** Вместо одной даты — список (по одной на строку).
- [ ] **Step 5: Прогнать тесты.**
- [ ] **Step 6: Коммит.**

### Task 6: Фильтр по периоду в списке

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/library/LibraryViewModel.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/ui/library/LibraryViewModelTest.kt`

**Interfaces:**
- Consumes `MediaDateRepository`, `data.media.extrasByOwner`
- Produces: фильтр по периоду и «Просмотрено» считают **любую** дату владельца

- [ ] **Step 1: Тест.** Фильм с датами в марте и мае: при периоде «май» он попадает
  в список; фильтр «Не просмотрено» его не берёт.
- [ ] **Step 2: Запустить — должно упасть.**
- [ ] **Step 3: ViewModel.** Подтянуть даты владельцев и фильтровать по ним
  (у фильмов — по всем датам просмотра, у книг — по датам «прочитано»,
  началу чтения и покупке).
- [ ] **Step 4: Прогнать тесты.**
- [ ] **Step 5: Коммит.**

### Task 7: Выгрузка и импорт

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/snapshot/KwBooktrackArchive.kt`
  (`BookExtras.dates: List<Long>`, `MovieExtras.dates: List<Long>`)
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/snapshot/SnapshotBuilder.kt`,
  `SnapshotApplier.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/exporter/ExportZipUseCase.kt`,
  `data/importer/ImportKwBooktrackZipUseCase.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/exporter/KwBooktrackZipRoundTripTest.kt`

**Interfaces:**
- Consumes `MediaDateRepository`, `data.media.mediaDates`
- Produces: в `extra.json` — список дополнительных дат; в CSV — основная дата

- [ ] **Step 1: Тест.** Круговой импорт: фильм с двумя датами → архив → импорт в пустую
  базу → обе даты на месте, основная = самая ранняя. Старый архив без ключа `dates`
  импортируется без ошибок.
- [ ] **Step 2: Запустить — должно упасть.**
- [ ] **Step 3: Формат.** В `BookExtras`/`MovieExtras` — `dates: List<Long>` (epoch-мс).
  Экспорт: в CSV — основная дата, в `extra.json` — дополнительные.
  Импорт: основная дата из CSV, дополнительные — из `extra.json`; объединение с
  датами в базе, основная пересчитывается как самая ранняя.
- [ ] **Step 4: Прогнать тесты** (включая тест на старый архив без `dates`).
- [ ] **Step 5: Коммит.**
