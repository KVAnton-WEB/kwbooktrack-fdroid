# Поддержка фильмов в kwbooktrack — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Добавить в kwbooktrack домен «фильм» по аналогии с книгами: список с переключателем «Книги ⇄ Фильмы», карточка фильма с заметками/цитатами/тегами/фото, редактирование, календарь просмотров, статистика времени просмотра, импорт `movi.tc` и экспорт в Tellico `.tc` и свой zip-бэкап.

**Architecture:** Книги остаются в таблице `books` (не трогаем). Фильмы — новая таблица `movies`. Заметки, цитаты, теги и фото — **общие** для книг и фильмов: у таблиц `notes`/`photos`/`media_tags` вместо FK на `books` появляется пара `media_id` + `media_type`. Переключатель типа носителя — `@Singleton MediaTypeStore` с `StateFlow<MediaType>`. Обложки — с именем-UUID у книг и фильмов; старые файлы `{bookId}.{ext}` продолжают работать, потому что в БД лежит абсолютный путь.

**Tech Stack:** Kotlin 1.9, Jetpack Compose (Material3), Hilt, Room 2.x (KSP), kotlinx.serialization, Coil, Robolectric + JUnit4 + Truth + coroutines-test. minSdk 31, JVM 17.

**Spec:** `docs/superpowers/specs/2026-11-16-movies-support-design.md` (ревизия 2)

## Global Constraints

- Версия БД: 9 → **11** двумя миграциями (`Migrate9To10`, `Migrate10To11`). `exportSchema = false`, `autoMigrations = []`, `fallbackToDestructiveMigration` **не использовать**.
- Каждая миграция — отдельный файл `data/db/migration/MigrateXToY.kt`, формат `val MigrateXToY: Migration = object : Migration(X, Y) { override fun migrate(db: SupportSQLiteDatabase) { … } }`, регистрация в `data/di/DatabaseModule.kt` в `.addMigrations(...)`.
- Комментарии, KDoc и тексты в коде — **по-русски**; идентификаторы, имена файлов и строки ресурсов — как есть.
- Новые строки — в **обоих** `res/values/strings.xml` и `res/values-en/strings.xml`. Порядок ключей в обоих файлах совпадает.
- `BookSource` переименовывается в `MediaSource` (Task 1). Значения в БД не меняются (в БД хранится `enum.name`).
- Каждый коммит обязан бампать `versionName` **и** `versionCode` в `app/build.gradle.kts` (хук `hooks/`). В этой задаче: `versionName 0.6.5 → 0.7.0`, `versionCode 70 → 71` — бамп делается один раз в Task 1, дальше `versionCode` +1 на каждый коммит, `versionName` остаётся `0.7.0`.
- Тесты: JUnit4 + Robolectric (`@RunWith(RobolectricTestRunner::class)`) + Truth (`com.google.common.truth.Truth.assertThat`) + `kotlinx.coroutines.test`.
- Команда сборки: `./gradlew testDebugUnitTest` (точечно — `./gradlew testDebugUnitTest --tests "пакет.Класс"`). **Никогда** `--production`.
- Покрытие заметок/фото/тегов у фильмов — через те же таблицы; новые таблицы `movie_notes`/`movie_photos` **не создаются**.

## Review Focus

Каждая строка — класс ввода или отказа, который спека подразумевает, но чей тест не написан явно. Тест для каждой строки добавляется в задачу, владеющую кодом.

1. **Повторный импорт `movi.tc`** — фильмы не должны плодиться: ключ слияния «название + год».
2. **Удаление фильма** — его заметки, теги, фото (и файлы фото) не должны остаться сиротами; внешнего ключа на `books`/`movies` у них больше нет.
3. **Старый zip-бэкап** (без `movies.csv` и без секции `movies` в `extra.json`) должен импортироваться без ошибки, а не падать `MissingFieldException`.
4. **Старые обложки** вида `covers/1.webp` должны показываться и экспортироваться — в БД абсолютный путь, имя файла не важно.
5. **`.tc` с фильмами** должен открываться в Tellico: значения не должны теряться (все поля объявлены в `<fields>`, заметки фильма переживают round-trip).
6. **Фильм с датой просмотра «только год»** (2 записи в `movi.tc`) должен считаться просмотренным и попасть в календарь на 1 января.
7. **`cast` с двумя колонками** («Актёр» + «Роль») не должен слипаться: граница ячеек — `::`.
8. **Удаление заметки** — фото этой заметки теряет привязку (`note_id` → NULL), но **файл** фото остаётся.

---

## File Structure

**Новые файлы:**

| Файл | Ответственность |
|---|---|
| `data/db/entity/MovieEntity.kt` | Таблица `movies` — 22 колонки фильма |
| `data/db/dao/MovieDao.kt` | CRUD + поиск + `upsertFromImport` по названию+году |
| `data/repository/MovieRepository.kt` | Обёртка над `MovieDao`: поисковые ключи, слияние, чистка при удалении |
| `data/media/MediaTypeStore.kt` | `@Singleton` с `StateFlow<MediaType>` — текущий тип носителя |
| `data/stats/MovieStatsCalculator.kt` | Агрегаты по фильмам: количество и минуты по месяцам/годам |
| `data/importer/TellicoMovieMapper.kt` | Tellico-запись → `MovieSnapshot` |
| `data/db/migration/Migrate9To10.kt` | Пересоздание `notes`/`photos`/`book_tags` в media-скоуп |
| `data/db/migration/Migrate10To11.kt` | Создание таблицы `movies` + индексы |
| `ui/common/FilterDropdown.kt` | Общий `FilterDropdown` + `periodText` (были `private` в `BookListScreen.kt`) |
| `ui/common/Period.kt` | `PeriodOption`, `PeriodOptions`, `FilterState` (были в `BookListViewModel.kt`) |
| `ui/library/LibraryScreen.kt` | Список библиотеки: книги ⇄ фильмы, дропдаун в заголовке |
| `ui/library/LibraryViewModel.kt` | Состояние списка: запрос, период, фильтр, тип носителя, раскладка |
| `ui/movies/MovieDetailScreen.kt` | Карточка фильма: поля, заметки, цитаты, теги, фото |
| `ui/movies/MovieDetailViewModel.kt` | Состояние карточки фильма |
| `ui/movies/MovieEditScreen.kt` | Создание/редактирование фильма |
| `ui/movies/MovieEditViewModel.kt` | Состояние редактора фильма |

**Изменяемые файлы (основные):**

| Файл | Что меняется |
|---|---|
| `data/db/entity/Enums.kt` | + `MediaType` |
| `data/db/entity/BookEntity.kt` | `source: BookSource` → `MediaSource` |
| `data/db/entity/NoteEntity.kt` | `bookId` → `mediaId` + `mediaType`, FK на `books` убран |
| `data/db/entity/PhotoEntity.kt` | то же; FK на `notes` сохранён |
| `data/db/entity/TagEntity.kt` | `BookTagCrossRef` → `MediaTagCrossRef`, таблица `media_tags`; `BookTagName` → `MediaTagName` |
| `data/db/dao/NoteDao.kt`, `PhotoDao.kt`, `TagDao.kt` | параметры `mediaId` + `mediaType` |
| `data/db/converter/Converters.kt` | + конвертеры `MediaType`, `BookSource` → `MediaSource` |
| `data/db/KwBooktrackDatabase.kt` | version 11, + `MovieEntity`, `MediaTagCrossRef`, `movieDao()` |
| `data/di/DatabaseModule.kt` | регистрация миграций + `provideMovieDao` |
| `data/repository/BookRepository.kt` | ручная чистка заметок/фото/тегов при удалении; `coverStorage.delete(path)` |
| `data/repository/NoteRepository.kt`, `PhotoRepository.kt`, `TagRepository.kt` | `*ByBook` → `*ByMedia` |
| `data/storage/Storages.kt` | `CoverStorage.promoteToBook(bookId, …)` → `promote(…)`; `deleteForBook(bookId)` → `delete(path)` |
| `data/snapshot/LibrarySnapshot.kt` | + `MovieSnapshot` |
| `data/snapshot/SnapshotBuilder.kt` | + `buildMovies()` |
| `data/snapshot/SnapshotApplier.kt` | `applyBook` / `applyMovie`, общая часть вынесена в приватный метод |
| `data/snapshot/KwBooktrackArchive.kt` | + `ExtraArchive.movies` |
| `data/tellico/TellicoRecord.kt` | + `collectionType: Int = 2` |
| `data/tellico/TellicoFields.kt` | + `MOVIE_TITLES`, `MOVIE_KNOWN` |
| `data/tellico/TellicoXmlReader.kt` | читает все коллекции; правка `cast` |
| `data/tellico/TellicoXmlWriter.kt` | коллекция фильмов + свои поля фильма |
| `data/importer/ImportTellicoUseCase.kt` | развод по `collectionType` |
| `data/exporter/ExportTellicoTcUseCase.kt` | экспорт коллекции текущего типа |
| `data/exporter/ExportZipUseCase.kt` | `movies.csv`, секция `movies`, `ExportCounts.movies = 0` |
| `data/importer/ImportKwBooktrackZipUseCase.kt` | чтение `movies.csv` и секции `movies` |
| `ui/books/BookListScreen.kt`, `BookListViewModel.kt` | → `ui/library/LibraryScreen.kt`, `LibraryViewModel.kt` |
| `ui/books/BookDetailScreen.kt`, `BookDetailViewModel.kt` | `mediaId` + `MediaType.BOOKS` |
| `ui/notes/*` | `mediaId` + `mediaType`; маршрут редактора |
| `ui/calendar/*`, `ui/stats/*` | ветвление по типу носителя |
| `ui/about/AboutScreen.kt`, `AboutExportViewModel.kt` | счётчик фильмов, выбор коллекции для `.tc` |
| `ui/navigation/KwBooktrackNavHost.kt` | новые маршруты фильмов |
| `res/values/strings.xml`, `res/values-en/strings.xml` | новые строки |

---

## Фаза 1 — Данные

### Task 1: `MediaType`, `MediaSource`, media-скоуп заметок/тегов/фото + миграция v9→v10

**Files:**
- Create: `data/db/migration/Migrate9To10.kt`
- Modify: `data/db/entity/Enums.kt`, `data/db/entity/BookEntity.kt`, `data/db/entity/NoteEntity.kt`, `data/db/entity/PhotoEntity.kt`, `data/db/entity/TagEntity.kt`
- Modify: `data/db/converter/Converters.kt`, `data/db/KwBooktrackDatabase.kt`, `data/di/DatabaseModule.kt`
- Modify: `data/db/dao/NoteDao.kt`, `data/db/dao/PhotoDao.kt`, `data/db/dao/TagDao.kt`
- Modify: `data/repository/NoteRepository.kt`, `data/repository/PhotoRepository.kt`, `data/repository/TagRepository.kt`, `data/repository/BookRepository.kt`
- Modify: `data/snapshot/SnapshotBuilder.kt`, `data/snapshot/SnapshotApplier.kt`
- Modify: `ui/books/BookDetailViewModel.kt`, `ui/notes/NotesListViewModel.kt`, `ui/notes/NoteEditorViewModel.kt`
- Modify: `ui/add/AddBookViewModel.kt` (переименование `BookSource`)
- Test: `data/db/migration/Migrate9To10Test.kt` (новый), `data/db/dao/NoteDaoTest.kt`, `data/db/dao/PhotoDaoTest.kt`, `data/db/dao/TagDaoTest.kt`, `data/snapshot/SnapshotBuilderTest.kt`, `data/snapshot/SnapshotApplierTest.kt`, `data/repository/TagRepositoryTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  // data/db/entity/Enums.kt
  enum class MediaType { BOOKS, MOVIES }
  // BookSource → MediaSource (те же значения: MANUAL, TELLICO, BOOKMORY, CATALOG)

  // data/db/entity/NoteEntity.kt
  @Entity(tableName = "notes", foreignKeys = [], indices = [
      Index(value = ["media_id"]), Index(value = ["created_at"]), Index(value = ["kind"]),
  ])
  data class NoteEntity(
      @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
      @ColumnInfo(name = "media_id") val mediaId: Long,
      @ColumnInfo(name = "media_type") val mediaType: MediaType = MediaType.BOOKS,
      @ColumnInfo(name = "page") val page: Int? = null,
      @ColumnInfo(name = "body_md") val bodyMd: String,
      @ColumnInfo(name = "kind") val kind: NoteKind = NoteKind.NOTE,
      @ColumnInfo(name = "created_at") val createdAt: Long,
      @ColumnInfo(name = "updated_at") val updatedAt: Long,
  )

  // data/db/entity/PhotoEntity.kt
  @Entity(tableName = "photos", foreignKeys = [ForeignKey(entity = NoteEntity::class,
      parentColumns = ["id"], childColumns = ["note_id"], onDelete = ForeignKey.SET_NULL)],
      indices = [Index(value = ["media_id"]), Index(value = ["note_id"])])
  data class PhotoEntity(
      @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
      @ColumnInfo(name = "media_id") val mediaId: Long,
      @ColumnInfo(name = "media_type") val mediaType: MediaType = MediaType.BOOKS,
      @ColumnInfo(name = "note_id") val noteId: Long? = null,
      @ColumnInfo(name = "path") val path: String,
      @ColumnInfo(name = "created_at") val createdAt: Long,
  )

  // data/db/entity/TagEntity.kt
  @Entity(tableName = "media_tags", primaryKeys = ["media_id", "media_type", "tag_id"],
      foreignKeys = [ForeignKey(entity = TagEntity::class, parentColumns = ["id"],
          childColumns = ["tag_id"], onDelete = ForeignKey.CASCADE)],
      indices = [Index(value = ["media_id"]), Index(value = ["tag_id"])])
  data class MediaTagCrossRef(
      @ColumnInfo(name = "media_id") val mediaId: Long,
      @ColumnInfo(name = "media_type") val mediaType: MediaType,
      @ColumnInfo(name = "tag_id") val tagId: Long,
  )
  data class MediaTagName(
      @ColumnInfo(name = "media_id") val mediaId: Long,
      @ColumnInfo(name = "media_type") val mediaType: MediaType,
      @ColumnInfo(name = "name") val name: String,
  )

  // NoteDao
  @Query("SELECT * FROM notes WHERE media_id = :mediaId AND media_type = :mediaType ORDER BY created_at DESC")
  fun observeByMedia(mediaId: Long, mediaType: MediaType): Flow<List<NoteEntity>>
  @Query("DELETE FROM notes WHERE media_id = :mediaId AND media_type = :mediaType")
  suspend fun deleteForMedia(mediaId: Long, mediaType: MediaType): Int

  // PhotoDao
  @Query("SELECT * FROM photos WHERE media_id = :mediaId AND media_type = :mediaType ORDER BY created_at DESC")
  fun observeByMedia(mediaId: Long, mediaType: MediaType): Flow<List<PhotoEntity>>
  @Query("DELETE FROM photos WHERE media_id = :mediaId AND media_type = :mediaType")
  suspend fun deleteForMedia(mediaId: Long, mediaType: MediaType): Int
  @Query("SELECT path FROM photos WHERE media_id = :mediaId AND media_type = :mediaType")
  suspend fun pathsForMedia(mediaId: Long, mediaType: MediaType): List<String>

  // TagDao
  @Query("SELECT mt.media_id AS media_id, mt.media_type AS media_type, t.name AS name FROM tags t " +
      "JOIN media_tags mt ON mt.tag_id = t.id ORDER BY t.name")
  suspend fun tagsForAllMedia(): List<MediaTagName>
  @Query("DELETE FROM media_tags WHERE media_id = :mediaId AND media_type = :mediaType")
  suspend fun clearCrossRefsForMedia(mediaId: Long, mediaType: MediaType): Int

  // NoteRepository / PhotoRepository / TagRepository — переименование с сохранением сигнатур:
  fun observeByMedia(mediaId: Long, mediaType: MediaType): Flow<List<…>>
  suspend fun deleteForMedia(mediaId: Long, mediaType: MediaType): Int
  suspend fun deleteFilesForMedia(mediaId: Long, mediaType: MediaType)   // PhotoRepository
  suspend fun attachToMedia(mediaId: Long, mediaType: MediaType, tagId: Long)   // TagRepository
  suspend fun clearForMedia(mediaId: Long, mediaType: MediaType): Int            // TagRepository
  ```
- Consumes: ничего (первая задача).

- [ ] **Step 1: Написать падающий тест миграции**

Создать `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/db/migration/Migrate9To10Test.kt` по образцу `Migrate8To9Test.kt`: `FrameworkSQLiteOpenHelperFactory`, `SupportSQLiteOpenHelper.Callback(9)`, `PRAGMA foreign_keys = ON` в `onConfigure`, схема v9 создаётся сырым SQL (нужны таблицы `books`, `notes`, `photos`, `tags`, `book_tags` — схему взять из `CREATE_BOOKS` в `Migrate7To8.kt` и дописать остальные).

Тесты:
- `migrationRewritesNotesColumns` — `PRAGMA table_info(notes)` содержит `media_id` и `media_type`, не содержит `book_id`.
- `migrationRewritesPhotosColumns` — то же для `photos`.
- `migrationRenamesBookTagsToMediaTags` — таблица `media_tags` существует, `book_tags` нет; `PRAGMA table_info(media_tags)` = `media_id`, `media_type`, `tag_id`.
- `migrationKeepsExistingRows` — книга, 2 заметки, 1 фото, 1 тег, 1 связка остаются на месте; у заметок `media_id` = прежний `book_id`, `media_type` = `BOOKS`; у фото `note_id` не обнулился.
- `migrationKeepsPhotoNoteLink` — `photos.note_id` указывает на существующую заметку (регрессия на `SET NULL` при `DROP TABLE notes`).
- `migrationDoesNotTouchBooks` — `PRAGMA table_info(books)` совпадает с v9.

- [ ] **Step 2: Прогнать тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.db.migration.Migrate9To10Test"`
Expected: FAIL — `Migrate9To10` не существует.

- [ ] **Step 3: Реализовать `Migrate9To10`**

Порядок (важен — `photos` ссылается на `notes`):
1. `CREATE TEMP TABLE _photos_bak AS SELECT * FROM photos` (страховка: при `DROP TABLE notes` внешний ключ `SET NULL` обнулил бы `photos.note_id`).
2. Создать `notes_new`, перенести `INSERT INTO notes_new SELECT id, book_id, 'BOOKS', page, body_md, kind, created_at, updated_at FROM notes`, `DROP TABLE notes`, `ALTER TABLE notes_new RENAME TO notes`.
3. Создать `photos_new` с FK на `notes(note_id)`, перенести `INSERT INTO photos_new SELECT id, book_id, 'BOOKS', note_id, path, created_at FROM photos`, `DROP TABLE photos`, `ALTER TABLE photos_new RENAME TO photos`.
4. Создать `media_tags`, перенести `INSERT INTO media_tags SELECT book_id, 'BOOKS', tag_id FROM book_tags`, `DROP TABLE book_tags`.
5. Вернуть фото: `INSERT INTO photos SELECT * FROM _photos_bak`; `DROP TABLE _photos_bak`.
6. Пересоздать индексы: `index_notes_media_id`, `index_notes_created_at`, `index_notes_kind`, `index_photos_media_id`, `index_photos_note_id`, `index_media_tags_media_id`, `index_media_tags_tag_id`.

Точный DDL (Room сверяет его при открытии базы):

```sql
CREATE TABLE `notes_new` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `media_id` INTEGER NOT NULL,
  `media_type` TEXT NOT NULL,
  `page` INTEGER,
  `body_md` TEXT NOT NULL,
  `kind` TEXT NOT NULL,
  `created_at` INTEGER NOT NULL,
  `updated_at` INTEGER NOT NULL
);
CREATE TABLE `photos_new` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `media_id` INTEGER NOT NULL,
  `media_type` TEXT NOT NULL,
  `note_id` INTEGER,
  `path` TEXT NOT NULL,
  `created_at` INTEGER NOT NULL,
  FOREIGN KEY(`note_id`) REFERENCES `notes`(`id`) ON UPDATE NO ACTION ON DELETE SET NULL
);
CREATE TABLE `media_tags` (
  `media_id` INTEGER NOT NULL,
  `media_type` TEXT NOT NULL,
  `tag_id` INTEGER NOT NULL,
  PRIMARY KEY(`media_id`, `media_type`, `tag_id`),
  FOREIGN KEY(`tag_id`) REFERENCES `tags`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE
);
```

`media_type` хранится строкой `'BOOKS'` — так же, как `kind` и `status` (конвертер `MediaType` ↔ `String`).

- [ ] **Step 4: Прогнать тест миграции — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.db.migration.Migrate9To10Test"`
Expected: PASS.

- [ ] **Step 5: Перевести сущности, DAO, репозитории и экраны на media-скоуп**

- `data/db/entity/Enums.kt`: добавить `enum class MediaType { BOOKS, MOVIES }`.
- `data/db/entity/BookEntity.kt`: `source: BookSource` → `source: MediaSource`.
- `data/db/entity/NoteEntity.kt`, `PhotoEntity.kt`, `TagEntity.kt`: новые формы (см. Interfaces).
- `data/db/converter/Converters.kt`: `fromBookSource`/`toBookSource` → `fromMediaSource`/`toMediaSource`; добавить `fromMediaType`/`toMediaType` (fallback — `MediaType.BOOKS`).
- `data/db/KwBooktrackDatabase.kt`: `version = 10`; entities: `MediaTagCrossRef` вместо `BookTagCrossRef`.
- `data/db/dao/NoteDao.kt`, `PhotoDao.kt`, `TagDao.kt`: сигнатуры из Interfaces.
- `data/repository/NoteRepository.kt`, `PhotoRepository.kt`, `TagRepository.kt`: сигнатуры из Interfaces; `PhotoRepository.deleteOrphanFilesForBook` → `deleteFilesForMedia`.
- `data/repository/BookRepository.kt`: `delete` и `deleteById` теперь чистят вручную (внешнего ключа на `books` у заметок больше нет). Порядок: заметки → файлы фото → строки фото → связи тегов → обложка. `reading_sessions` по-прежнему каскадит сам.
- `data/snapshot/SnapshotBuilder.kt`: `tagDao.tagsForAllBooks()` → `tagsForAllMedia()`; группировка по `(mediaId, mediaType)`.
- `data/snapshot/SnapshotApplier.kt`: `noteRepository.observeByBook(bookId)` → `observeByMedia(mediaId, mediaType)`; `tagRepository.attachToBook` → `attachToMedia`; `photoRepository.observeByBook` → `observeByMedia`.
- `ui/books/BookDetailViewModel.kt`: `observeByBook(bookId)` → `observeByMedia(bookId, MediaType.BOOKS)`.
- `ui/notes/NotesListViewModel.kt`: заметки привязаны к книгам или фильмам; владелец ищется по `mediaId`+`mediaType`. Пока фильмов нет — ветка `BOOKS`; ветка `MOVIES` появится в Task 6, здесь оставить `mediaType` в модели и использовать `BOOKS` при поиске владельца, с пометкой `// TODO(Task 6): фильмы`.
- `ui/notes/NoteEditorViewModel.kt`: `bookId` → `mediaId` + `mediaType` из `SavedStateHandle`.
- `ui/add/AddBookViewModel.kt`: `BookSource` → `MediaSource`.

- [ ] **Step 6: Прогнать затронутые тесты**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.db.dao.*" --tests "ru.antonkw.kwbooktrack.data.snapshot.*" --tests "ru.antonkw.kwbooktrack.data.repository.*" --tests "ru.antonkw.kwbooktrack.ui.notes.*" --tests "ru.antonkw.kwbooktrack.ui.books.*"`
Expected: PASS (существующие тесты обновлены под новые сигнатуры).

- [ ] **Step 7: Прогнать всю сборку — компиляция чистая**

Run: `./gradlew testDebugUnitTest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 8: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — заметки, теги и фото привязаны к книге или фильму

Заметки, цитаты, теги и фото больше не ссылаются на books внешним ключом:
вместо него media_id + media_type. Так их смогут использовать и фильмы.
Миграция v9 -> v10 пересоздаёт три маленькие таблицы; books и
reading_sessions не тронуты."
```

---

### Task 2: Таблица `movies`, `MovieDao`, `MovieRepository` + миграция v10→v11

**Files:**
- Create: `data/db/entity/MovieEntity.kt`, `data/db/dao/MovieDao.kt`, `data/repository/MovieRepository.kt`, `data/db/migration/Migrate10To11.kt`
- Modify: `data/db/KwBooktrackDatabase.kt` (version 11, + `MovieEntity`, `abstract fun movieDao(): MovieDao`), `data/di/DatabaseModule.kt` (`addMigrations(Migrate9To10, Migrate10To11)` + `provideMovieDao`)

**Interfaces:**
- Consumes: `MediaType`, `MediaSource` из Task 1.
- Produces:
  ```kotlin
  // data/db/entity/MovieEntity.kt
  @Entity(tableName = "movies", indices = [
      Index(value = ["title_search"]), Index(value = ["people_search"]), Index(value = ["watched_at"]),
  ])
  data class MovieEntity(
      @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "id") val id: Long = 0,
      @ColumnInfo(name = "title") val title: String,
      @ColumnInfo(name = "original_title") val originalTitle: String? = null,
      @ColumnInfo(name = "year") val year: Int? = null,
      @ColumnInfo(name = "genre") val genre: String? = null,
      @ColumnInfo(name = "nationality") val nationality: String? = null,
      @ColumnInfo(name = "director") val director: String? = null,
      @ColumnInfo(name = "cast") val cast: String? = null,
      @ColumnInfo(name = "duration_min") val durationMin: Int? = null,
      @ColumnInfo(name = "rating") val rating: Int? = null,
      @ColumnInfo(name = "watched_at") val watchedAt: Long? = null,
      @ColumnInfo(name = "cover_path") val coverPath: String? = null,
      @ColumnInfo(name = "plot") val plot: String? = null,
      @ColumnInfo(name = "comments") val comments: String? = null,
      @ColumnInfo(name = "url") val url: String? = null,
      @ColumnInfo(name = "is_downloaded") val isDownloaded: Boolean = false,
      @ColumnInfo(name = "created_at") val createdAt: Long,
      @ColumnInfo(name = "updated_at") val updatedAt: Long,
      @ColumnInfo(name = "source") val source: MediaSource = MediaSource.MANUAL,
      @ColumnInfo(name = "title_search") val titleSearch: String = "",
      @ColumnInfo(name = "people_search") val peopleSearch: String = "",
      @ColumnInfo(name = "extra_fields") val extraFields: Map<String, ExtraField> = emptyMap(),
  )

  // data/db/dao/MovieDao.kt
  @Query("SELECT * FROM movies ORDER BY updated_at DESC") fun observeAll(): Flow<List<MovieEntity>>
  @Query("SELECT * FROM movies ORDER BY updated_at DESC") suspend fun getAll(): List<MovieEntity>
  @Query("SELECT * FROM movies WHERE id = :id LIMIT 1") suspend fun findById(id: Long): MovieEntity?
  @Query("SELECT * FROM movies WHERE title = :title AND year IS :year ORDER BY updated_at DESC LIMIT 1")
  suspend fun findByTitleAndYear(title: String, year: Int?): MovieEntity?
  @RawQuery(observedEntities = [MovieEntity::class]) fun rawSearch(query: SupportSQLiteQuery): Flow<List<MovieEntity>>
  @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(movie: MovieEntity): Long
  @Update suspend fun update(movie: MovieEntity): Int
  @Delete suspend fun delete(movie: MovieEntity): Int
  @Query("DELETE FROM movies WHERE id = :id") suspend fun deleteById(id: Long): Int
  @Query("SELECT COUNT(*) FROM movies") suspend fun count(): Int
  @Transaction suspend fun upsertFromImport(movie: MovieEntity): Long   // findByTitleAndYear → mergeWith/insert

  // data/repository/MovieRepository.kt
  @Singleton class MovieRepository @Inject constructor(
      private val movieDao: MovieDao,
      private val tagDao: TagDao,
      private val coverStorage: CoverStorage,
      private val photoRepository: PhotoRepository,
  ) {
      fun observeAll(): Flow<List<MovieEntity>>
      suspend fun getAll(): List<MovieEntity>
      fun search(query: String): Flow<List<MovieEntity>>
      suspend fun findById(id: Long): MovieEntity?
      suspend fun insert(movie: MovieEntity): Long
      suspend fun update(movie: MovieEntity): Int
      suspend fun updateFromImport(movie: MovieEntity): Int
      suspend fun upsertFromImport(movie: MovieEntity): Long
      suspend fun delete(movie: MovieEntity): Int
      suspend fun deleteById(id: Long): Int
      suspend fun count(): Int
  }
  ```
  `withSearchKeys(movie)`: `titleSearch` = `title` + `originalTitle` (через пробел, lowercase), `peopleSearch` = `director` + `cast` (lowercase). Поиск — raw SQL, токены как в `BookRepository.search`, цели `title_search` и `people_search`.
  `mergeWith` (внутри `upsertFromImport`): nullable-поля берутся из существующего, `source` сохраняется, булевы не сливаются, `extraFields = existing + incoming` — как в `BookDao`.

- [ ] **Step 1: Написать падающий тест `MovieDaoTest`**

Создать `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/db/dao/MovieDaoTest.kt` по образцу `BookDaoTest.kt` (через `TestDb.createInMemory`). Тесты:
- `insertAndFindById`
- `findByTitleAndYearMatchesEvenWhenYearIsNull`
- `upsertFromImportDoesNotDuplicate` — дважды с одинаковым названием и годом → одна строка, id совпадают.
- `upsertFromImportKeepsExistingNullableFields` — `mergeWith` не затирает заполненное поле пустым из импорта.
- `searchFindsByDirector` — `rawSearch` находит по `people_search` (регистр кириллицы).

- [ ] **Step 2: Прогнать — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.db.dao.MovieDaoTest"`
Expected: FAIL — `MovieEntity` не существует.

- [ ] **Step 3: Реализовать `MovieEntity`, `MovieDao`, `MovieRepository`, `Migrate10To11`**

DDL миграции:

```sql
CREATE TABLE IF NOT EXISTS `movies` (
  `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
  `title` TEXT NOT NULL,
  `original_title` TEXT,
  `year` INTEGER,
  `genre` TEXT,
  `nationality` TEXT,
  `director` TEXT,
  `cast` TEXT,
  `duration_min` INTEGER,
  `rating` INTEGER,
  `watched_at` INTEGER,
  `cover_path` TEXT,
  `plot` TEXT,
  `comments` TEXT,
  `url` TEXT,
  `is_downloaded` INTEGER NOT NULL,
  `created_at` INTEGER NOT NULL,
  `updated_at` INTEGER NOT NULL,
  `source` TEXT NOT NULL,
  `title_search` TEXT NOT NULL,
  `people_search` TEXT NOT NULL,
  `extra_fields` TEXT NOT NULL
);
CREATE INDEX `index_movies_title_search` ON `movies` (`title_search`);
CREATE INDEX `index_movies_people_search` ON `movies` (`people_search`);
CREATE INDEX `index_movies_watched_at` ON `movies` (`watched_at`);
```

- [ ] **Step 4: Прогнать тест DAO — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.db.dao.MovieDaoTest"`
Expected: PASS.

- [ ] **Step 5: Написать `Migrate10To11Test` и прогнать**

По образцу `Migrate9To10Test` (схема v10 = схема v9 + новые `notes`/`photos`/`media_tags`). Тесты: `migrationCreatesMoviesTable`, `migrationCreatesMoviesIndices` (проверка через `PRAGMA index_list(movies)`).

- [ ] **Step 6: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — таблица фильмов: сущность, DAO, репозиторий

Миграция v10 -> v11 создаёт movies с индексами по названию, людям и дате
просмотра. Ключ слияния при импорте — название и год: у фильма нет ISBN."
```

---

### Task 3: Обложки — UUID-имена у книг и фильмов

**Files:**
- Modify: `data/storage/Storages.kt` (`CoverStorage`), `data/repository/BookRepository.kt`, `data/repository/MovieRepository.kt`, `ui/add/AddBookViewModel.kt`, `data/snapshot/SnapshotApplier.kt`
- Test: `data/storage/StoragesTest.kt`, `data/snapshot/SnapshotApplierTest.kt`

**Interfaces:**
- Consumes: `MovieRepository` из Task 2.
- Produces:
  ```kotlin
  // data/storage/Storages.kt
  interface CoverStorage {
      /** Сохраняет во временный файл, возвращает абсолютный путь. */
      fun saveTemp(source: InputStream, extension: String): String
      /** Возвращает окончательный путь обложки. Имя уже уникальное (UUID) — копировать нечего. */
      fun promote(tempPath: String?): String?
      /** Удаляет файл обложки по точному пути. */
      fun delete(path: String?)
  }
  ```
  `FileCoverStorage.saveTemp` уже пишет `covers/cover_{UUID}.{ext}` — менять нечего. `promote(tempPath)` возвращает `tempPath` (или `null` для `null`). `delete(path)` — `File(path)?.delete()`, с проверкой, что файл внутри `coversDir`.

- [ ] **Step 1: Написать падающий тест**

В `data/storage/StoragesTest.kt` добавить:
- `promoteKeepsUuidName` — `saveTemp` → `promote` → имя файла начинается с `cover_`, расширение сохранено.
- `deleteRemovesOnlyGivenFile` — две обложки, `delete` одной не трогает вторую.

- [ ] **Step 2: Прогнать — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.storage.StoragesTest"`
Expected: FAIL — `promote`/`delete` не существуют.

- [ ] **Step 3: Реализовать новый интерфейс `CoverStorage`**

`promoteToBook(bookId, tempPath)` → `promote(tempPath)`; `deleteForBook(bookId)` → `delete(path)`. В `FileCoverStorage` удалить `deleteCoversFor` (поиск по префиксу `"$bookId."` больше не нужен).

- [ ] **Step 4: Обновить вызовы**

- `BookRepository.delete` / `deleteById`: `coverStorage.deleteForBook(book.id)` → `coverStorage.delete(book.coverPath)`.
- `MovieRepository.delete` / `deleteById`: то же + ручная чистка заметок/фото/тегов фильма (внешнего ключа на `movies` нет).
- `ui/add/AddBookViewModel.kt`: `coverStorage.deleteForBook(existing.id)` → `coverStorage.delete(existing.coverPath)`.
- `SnapshotApplier.applyCover`: файл обложки писать как `covers/cover_{UUID}.{ext}` вместо `covers/{bookId}.{ext}`; старый файл (прежний `cover_path`) удалять после записи нового.

- [ ] **Step 5: Прогнать тесты**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.storage.*" --tests "ru.antonkw.kwbooktrack.data.snapshot.*"`
Expected: PASS.

- [ ] **Step 6: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — обложки с UUID-именами вместо имени по id

Имя cover_{uuid} не конфликтует между книгами и фильмами и не привязано к
строке БД. Старые файлы {bookId}.{ext} продолжают работать: в базе лежит
абсолютный путь, а не имя файла."
```

---

## Фаза 2 — Импорт и экспорт

### Task 4: Ридер Tellico читает коллекцию фильмов и чинит `cast`

**Files:**
- Modify: `data/tellico/TellicoRecord.kt`, `data/tellico/TellicoFields.kt`, `data/tellico/TellicoXmlReader.kt`
- Test: `data/tellico/TellicoXmlReaderTest.kt`

**Interfaces:**
- Consumes: ничего.
- Produces:
  ```kotlin
  // data/tellico/TellicoRecord.kt
  data class TellicoRecord(
      val fields: Map<String, String> = emptyMap(),
      val extra: Map<String, ExtraField> = emptyMap(),
      /** Тип коллекции Tellico: 2 — книги, 3 — фильмы. */
      val collectionType: Int = 2,
  )

  // data/tellico/TellicoFields.kt
  const val COLLECTION_BOOKS = 2
  const val COLLECTION_MOVIES = 3
  /** Заголовки полей коллекции фильмов (внутреннее имя → русский заголовок). */
  val MOVIE_TITLES: Map<String, String>
  /** Известные поля коллекции фильмов. */
  val MOVIE_KNOWN: Set<String>
  fun knownFields(collectionType: Int): Set<String> =
      if (collectionType == COLLECTION_MOVIES) MOVIE_KNOWN else KNOWN
  ```
  `MOVIE_TITLES` содержит: `title`, `year`, `genre`, `nationality`, `director`, `cast`, `running-time`, `rating`, `cover`, `plot`, `comments`, `дата-просмотра`, `оригинальное-название`, `кинопоиск`, `скачан`, `рецензия`, `cdate`, `mdate`, `теги`, `цитаты`, `заметки`, `фото-заметок`, `keyword`, `studio`, `writer`, `producer`, `composer`, `language`, `medium`, `certification`, `region`, `format`, `color`, `aspect-ratio`, `widescreen`, `directors-cut`, `audio-track`, `subtitle`, `pur_date`, `pur_price`, `gift`, `loaned`.

  `TellicoXmlReader.read(bytes|file)` теперь возвращает записи **всех** поддерживаемых коллекций; записи коллекции `type="3"` помечены `collectionType = 3`. Коллекции других типов пропускаются. Если поддерживаемых коллекций нет — `IllegalStateException("Это не коллекция книг и не фильмов")`.

- [ ] **Step 1: Написать падающие тесты**

В `data/tellico/TellicoXmlReaderTest.kt`:
- `readsMovieCollection` — XML с `<collection type="3">` и одной записью → `read()` возвращает 1 запись с `collectionType == 3`, поле `title` = значение.
- `readsBothCollections` — файл с `type="2"` и `type="3"` → 2 записи, `collectionType` 2 и 3.
- `skipsUnsupportedCollectionType` — `type="1"` (библиография) пропускается; если в файле только она — исключение с текстом «Это не коллекция книг и не фильмов».
- `castColumnsJoinedWithDoubleColon` — `<cast><column>Уоттс, Наоми</column><column>Роль</column></cast>` → значение `cast` = `Уоттс, Наоми::Роль` (в значении элемента `cast`, строки разделены `\n`).
- `castWithSingleColumnKeepsOnlyActor` — `<cast><column>Уоттс, Наоми</column></cast>` → `Уоттс, Наоми`, без хвоста из пробелов.

Существующий тест `nonBookCollectionIsRejected` переименовать в `unsupportedCollectionIsRejected` и заменить `type="2"` на `type="1"`.

- [ ] **Step 2: Прогнать — должны упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.tellico.TellicoXmlReaderTest"`
Expected: FAIL.

- [ ] **Step 3: Реализовать чтение всех коллекций**

В `TellicoXmlReader.parse`: собрать все `root.collect("collection")`, оставить с `type` = "2" или "3", для каждой — свои `parseDeclarations` и свои «известные» поля (`TellicoFields.knownFields(type)`), записи собрать в один список с `collectionType`.

- [ ] **Step 4: Починить `cast`**

В `XmlNode.fieldValues()`: если элемент — контейнер (`name == childName + "s"`), значение каждого повторяющегося элемента брать так: если у элемента есть дочерние элементы с именем `column` — соединить их `textContent().trim()` через `::`; иначе — `textContent().trim()`. Значения по-прежнему соединяются разделителем строк таблицы (`\n`, если в объявлении поля `columns > 1`).

- [ ] **Step 5: Прогнать — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.tellico.TellicoXmlReaderTest"`
Expected: PASS.

- [ ] **Step 6: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — ридер Tellico читает коллекцию фильмов

Коллекции type=2 и type=3 читаются вместе, остальные типы пропускаются.
Колонки таблицы cast соединяются через :: — раньше перевод строки внутри
значения склеивался с ролью."
```

---

### Task 5: `TellicoMovieMapper` — Tellico-запись фильма → `MovieSnapshot`

**Files:**
- Create: `data/importer/TellicoMovieMapper.kt`, `data/snapshot/LibrarySnapshot.kt` (+ `MovieSnapshot`)
- Test: `data/importer/TellicoMovieMapperTest.kt`

**Interfaces:**
- Consumes: `MovieEntity` (Task 2), `TellicoRecord.collectionType` (Task 4), `NoteSnapshot`/`PhotoSnapshot` (существующие).
- Produces:
  ```kotlin
  // data/snapshot/LibrarySnapshot.kt
  data class MovieSnapshot(
      val movie: MovieEntity,
      val notes: List<NoteSnapshot> = emptyList(),
      val tags: List<String> = emptyList(),
      val photos: List<PhotoSnapshot> = emptyList(),
      val extraFields: Map<String, ExtraField> = emptyMap(),
  )

  // data/importer/TellicoMovieMapper.kt
  @Singleton class TellicoMovieMapper @Inject constructor() {
      fun map(record: TellicoRecord): MovieSnapshot?
  }
  ```
  Поля (по таблице маппинга в спеке §5.2): `title` (пусто → `"(без названия)"`), `оригинальное-название` → `originalTitle`, `year` → `year`, `genre`/`nationality`/`director` (много значений → `"; "`), `cast` (строки `\n`, ячейки `::`), `running-time` → `durationMin`, `rating` (только 1..5), `дата-просмотра` → `watchedAt`, `cover` → `coverPath`, `plot` → `plot` (HTML → markdown), `comments` → `comments` (HTML → markdown), `рецензия` → заметка `kind = REVIEW`, `кинопоиск` → `url`, `скачан` → `isDownloaded`, `cdate`/`mdate` → `createdAt`/`updatedAt`, `source = MediaSource.TELLICO`, `keyword` → `extraFields["keyword"]`, `теги` → `tags`, `цитаты` → заметки `QUOTE`, `заметки` → заметки `NOTE`, `фото-заметок` → фото. Всё остальное → `extraFields`.
  Даты: `yyyy-MM-dd`, `yyyy-MM`, `yyyy`; неполная дата дополняется 1-м числом — как в `TellicoBookMapper.parseDate`.

- [ ] **Step 1: Написать падающий тест**

Создать `data/importer/TellicoMovieMapperTest.kt` по образцу `TellicoBookMapperTest.kt`. Тесты:
- `mapsBasicFields` — название, год, жанр, режиссёр, продолжительность, рейтинг, страна.
- `mapsWatchedDate` — `дата-просмотра` = `2024-02-22` → `watchedAt` = начало суток 22.02.2024.
- `mapsYearOnlyWatchedDate` — `дата-просмотра` = `2024` → `watchedAt` = 01.01.2024 (фильм просмотрен, дата известна по году).
- `mapsCastKeepingCellBoundary` — `cast` = `Уоттс, Наоми::Роль` сохраняет `::`.
- `mapsReviewToReviewNote` — `рецензия` → заметка `kind = REVIEW`.
- `mapsTagsToTagsList`
- `mapsUnknownFieldsToExtraFields` — `widescreen` → `extraFields["widescreen"]`.
- `returnsNullForEmptyTitle` — запись без `title` → `null`.

- [ ] **Step 2: Прогнать — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.importer.TellicoMovieMapperTest"`
Expected: FAIL.

- [ ] **Step 3: Реализовать маппер**

- [ ] **Step 4: Прогнать — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.importer.TellicoMovieMapperTest"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — маппер фильмов из формата Tellico

Поля коллекции type=3 в MovieEntity; рецензия становится заметкой REVIEW,
как у книг. Дата просмотра «только год» дополняется 1 января."
```

---

### Task 6: Применение фильмов — `SnapshotApplier.applyMovie`

**Files:**
- Modify: `data/snapshot/SnapshotApplier.kt`, `data/snapshot/SnapshotBuilder.kt`
- Test: `data/snapshot/SnapshotApplierTest.kt`, `data/snapshot/SnapshotBuilderTest.kt`

**Interfaces:**
- Consumes: `MovieSnapshot` (Task 5), `MovieRepository` (Task 2), `MediaType` (Task 1).
- Produces:
  ```kotlin
  // data/snapshot/SnapshotApplier.kt
  class SnapshotApplier {
      suspend fun apply(snapshot: BookSnapshot, assets: ImportAssets): ApplyStats
      suspend fun apply(snapshot: MovieSnapshot, assets: ImportAssets): ApplyStats   // перегрузка
  }
  ```
  Общая часть (заметки, теги, фото, обложка) вынесена в приватный метод, принимающий `mediaId`, `mediaType`, списки заметок/тегов/фото. `apply(BookSnapshot)` дополнительно применяет сессии чтения; `apply(MovieSnapshot)` — нет (у фильмов нет трекера страниц).

  `SnapshotBuilder` получает `suspend fun buildMovies(): List<MovieSnapshot>` — собирает фильмы, их заметки (`NoteDao.getAll()`, отфильтрованные по `media_type = MOVIES`), теги (`TagDao.tagsForAllMedia()`, отфильтрованные по `media_type = MOVIES`) и фото (`PhotoDao.getAll()`).

- [ ] **Step 1: Написать падающие тесты**

В `SnapshotApplierTest.kt` добавить:
- `applyMovieInsertsMovieAndItsNotes` — фильм + заметка REVIEW + тег → в БД фильм, заметка с `mediaType = MOVIES`, привязка тега.
- `applyMovieDoesNotDuplicateOnSecondRun` — дважды → один фильм, одна заметка.
- `applyMovieMergesByTitleAndYear` — фильм с тем же названием и годом обновляется, а не добавляется.
- `applyMovieWritesCoverWithUuidName` — файл обложки назван `cover_*.webp`.

В `SnapshotBuilderTest.kt` добавить:
- `buildMoviesCollectsNotesTagsPhotos` — у фильма собираются заметки и теги, у книги — нет (в выборке buildMovies только фильмы).

- [ ] **Step 2: Прогнать — должны упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.snapshot.*"`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

- [ ] **Step 4: Прогнать — должны пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.snapshot.*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — применение фильмов при импорте

Фильм, его заметки, теги и фото применяются теми же правилами слияния, что
и книга: без дублей при повторном импорте."
```

---

### Task 7: Импорт `.tc` разводит записи по типу коллекции

**Files:**
- Modify: `data/importer/ImportTellicoUseCase.kt`, `data/importer/ImportTellicoZipUseCase.kt`
- Test: `data/importer/ImportTellicoUseCaseTest.kt`, `data/importer/TellicoTcImportTest.kt`

**Interfaces:**
- Consumes: `TellicoMovieMapper` (Task 5), `SnapshotApplier.apply(MovieSnapshot)` (Task 6).
- Produces:
  ```kotlin
  // data/importer/ImportTellicoUseCase.kt
  data class ImportTellicoResult(
      val total: Int,
      val imported: Int,
      val skipped: Int,
      val coversCopied: Int,
      val notesImported: Int,
      val dryRun: Boolean,
      val errors: List<Throwable>,
      val warnings: List<String>,
      val movies: Int = 0,          // новое поле
  )
  ```
  `ImportTellicoUseCase.invoke(records, options)` разводит записи: `collectionType == 2` → книжный маппер, `collectionType == 3` → маппер фильмов. `movies` считает импортированные фильмы.

- [ ] **Step 1: Написать падающий тест**

В `ImportTellicoUseCaseTest.kt` добавить:
- `importsMoviesFromMovieCollection` — запись с `collectionType = 3` → в БД фильм, `result.movies == 1`, `result.imported == 1`.
- `countsBooksAndMoviesSeparately` — по одной записи каждого типа → `result.movies == 1`, счётчик книг = 1.
- `movieErrorDoesNotBreakBookImport` — битая запись фильма не роняет импорт книг.

- [ ] **Step 2: Прогнать — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.importer.ImportTellicoUseCaseTest"`
Expected: FAIL.

- [ ] **Step 3: Реализовать развод по типу**

- [ ] **Step 4: Прогнать — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.importer.*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — импорт Tellico разводит книги и фильмы по типу коллекции"
```

---

### Task 8: Экспорт `.tc` — коллекция фильмов

**Files:**
- Modify: `data/tellico/TellicoXmlWriter.kt`, `data/exporter/ExportTellicoTcUseCase.kt`
- Test: `data/exporter/TellicoTcRoundTripTest.kt`

**Interfaces:**
- Consumes: `MovieSnapshot` (Task 5), `MediaType` (Task 1).
- Produces:
  ```kotlin
  // data/tellico/TellicoXmlWriter.kt
  data class TcEntry(
      val snapshot: BookSnapshot,
      val coverArchiveName: String? = null,
  )
  data class TcMovieEntry(
      val snapshot: MovieSnapshot,
      val coverArchiveName: String? = null,
  )
  object TellicoXmlWriter {
      fun write(entries: List<TcEntry>): String
      fun writeMovies(entries: List<TcMovieEntry>): String
  }
  ```
  Коллекция фильмов: `<collection title="kwbooktrack" type="3">`. Объявленные поля — Tellico-поля видео плюс **наши** `цитаты`, `заметки`, `теги`, `фото-заметок` (иначе Tellico выбросит значения). Запись фильма пишет: `title`, `year`, `original_title` → `оригинальное-название`, `genre`, `nationality`, `director`, `cast` (таблицей `<casts><cast><column>…</column></cast></casts>`), `running-time`, `rating`, `cover`, `plot`, `comments`, `дата-просмотра`, `кинопоиск`, `скачан`, `cdate`, `mdate`, `рецензия` (заметка REVIEW), `теги`, `цитаты`, `заметки`, `фото-заметок`, чужие поля из `extraFields`.

  `ExportTellicoTcUseCase` получает тип коллекции **параметром**: `invoke(output: OutputStream, mediaType: MediaType)`. `BOOKS` → `write(entries)`, `MOVIES` → `writeMovies(entries)`. Обе коллекции в одном файле **не** пишутся (см. спеке §3 п. 4). Тип носителя подставляет вызывающий (`MediaTypeStore` появляется в Task 10, экран «О приложении» — в Task 14).

- [ ] **Step 1: Написать падающий тест**

В `TellicoTcRoundTripTest.kt` добавить:
- `exportsMovieCollectionWithTypeThree` — в `tellico.xml` есть `<collection … type="3">` и нет `type="2"`.
- `roundTripKeepsMovieFields` — фильм с жанрами, режиссёром, продолжительностью, датой просмотра, рецензией → экспорт → импорт → поля совпали.
- `roundTripKeepsMovieCast` — `cast` с двумя ячейками пережил round-trip с границей `::`.
- `roundTripKeepsMovieReviewAndNotes` — рецензия и заметка фильма пережили round-trip.
- `exportsOnlyOneCollection` — в файле ровно один `<collection>`.

- [ ] **Step 2: Прогнать — должны упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.exporter.TellicoTcRoundTripTest"`
Expected: FAIL.

- [ ] **Step 3: Реализовать writer фильмов**

- [ ] **Step 4: Прогнать — должны пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.exporter.*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — экспорт в Tellico: коллекция фильмов

В .tc пишется одна коллекция — того типа, который выбран сейчас. Поля
заметок, тегов и фото объявляются, иначе Tellico молча выбросит значения."
```

---

### Task 9: zip-бэкап — `movies.csv`, секция `movies`, импорт

**Files:**
- Modify: `data/exporter/ExportZipUseCase.kt`, `data/importer/ImportKwBooktrackZipUseCase.kt`, `data/snapshot/KwBooktrackArchive.kt`
- Test: `data/exporter/ExportZipUseCaseTest.kt`, `data/importer/ImportKwBooktrackZipUseCaseTest.kt`, `data/exporter/KwBooktrackZipRoundTripTest.kt`

**Interfaces:**
- Consumes: `MovieEntity`, `MovieSnapshot` (Task 2, 5).
- Produces:
  ```kotlin
  // data/exporter/ExportZipUseCase.kt
  data class ExportCounts(
      val books: Int,
      val sessions: Int,
      val notes: Int,
      val quotes: Int,
      val photos: Int,
      val tags: Int,
      val movies: Int = 0,      // новое поле, значение по умолчанию — обязательно
  )
  ```
  `movies.csv` — те же правила, что у `books.csv` (заголовок `;`, кавычки, даты ISO). Заголовок `movies.csv` (24 колонки):
  `title;original_title;year;genre;nationality;director;cast;duration_min;rating;watched_at;cover_path;plot;comments;url;is_downloaded;source;id;created_at;updated_at` — **в этом порядке и ровно эти**; `extra_fields` и заметки/теги/фото — в `extra.json`.
  `extra.json` получает секцию `movies`:
  ```kotlin
  // data/snapshot/KwBooktrackArchive.kt
  @Serializable data class ExtraArchive(
      val books: Map<String, BookExtras> = emptyMap(),
      val movies: Map<String, MovieExtras> = emptyMap(),
  )
  @Serializable data class MovieExtras(
      val notes: List<NoteJson> = emptyList(),
      val tags: List<String> = emptyList(),
      val photos: List<PhotoJson> = emptyList(),
      val extraFields: Map<String, ExtraField> = emptyMap(),
  )
  ```
  `ImportKwBooktrackZipUseCase` читает `movies.csv` (необязательный файл — если нет, фильмов ноль) и секцию `movies` из `extra.json`.

- [ ] **Step 1: Написать падающие тесты**

- `ExportZipUseCaseTest`: `exportWritesMoviesCsv` — в архиве есть `movies.csv` с заголовком; `exportCountsIncludeMovies` — `manifest.json` содержит `"movies": 2`.
- `ImportKwBooktrackZipUseCaseTest`: `importsMoviesFromCsv`; `oldBackupWithoutMoviesStillImports` — архив без `movies.csv` и без секции `movies` импортируется без ошибки (регрессия на `MissingFieldException`).
- `KwBooktrackZipRoundTripTest`: `movieRoundTripKeepsFieldsNotesAndTags` — фильм с заметкой и тегом → экспорт → импорт → всё на месте.

- [ ] **Step 2: Прогнать — должны упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.exporter.*" --tests "ru.antonkw.kwbooktrack.importer.ImportKwBooktrackZipUseCaseTest"`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

- [ ] **Step 4: Прогнать — должны пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.exporter.*" --tests "ru.antonkw.kwbooktrack.importer.*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — zip-бэкап умеет фильмы

movies.csv плюс секция movies в extra.json. Старые бэкапы без фильмов
читаются как раньше: у нового счётчика есть значение по умолчанию."
```

---

## Фаза 3 — Интерфейс

### Task 10: `MediaTypeStore` и список библиотеки с дропдауном «Книги ⇄ Фильмы»

**Files:**
- Create: `data/media/MediaTypeStore.kt`, `ui/common/FilterDropdown.kt`, `ui/common/Period.kt`, `ui/library/LibraryScreen.kt`, `ui/library/LibraryViewModel.kt`
- Modify: `ui/books/BookListScreen.kt` (удаляется), `ui/books/BookListViewModel.kt` (удаляется), `ui/navigation/KwBooktrackNavHost.kt`
- Test: `ui/library/LibraryViewModelTest.kt` (перенос из `ui/books/BookListViewModelTest.kt`)

**Interfaces:**
- Consumes: `MovieRepository` (Task 2), `BookRepository`, `SessionRepository`, `MediaType` (Task 1).
- Produces:
  ```kotlin
  // data/media/MediaTypeStore.kt
  @Singleton class MediaTypeStore @Inject constructor() {
      val type: StateFlow<MediaType>
      fun set(type: MediaType)
  }

  // ui/common/Period.kt — перенесено из BookListViewModel.kt без изменений:
  data class PeriodOption(@StringRes val labelRes: Int, val formatArg: String? = null,
      val startMillis: Long? = null, val endMillis: Long? = null, val wholeYear: Boolean = false) {
      fun matches(epochMs: Long?): Boolean
      fun matchesAny(vararg dates: Long?): Boolean
      val isAllTime: Boolean
      companion object { fun allTime(): PeriodOption; fun year(year: Int): PeriodOption
          fun month(year: Int, month: Int, @StringRes monthNameRes: Int): PeriodOption }
  }
  object PeriodOptions { @StringRes fun monthRes(m: Int): Int }
  data class FilterState(val period: PeriodOption = PeriodOption.allTime(), val type: BookFilterType) {
      val isActive: Boolean
  }

  // ui/common/FilterDropdown.kt — перенесено из BookListScreen.kt без изменений:
  @Composable fun FilterDropdown(label: String, selectedLabel: String, options: List<String>,
      onSelected: (Int) -> Unit, modifier: Modifier = Modifier)
  @Composable fun periodText(option: PeriodOption): String

  // ui/library/LibraryViewModel.kt
  enum class BookFilterType(@StringRes val labelRes: Int) { ALL, UNREAD, READING, FINISHED, PURCHASED }
  enum class MovieFilterType(@StringRes val labelRes: Int) { ALL, WATCHED, UNWATCHED }
  sealed interface LibraryItem {
      val id: Long
      data class Book(val book: BookEntity, val pagesRead: Int, val progressPercent: Int) : LibraryItem
      data class Movie(val movie: MovieEntity) : LibraryItem
  }
  sealed interface LibraryUiState {
      data object Loading : LibraryUiState
      data class Loaded(val items: List<LibraryItem>, val query: String, val isGrid: Boolean,
          val filterActive: Boolean) : LibraryUiState
  }
  @HiltViewModel class LibraryViewModel @Inject constructor(
      private val bookRepository: BookRepository,
      private val movieRepository: MovieRepository,
      private val sessionRepository: SessionRepository,
      private val mediaTypeStore: MediaTypeStore,
      private val savedStateHandle: SavedStateHandle,
  ) : ViewModel() {
      val uiState: StateFlow<LibraryUiState>
      val currentQuery: StateFlow<String>
      val currentLayout: StateFlow<Boolean>
      val currentMediaType: StateFlow<MediaType>
      val currentBookFilter: StateFlow<BookFilterType>
      val currentMovieFilter: StateFlow<MovieFilterType>
      val currentPeriod: StateFlow<PeriodOption>
      fun setQuery(value: String); fun toggleLayout(); fun setMediaType(type: MediaType)
      fun setBookFilter(value: BookFilterType); fun setMovieFilter(value: MovieFilterType)
      fun setPeriod(option: PeriodOption); fun clearFilters()
  }
  ```
  Фильтры: книги — как сейчас; фильмы — `ALL` (все), `WATCHED` (`watchedAt != null`), `UNWATCHED` (`watchedAt == null`). Период для фильмов — по `watchedAt`.

  `LibraryScreen(onMediaClick: (MediaType, Long) -> Unit, onOpenStats, onAddMedia: (MediaType) -> Unit, onOpenCalendar, onOpenNotes, onOpenAbout, modifier, viewModel)`.
  Заголовок TopAppBar — `MediaTypeDropdown` (тот же `ExposedDropdownMenuBox`, что у `FilterDropdown`): варианты «Книги»/«Фильмы». Сканер ISBN — только в режиме книг. FAB — «добавить книгу» или «добавить фильм».

  Маршруты: `Routes.BOOK_LIST = "library"` (бывший `books`), `Routes.BOOK_DETAIL = "books/{bookId}"` без изменений.

- [ ] **Step 1: Перенести тест `BookListViewModelTest` в `LibraryViewModelTest` и расширить**

- `filtersBooksByStatus` — как сейчас.
- `filtersMoviesByWatched` — в режиме MOVIES с фильтром WATCHED остаются только фильмы с `watchedAt`.
- `switchingMediaTypeSwitchesContent` — после `setMediaType(MOVIES)` в состоянии только фильмы.
- `keepsSeparateFiltersPerMediaType` — фильтр книг не сбрасывается при переключении на фильмы и обратно.
- Остальные тесты из `BookListViewModelTest` переносятся как есть.

- [ ] **Step 2: Прогнать — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.ui.library.LibraryViewModelTest"`
Expected: FAIL.

- [ ] **Step 3: Реализовать `MediaTypeStore`, `ui/common/Period.kt`, `ui/common/FilterDropdown.kt`**

- [ ] **Step 4: Реализовать `LibraryViewModel` и `LibraryScreen`**

- [ ] **Step 5: Прогнать — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.ui.library.*"`
Expected: PASS.

- [ ] **Step 6: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — список библиотеки с переключателем книг и фильмов

Заголовок «Книги» стал дропдауном: Книги ⇄ Фильмы. Поиск, период и сетка
фильтров общие, набор вариантов «Тип» — свой для каждого типа носителя."
```

---

### Task 11: Карточка фильма и его редактирование

**Files:**
- Create: `ui/movies/MovieDetailScreen.kt`, `ui/movies/MovieDetailViewModel.kt`, `ui/movies/MovieEditScreen.kt`, `ui/movies/MovieEditViewModel.kt`
- Modify: `ui/navigation/KwBooktrackNavHost.kt`, `res/values/strings.xml`, `res/values-en/strings.xml`
- Test: `ui/movies/MovieDetailViewModelTest.kt`, `ui/movies/MovieEditViewModelTest.kt`

**Interfaces:**
- Consumes: `MovieRepository`, `NoteRepository`, `PhotoRepository`, `TagRepository`, `CoverStorage` (Task 1–2).
- Produces:
  ```kotlin
  // ui/navigation/KwBooktrackNavHost.kt
  const val MOVIE_DETAIL = "movies/{movieId}"
  const val ADD_MOVIE = "movies/new"
  const val EDIT_MOVIE = "movies/{movieId}/edit"
  fun movieDetail(movieId: Long) = "movies/$movieId"
  fun editMovie(movieId: Long) = "movies/$movieId/edit"
  ```
  `MovieDetailViewModel` — по образцу `BookDetailViewModel`: `movieId` из `SavedStateHandle`, состояние содержит фильм, заметки, фото, теги; удаление чистит заметки/фото/теги фильма.
  `MovieEditViewModel` — по образцу `AddBookViewModel`: создание и редактирование, поля фильма, обложка, рейтинг, теги, сохранение.

  Новые строки (ru / en): `movie_list_title` «Фильмы» / "Movies"; `movie_detail_title` «Фильм» / "Movie"; `movie_add_title` «Новый фильм» / "New movie"; `movie_edit_title` «Редактирование фильма» / "Edit movie"; `cd_add_movie` «Добавить фильм» / "Add movie"; `movie_field_original_title` «Оригинальное название» / "Original title"; `movie_field_year` «Год» / "Year"; `movie_field_genre` «Жанры» / "Genres"; `movie_field_nationality` «Страна» / "Country"; `movie_field_director` «Режиссёр» / "Director"; `movie_field_cast` «Актёры» / "Cast"; `movie_field_duration` «Продолжительность, мин» / "Duration, min"; `movie_field_watched_at` «Дата просмотра» / "Watched on"; `movie_field_plot` «Сюжет» / "Plot"; `movie_field_url` «Ссылка» / "Link"; `movie_field_downloaded` «Скачан» / "Downloaded"; `movie_duration_label` «%1$d мин» / "%1$d min".

- [ ] **Step 1: Написать падающие тесты**

`MovieDetailViewModelTest`:
- `loadsMovieWithNotesAndTags`
- `deletesMovieWithItsNotes` — после удаления заметок фильма в БД нет.

`MovieEditViewModelTest`:
- `createsMovie` — сохранение нового фильма создаёт строку с введёнными полями.
- `editsExistingMovie`
- `savesWatchedDate`
- `doesNotSaveUnchanged` — dirty-check, как в `AddBookViewModelTest`.

- [ ] **Step 2: Прогнать — должны упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.ui.movies.*"`
Expected: FAIL.

- [ ] **Step 3: Реализовать экраны и маршруты**

- [ ] **Step 4: Прогнать — должны пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.ui.movies.*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — карточка фильма и его редактирование

Фильм показывает те же заметки, цитаты, теги и фото, что и книга, и
редактируется так же."
```

---

### Task 12: Экраны заметок работают с фильмами

**Files:**
- Modify: `ui/notes/NotesListViewModel.kt`, `ui/notes/NoteEditorViewModel.kt`, `ui/notes/NoteEditorScreen.kt`, `ui/notes/NoteDetailScreen.kt`, `ui/navigation/KwBooktrackNavHost.kt`
- Test: `ui/notes/NoteEditorViewModelTest.kt`, `ui/notes/NotesListViewModelTest.kt`

**Interfaces:**
- Consumes: `MediaType` (Task 1), `MovieRepository` (Task 2).
- Produces:
  ```kotlin
  // ui/navigation/KwBooktrackNavHost.kt
  const val NOTE_EDITOR = "notes/new?mediaId={mediaId}&mediaType={mediaType}&kind={kind}"
  fun noteEditor(mediaId: Long, mediaType: MediaType, kind: NoteKind = NoteKind.NOTE): String
  ```
  `NotesListViewModel` ищет владельца заметки: при `mediaType = BOOKS` — в книгах, при `MOVIES` — в фильмах. В списке заметок у фильма показывается его название вместо «(книга удалена)».
  `NoteEditorViewModel` принимает `mediaId` и `mediaType` из `SavedStateHandle` и пишет заметку с ними.

- [ ] **Step 1: Написать падающие тесты**

- `NoteEditorViewModelTest`: `createsNoteForMovie` — заметка фильма сохраняется с `mediaType = MOVIES` и `mediaId` фильма.
- `NotesListViewModelTest`: `listsMovieNotesWithMovieTitle` — заметка фильма показывается с названием фильма.

- [ ] **Step 2: Прогнать — должны упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.ui.notes.*"`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

- [ ] **Step 4: Прогнать — должны пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.ui.notes.*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — заметки и цитаты принадлежат книге или фильму

Маршрут редактора заметки несёт mediaId и mediaType; список заметок
показывает название фильма наравне с названием книги."
```

---

### Task 13: Календарь просмотров и статистика по типам носителя

**Files:**
- Create: `data/stats/MovieStatsCalculator.kt`
- Modify: `ui/calendar/CalendarViewModel.kt`, `ui/calendar/CalendarScreen.kt`, `ui/stats/StatisticsViewModel.kt`, `ui/stats/StatisticsScreen.kt`, `res/values/strings.xml`, `res/values-en/strings.xml`
- Test: `data/stats/MovieStatsCalculatorTest.kt`, `ui/calendar/CalendarViewModelTest.kt`, `ui/stats/StatisticsViewModelTest.kt`

**Interfaces:**
- Consumes: `MediaTypeStore` (Task 10), `MovieRepository` (Task 2).
- Produces:
  ```kotlin
  // data/stats/MovieStatsCalculator.kt
  data class MovieStats(
      val totalMovies: Int,
      val totalMinutes: Int,
      val monthlyWatched: List<MonthlyBooks>,
      val monthlyMinutes: List<MonthlyPages>,
      val yearlyWatched: List<YearlyBooks>,
      val yearlyMinutes: List<YearlyPages>,
      val minYear: Int,
      val maxYear: Int,
  )
  object MovieStatsCalculator {
      fun compute(movies: List<MovieEntity>): MovieStats
  }
  ```
  Минуты фильма = `durationMin` (0, если нет). Агрегация — по `watchedAt`; фильмы без `watchedAt` в календарь и графики не попадают.

  Календарь: `CalendarDay(date, books, pages)` получает ещё `minutes: Int`. Режимы: «Обложки» и «Значение»; в режиме книг значение — страницы, в режиме фильмов — минуты. Строка `calendar_mode_books` «Книги» / "Books" заменяется на `calendar_mode_covers` «Обложки» / "Covers"; добавляется `calendar_mode_minutes` «Минуты» / "Minutes".

  Статистика: для фильмов — карточка «Фильмов: N» и «Минут: M», графики «Просмотрено фильмов по месяцам/годам» и «Минут просмотра по месяцам/годам». Графики «куплено»/«потрачено» только для книг.

  Новые строки: `calendar_mode_covers` «Обложки» / "Covers"; `calendar_mode_minutes` «Минуты» / "Minutes"; `calendar_prompt_movies` «Сколько фильмов вы посмотрели в этом месяце?» / "How many movies did you watch this month?"; `stats_movies_watched` «Фильмов: %1$d» / "Movies: %1$d"; `stats_minutes_watched` «Минут: %1$d» / "Minutes: %1$d"; `stats_chart_movies_by_month` / `_by_year`; `stats_chart_minutes_by_month` / `_by_year`.

- [ ] **Step 1: Написать падающие тесты**

`MovieStatsCalculatorTest`:
- `countsWatchedMoviesByMonth`
- `sumsMinutesByMonth` — два фильма по 147 и 90 минут в одном месяце → 237.
- `ignoresMoviesWithoutWatchedDate`
- `computesYearlyAggregates`

`CalendarViewModelTest`:
- `movieModeShowsMinutesForWatchedDay` — фильм с `watchedAt` и `durationMin = 147` → в ячейке дня `minutes == 147`.

`StatisticsViewModelTest`:
- `movieModeUsesMovieStats` — в режиме MOVIES агрегаты считаются по фильмам.

- [ ] **Step 2: Прогнать — должны упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.stats.*" --tests "ru.antonkw.kwbooktrack.ui.calendar.*" --tests "ru.antonkw.kwbooktrack.ui.stats.*"`
Expected: FAIL.

- [ ] **Step 3: Реализовать калькулятор, календарь и статистику**

- [ ] **Step 4: Прогнать — должны пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.data.stats.*" --tests "ru.antonkw.kwbooktrack.ui.calendar.*" --tests "ru.antonkw.kwbooktrack.ui.stats.*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add -A
git commit -m "v 0.7.0 — календарь просмотров и статистика времени

В режиме фильмов календарь показывает минуты вместо страниц, статистика —
число фильмов и минут просмотра. Время просмотра берётся из
продолжительности фильма."
```

---

### Task 14: «О приложении» — счётчик фильмов и выбор коллекции для `.tc`

**Files:**
- Modify: `ui/about/AboutScreen.kt`, `ui/about/AboutExportViewModel.kt`, `res/values/strings.xml`, `res/values-en/strings.xml`
- Test: `ui/about/AboutViewModelTest.kt`, `ui/about/AboutExportViewModelTest.kt`

**Interfaces:**
- Consumes: `MediaTypeStore` (Task 10), `MovieRepository` (Task 2), `ExportTellicoTcUseCase` (Task 8).
- Produces:
  ```kotlin
  // ui/about/AboutScreen.kt — AboutViewModel в этом же файле
  data class AboutInfo(val version: String, val totalBooks: Int, val finishedBooks: Int, val totalMovies: Int)
  ```
  В карточке «Библиотека» — счётчик фильмов (`about_total_movies` «Всего фильмов: %1$d» / "Total movies: %1$d"). Рядом с кнопкой «Экспорт в Tellico (.tc)» — переключатель «Книги ⇄ Фильмы», показывающий, какая коллекция выгружается; он меняет `MediaTypeStore`. Сообщение об успехе экспорта — с числом книг или фильмов (`export_success_movies_format` «Экспорт готов: %1$d фильмов» / "Export ready: %1$d movies").

- [ ] **Step 1: Написать падающие тесты**

- `AboutViewModelTest`: `countsMovies`.
- `AboutExportViewModelTest`: `tellicoExportUsesSelectedCollection` — при `MediaTypeStore` = MOVIES экспортируется коллекция `type="3"`.

- [ ] **Step 2: Прогнать — должны упасть**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.ui.about.*"`
Expected: FAIL.

- [ ] **Step 3: Реализовать**

- [ ] **Step 4: Прогнать — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "ru.antonkw.kwbooktrack.ui.about.*"`
Expected: PASS.

- [ ] **Step 5: Финальная проверка и коммит**

Run: `./gradlew testDebugUnitTest` — ожидается BUILD SUCCESSFUL.

```bash
git add -A
git commit -m "v 0.7.0 — экран «О приложении»: счётчик фильмов и выбор коллекции"
```

---

## Self-Review

**1. Покрытие спецификации.** §2 цели → Task 2, 5, 6, 7, 8, 9, 10, 11, 13. §3 (структура `movi.tc`, неполная дата, правка `cast`, одна коллекция, поля под заметки) → Task 4, 5, 8. §4 (модель данных, `MediaType`, media-скоуп, `MovieEntity`, `MediaSource`, `MediaTypeStore`, обложки) → Task 1, 2, 3, 10. §5 импорт → Task 4, 5, 6, 7. §6 экспорт → Task 8, 9. §7 статистика и календарь → Task 13. §8 интерфейс → Task 10, 11, 12, 13, 14. §9 миграции → Task 1, 2. §10 решения → отражены в Global Constraints и в описаниях задач.

**2. Шаги.** Каждый шаг — одно действие с проверяемым результатом; тесты названы, сигнатуры заданы, значения из спеки приведены дословно. Пробелов «TBD» нет.

**3. Согласованность типов.** `MediaType` в Task 1 → используется в Task 2, 4, 6, 10, 12, 13. `MovieEntity` в Task 2 → Task 5, 6, 8, 9, 13. `MovieSnapshot` в Task 5 → Task 6, 8. `MediaTypeStore` в Task 10 → Task 13, 14 и `ExportTellicoTcUseCase` в Task 8 — **Task 8 идёт раньше Task 10, а нужен `MediaTypeStore`**. Исправлено: `ExportTellicoTcUseCase` в Task 8 принимает тип коллекции **параметром** (`invoke(output, mediaType: MediaType)`), а `MediaTypeStore` подключается в Task 14 (экран «О приложении»), который и так зависит от Task 10.

**4. Review Focus.** 1 → Task 2 (`upsertFromImportDoesNotDuplicate`), Task 6. 2 → Task 2/3 (ручная чистка), Task 11. 3 → Task 9 (`oldBackupWithoutMoviesStillImports`). 4 → Task 3. 5 → Task 8. 6 → Task 5 (`mapsYearOnlyWatchedDate`), Task 13. 7 → Task 4 (`castColumnsJoinedWithDoubleColon`). 8 → Task 1 (`migrationKeepsPhotoNoteLink`) и существующий `SnapshotApplierTest`.

**5. Пропорция.** План — 14 задач, ~450 строк; код, который он описывает, — порядка 2 500–3 000 строк. План держит решения (имена, сигнатуры, значения, тесты), а не пересказывает тела функций.
