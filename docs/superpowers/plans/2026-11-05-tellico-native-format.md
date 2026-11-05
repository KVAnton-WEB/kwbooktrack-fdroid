# Родной формат Tellico (.tc / XML): импорт и экспорт без потерь — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Приложение читает и пишет родной формат Tellico (`.tc` = zip с `tellico.xml` + `images/`), определяя формат файла автоматически; CSV-путь и zip-бэкап приложения сохраняются и тоже становятся без потерь.

**Architecture:** Единая промежуточная модель `TellicoRecord` (внутренние имена полей Tellico) наполняется двумя ридерами — CSV и XML; маппер превращает её в `BookSnapshot` (снимок книги с заметками, сессиями, тегами, фото и чужими полями). `SnapshotBuilder` собирает такие снимки из БД, `SnapshotApplier` вливает их обратно (слияние без дублей) — этот путь общий для всех форматов. Экспорт: `ExportTellicoTcUseCase` пишет `.tc`, `ExportZipUseCase` дополняется `extra.json` + `photos/`.

**Tech Stack:** Kotlin, Room (v8 → v9), Hilt, kotlinx.serialization, commons-csv, `javax.xml.parsers` (чистый JVM), JUnit4 + Robolectric + Truth.

**Spec:** `docs/superpowers/specs/2026-11-05-tellico-native-format-design.md` (утверждён пользователем)

## Global Constraints

- **Язык ответов и коммитов** — русский. Код, идентификаторы, имена файлов — без перевода.
- **Версия в каждом коммите**: pre-commit hook (`hooks/pre-commit`) требует, чтобы в `app/build.gradle.kts` изменились **и** `versionCode`, **и** `versionName`. Текущие: `versionCode = 43`, `versionName = "0.5.35"`. Задача N бампит их до `43 + N` / `0.5.{35 + N}` — но с учётом fix-коммитов (Task 1 → 45/0.5.37, Task 6 → 51/0.5.43 и 52/0.5.44) фактические значения: Task 2 = 46/0.5.38, Task 3 = 47/0.5.39, … Task 7 = 53/0.5.45, Task 8 = 54/0.5.46, Task 9 = 55/0.5.47, Task 10 = 56/0.5.48. Сообщение коммита — в стиле репозитория: `v 0.5.36 — <что сделано>`.
- **Тесты**: `./gradlew testDebugUnitTest`. Production-сборку (`--production`) не запускать никогда.
- **Никаких новых зависимостей.** `kotlinx.serialization.json` и `commons-csv` уже подключены; XML — чистый `javax.xml.parsers`; MD5 — `java.security.MessageDigest`.
- **Даты** хранятся как epoch-millis в локальной зоне (`data/time/Epoch.kt`: `LocalDate.toEpochMs()`, `Long.toLocalDate()`).
- **Обложки** приложение хранит как `filesDir/covers/{bookId}.{ext}` (WebP). Имя файла в архиве Tellico — MD5 от содержимого (как у Tellico).
- **Формат `.tc` = ZIP.** Внутри: `tellico.xml`, `images/{md5}.{ext}`, `photos/{md5}.{ext}`.
- **Формат определяется по содержимому файла**, не по расширению.

## Формат Tellico — факты, проверенные на реальных данных

Источник: `~/Документы/Tellico/tellico.xml` (коллекция «Мои книги», `type="2"`, 605 записей, 45 полей), `~/Документы/Tellico/kwbook/backup.tc`, DTD `http://periapsis.org/tellico/dtd/v12/tellico.dtd`.

**Каркас файла:**

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tellico PUBLIC '-//Robby Stephenson/DTD Tellico V12.0//EN' 'http://periapsis.org/tellico/dtd/v12/tellico.dtd'>
<tellico xmlns="http://periapsis.org/tellico/" syntaxVersion="12">
 <collection title="Мои книги" type="2">
  <fields>…</fields>
  <entry id="1">…</entry>
  <images><image format="jpeg" height="488" id="0dcebf7e….jpeg" width="310"/></images>
 </collection>
 <filters>…</filters>
</tellico>
```

**Правила, без которых файл Tellico не поймёт:**

1. **Контейнеры многозначных полей.** Поле с `flags & 1` (AllowMultiple) пишется контейнером `<имя+s>`, значения — вложенными элементами `<имя>`:
   `<authors><author>Толкин, Джон</author></authors>`, `<genres><genre>…</genre></genres>`,
   `<languages><language>ru</language></languages>`, `<translators><translator>…</translator></translators>`,
   `<keywords><keyword>…</keyword></keywords>`, `<cr_years><cr_year>…</cr_year></cr_years>`,
   `<цитатыs><цитаты>…</цитаты></цитатыs>`.
   Имя **поля** — единственное (`author`), контейнер — с буквой `s` на конце. Плоская запись `<author>…</author>` без контейнера Tellico не читает как «список».
2. **Имена полей — внутренние, английские** для встроенных (`title`, `author`, `publisher`, `pub_year`, `cr_year`, `isbn`, `pages`, `language`, `genre`, `keyword`, `series`, `series_num`, `condition`, `translator`, `binding`, `pur_price`, `pur_date`, `read`, `gift`, `loaned`, `rating`, `cover`, `comments`, `cdate`, `mdate`) и **свои** для пользовательских (`место-покупки`, `продано-new`, `дата-прочтения`, `прочитал-часть`, `усл.-печ.-л.`, `язык-оригинала`, `оригинальное-название`, `цитаты`, `рецензия`, `чтец`, `иллюстратор`, `обложка`, `комикс`, `livelibfantlab`, …). Человеческое название лежит в атрибуте `title` у `<field>`.
3. **Даты**: `<поле calendar="gregorian"><year>2023</year><month>02</month><day>14</day></поле>` (возможны частичные: только `year`, или `year`+`month`) либо текстом `2023-02-14`.
4. **Табличные поля** (`type="2"`): строки через `\n`, колонки через `::`. У поля `цитаты` — 2 колонки (`страница::Цитата`); значение может содержать 2–4 таблицы в одном контейнере.
5. **Булевы** пишутся только как `true`; отсутствие = false.
6. **`<id>`** внутри `<entry>` — служебный id записи Tellico (в `<fields>` это поле `name="id" title="Номер"`), в данные не превращается.
7. **`<cover>`** — голое имя файла обложки; сам файл лежит в `images/` (в других раскладках — `<collection>_files/`). В `<images>` — `<image id="имя-файла" format="jpeg"/>`, `width`/`height` в DTD `#IMPLIED`.
8. **`<field>` требует `category`** (`#REQUIRED`). Категории коллекции пользователя: `Общее`, `Покупка`, `Личное`, `Издательство`, `Классификация`, `Аннотация`, `Рецензия`, `Цитаты`, `Лицевая сторона обложки`, `Содержание`.
9. **Флаги**: бит `1` = AllowMultiple (контейнер), бит `2` = BasicField (скрытое), бит `4` = CompleteField, бит `16` = ReadOnly. Проверено: `author`/`genre`/`translator`/`keyword`/`cr_year`/`цитаты` имеют бит 1 и пишутся контейнерами.
10. **Типы**: `1` текст, `2` таблица/форматированный, `3` список, `4` флажок, `6` число, `7` URL, `10` картинка, `12` дата, `14` рейтинг (с пропами `maximum`/`minimum`).

## Решения, принятые при написании плана (спека молчит или противоречит)

- **Только `type="2"`** (Книги). Библиография (`type="1"`) не принимается: у неё другой набор встроенных имён (`year` вместо `pub_year`). Ошибка — «Это не коллекция книг». (§9 спеки говорит «не 1 и не 2» — §5.4 подробнее, он и подтверждён «builder check».)
- **Имя файла фото = MD5 от содержимого в обоих форматах** (`photos/{md5}.{ext}`), хотя §5.7 в примере JSON показывает `620_77.jpg`. Причина: §7 требует, чтобы повторный импорт не плодил дубликаты, а это возможно только при устойчивом имени; id строки БД при импорте меняется.
- **`books.csv` получает ещё три колонки** сверх `id` из §5.7: `purchase_date`, `purchase_price`, `source`. Без них бэкап теряет реальные данные пользователя (дату и цену покупки), а требование «без потерь» — пользовательское.
- **Локальное имя файла фото в приложении** — `{md5}_{bookId}.{ext}`: тот же файл у двух книг не удаляется вместе с одной из них.
- **`ImportTellicoZipUseCase` принимает не только zip**, но и «голый» `.xml`/`.csv` (§5.10 требует автоопределение и для них). Имя класса остаётся прежним — как в спеке.
- **Markdown в `comments`/`рецензия` не конвертируется в HTML** (§5.8) — в Tellico будет видна разметка.
- **Экранирование в ячейках таблиц**: `\` → `\\`, LF → `\n`, CR → `\r`. `::` внутри **средней** колонки не экранируется (в последней колонке не теряется — там «хвост» забирается целиком).
- **Порядок задач**: маппер меняет тип возврата, поэтому импортёры переводятся на снимки **вместе с ним** (Task 6). Апплиер (Task 5) идёт перед ними — он нужен импорту и от маппера не зависит.

## Review Focus

Пять входов/поломок, которые спека подразумевает, но чьи тесты не задаёт, и которые ломаются тихо:

1. **Обложка не показывается в Tellico** — `<image>` пишется без `width`/`height` (в DTD они `#IMPLIED`). Ловится только ручным прогоном в Tellico: юнит-тесты симметричны и ничего не поймают. Тест: `TellicoXmlWriter` пишет `<image id=… format=…/>` ровно с двумя атрибутами.
2. **Повторный импорт `.tc` плодит фото** — дедуп держится на MD5 от содержимого; если имя перестанет быть устойчивым (переименование при сохранении, смена расширения), дубликаты вернутся. Тест: два импорта одного файла не меняют число фото.
3. **Заметка с `::` внутри** ломает разбор таблицы «Заметки» (3 колонки, текст — средняя). Экранируется только `\`, LF, CR. Тест: `::` в **последней** колонке выживает.
4. **Частичная дата** (`2023`, `2023-02`) из `<дата-прочтения>` не теряется, а превращается в 2023-01-01 / 2023-02-01.
5. **Поле «Продано?»**: в XML имя `продано-new`, из CSV-заголовка «Продано?» по правилу Tellico выводится `продано-` → Tellico завёл бы второе поле. Тест: CSV-заголовок «Продано?» отображается в `продано-new`.

---

## Структура файлов

**Новые:**

| Файл | Ответственность |
| ---- | --------------- |
| `data/snapshot/LibrarySnapshot.kt` | Модели снимка: `BookSnapshot`, `NoteSnapshot`, `SessionSnapshot`, `PhotoSnapshot`, `ExtraField` |
| `data/snapshot/SnapshotBuilder.kt` | Собирает `List<BookSnapshot>` из БД |
| `data/snapshot/SnapshotApplier.kt` | Вливает снимок в БД (слияние без дублей) |
| `data/snapshot/ImportAssets.kt` | Файлы распакованного архива: поиск обложки/фото по имени |
| `data/tellico/TellicoRecord.kt` | Промежуточная модель записи: `fields` + `extra` |
| `data/tellico/TellicoFields.kt` | Словарь полей Tellico: внутреннее имя ↔ русский заголовок, правило вывода имени |
| `data/tellico/TellicoCsvReader.kt` | CSV Tellico → `List<TellicoRecord>` (перенос из `data/importer/`) |
| `data/tellico/TellicoXmlReader.kt` | `tellico.xml` → `List<TellicoRecord>` (DOM, без сети) |
| `data/tellico/TellicoXmlWriter.kt` | Сборка `tellico.xml` по снимкам |
| `data/importer/ImportFormatDetector.kt` | Определение формата файла по содержимому: `ImportFormat` + `detect(file)` |
| `data/exporter/ExportTellicoTcUseCase.kt` | Экспорт `.tc` (zip: `tellico.xml` + `images/` + `photos/`) |

**Изменяемые:**

| Файл | Что меняется |
| ---- | ------------ |
| `data/db/entity/BookEntity.kt` | + колонка `extra_fields` (`Map<String, ExtraField>`) |
| `data/db/converter/Converters.kt` | + конвертер `Map<String, ExtraField>` ⇄ JSON |
| `data/db/KwBooktrackDatabase.kt` | `version = 9` |
| `data/db/migration/Migrate8To9.kt` (новый) | + колонка `extra_fields` |
| `data/di/DatabaseModule.kt` | + `Migrate8To9` в `addMigrations(...)` |
| `data/db/dao/TagDao.kt` | + `tagsForBook(bookId)` |
| `data/db/dao/PhotoDao.kt` | + `observeAll()` |
| `data/db/dao/BookDao.kt` | `mergeWith` сливает `extraFields` (импорт побеждает по ключу) |
| `data/storage/Storages.kt` | `PhotoStorage` + `saveNamed(name, source, ext)` |
| `data/importer/TellicoBookMapper.kt` | `map(record: TellicoRecord): BookSnapshot?` |
| `data/importer/ImportTellicoUseCase.kt` | Принимает `List<TellicoRecord>`; старая перегрузка `invoke(input: Reader)` остаётся |
| `data/importer/ImportTellicoZipUseCase.kt` | Распаковка в staging, автоопределение XML/CSV, фото/сессии/теги |
| `data/importer/ImportKwBooktrackZipUseCase.kt` | Читает `extra.json` + `photos/`, возвращает `ImportTellicoResult` |
| `data/exporter/ExportZipUseCase.kt` | Колонки `purchase_date`/`purchase_price`/`source`/`id`, `extra.json`, `photos/` |
| `ui/importer/TellicoImportScreen.kt` | Кнопка «Выбрать файл», mime-типы, новое описание |
| `ui/importer/TellicoImportViewModel.kt` | Временный файл `import-…`, маршрутизация по формату |
| `ui/about/AboutScreen.kt` | Третья кнопка «Экспорт в Tellico (.tc)» |
| `ui/about/AboutExportViewModel.kt` | `exportTellicoToUri(uri)`, имя файла `.tc` |
| `app/src/main/res/values/strings.xml` | Новые строки |

**Тесты:**

| Файл | Назначение |
| ---- | ---------- |
| `data/snapshot/SnapshotBuilderTest.kt` | Порядок заметок, индексы фото, теги |
| `data/snapshot/SnapshotApplierTest.kt` | Семантика слияния (без дублей) |
| `data/tellico/TellicoCsvReaderTest.kt` | CSV → `TellicoRecord`, скип «Номер», «Продано?» → `продано-new` |
| `data/tellico/TellicoXmlReaderTest.kt` | XML → `TellicoRecord`: контейнеры, таблицы, даты, `extra` |
| `data/importer/TellicoBookMapperTest.kt` (переписан) | `TellicoRecord` → `BookSnapshot` |
| `data/importer/TellicoTcImportTest.kt` | Импорт собранного `.tc` целиком |
| `data/importer/ImportFormatDetectorTest.kt` | Определение формата по содержимому |
| `data/exporter/TellicoTcRoundTripTest.kt` | Экспорт `.tc` → импорт → всё на месте |
| `data/exporter/KwBooktrackZipRoundTripTest.kt` | Экспорт `books.csv` → импорт → всё на месте |
| `data/db/migration/Migrate8To9Test.kt` | Миграция 8→9 |
| `ui/importer/TellicoImportViewModelTest.kt`, `ui/about/AboutExportViewModelTest.kt` | UI-слой |

---

### Task 1: Снимок библиотеки — модель и сборщик

**Files:**
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/snapshot/LibrarySnapshot.kt`
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/snapshot/SnapshotBuilder.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/dao/TagDao.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/dao/PhotoDao.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/snapshot/SnapshotBuilderTest.kt`

**Interfaces:**
- Consumes: `BookEntity`, `NoteEntity`, `ReadingSessionEntity`, `PhotoEntity`, `TagEntity`, `NoteKind`, `BookRepository`, `NoteDao`, `ReadingSessionDao`, `PhotoDao`, `TagDao`.
- Produces:
  ```kotlin
  @Serializable
  data class ExtraField(
      @SerialName("t") val title: String,
      @SerialName("v") val value: String,
  )

  data class NoteSnapshot(val kind: NoteKind, val body: String, val page: Int? = null, val createdAt: Long? = null)
  data class SessionSnapshot(val startedAt: Long, val endedAt: Long? = null, val startPage: Int? = null,
                             val endPage: Int? = null, val durationSec: Long? = null, val note: String? = null)
  data class PhotoSnapshot(val archivePath: String, val localPath: String = archivePath,
                           val noteIndex: Int? = null, val createdAt: Long? = null)
  data class BookSnapshot(val book: BookEntity,
                           val notes: List<NoteSnapshot> = emptyList(),
                           val sessions: List<SessionSnapshot> = emptyList(),
                           val tags: List<String> = emptyList(),
                           val photos: List<PhotoSnapshot> = emptyList(),
                           val extraFields: Map<String, ExtraField> = emptyMap())
  ```
  `archivePath` — путь к фото внутри архива (`photos/{md5}.{ext}`), устойчив между экспортом и импортом; `localPath` — путь к файлу в приложении (при импорте вычисляется апплиером).

  ```kotlin
  @Singleton
  class SnapshotBuilder @Inject constructor(
      private val bookRepository: BookRepository,
      private val noteDao: NoteDao,
      private val sessionDao: ReadingSessionDao,
      private val photoDao: PhotoDao,
      private val tagDao: TagDao,
  ) {
      suspend fun buildAll(): List<BookSnapshot>
  }
  ```
- `TagDao`: + `suspend fun tagsForBook(bookId: Long): List<TagEntity>` — `SELECT t.* FROM tags t JOIN book_tags bt ON bt.tag_id = t.id WHERE bt.book_id = :bookId ORDER BY t.name`.
- `PhotoDao`: + `fun observeAll(): Flow<List<PhotoEntity>>` — `SELECT * FROM photos ORDER BY created_at DESC`.

- [ ] **Step 1: Написать падающий тест `SnapshotBuilderTest`**

В БД (in-memory, `TestDb.createInMemory`) — книга с 4 заметками (по одной `QUOTE`, `REVIEW`, `NOTE`, ещё одной `NOTE` с большим `createdAt`), одной сессией, одним тегом, одним фото, привязанным к заметке `NOTE`.

Assertions:
- `buildAll()` возвращает 1 снимок; `snapshot.notes` содержит 4 заметки в порядке `QUOTE` → `REVIEW` → `NOTE` → `NOTE`;
- внутри групп порядок по `(createdAt ASC, id ASC)`: вторая `NOTE` (с большим `createdAt`) идёт после первой;
- `snapshot.tags == ["фэнтези"]`;
- `snapshot.sessions` содержит 1 сессию с её `startedAt`;
- `snapshot.photos` содержит 1 фото с непустым `archivePath`, заканчивающимся на расширение файла, и `noteIndex` = 1-based индекс заметки `NOTE` в `snapshot.notes`;
- `snapshot.extraFields` пуст (колонка появится в Task 2 — **до Task 2 этот ассерт не ставить**).

- [ ] **Step 2: Запустить тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "*SnapshotBuilderTest*"`
Expected: FAIL — «Unresolved reference: SnapshotBuilder».

- [ ] **Step 3: Реализовать `LibrarySnapshot.kt` и `SnapshotBuilder.kt`**

`LibrarySnapshot.kt` — ровно модели из блока Interfaces.

`SnapshotBuilder.buildAll()`:
1. `bookRepository.observeAll().first()` — книги.
2. `noteDao.observeAll().first()` → сгруппировать по `bookId` → для каждой книги отсортировать: сначала по рангу вида (`QUOTE`=0, `REVIEW`=1, `NOTE`=2), затем по `(createdAt ASC, id ASC)`.
3. `sessionDao.observeAll().first()` → сгруппировать по `bookId` → `SessionSnapshot` из полей `ReadingSessionEntity` (без `id`/`bookId`).
4. `photoDao.observeAll().first()` → сгруппировать по `bookId` → `PhotoSnapshot`:
   - `archivePath` = `"photos/" + <md5 от содержимого файла> + "." + <расширение>`; md5 считать из байтов файла `photo.path` (файла нет → `archivePath` = `photos/{photo.id}.{ext}`, чтобы не ронять экспорт);
   - `localPath` = `photo.path`;
   - `noteIndex` = 1-based индекс заметки с `id == photo.noteId` в отсортированном списке заметок книги; если заметки нет — `null`.
5. `tagDao.tagsForBook(bookId)` → `List<String>` (имена тегов).
6. `BookSnapshot(book, notes, sessions, tags, photos, extraFields = emptyMap())` — в Task 2 заменить на `book.extraFields`.

- [ ] **Step 4: Запустить тест — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "*SnapshotBuilderTest*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
git add app/src/main/kotlin/ru/antonkw/kwbooktrack/data/snapshot \
        app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/dao/TagDao.kt \
        app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/dao/PhotoDao.kt \
        app/src/test/kotlin/ru/antonkw/kwbooktrack/data/snapshot/SnapshotBuilderTest.kt \
        app/build.gradle.kts
# в app/build.gradle.kts: versionCode = 44, versionName = "0.5.36"
git commit -m "v 0.5.36 — снимок библиотеки: модель и сборщик"
```

---

### Task 2: Хранение чужих полей Tellico — миграция БД 8→9

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/entity/BookEntity.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/converter/Converters.kt`
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/migration/Migrate8To9.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/KwBooktrackDatabase.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/di/DatabaseModule.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/snapshot/SnapshotBuilder.kt` (одна строка)
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/db/migration/Migrate8To9Test.kt`

**Interfaces:**
- Consumes: `ExtraField` из Task 1.
- Produces:
  - `BookEntity` получает поле `@ColumnInfo(name = "extra_fields") val extraFields: Map<String, ExtraField> = emptyMap()`.
  - `Converters` получает пару:
    ```kotlin
    @TypeConverter fun fromExtraFields(value: Map<String, ExtraField>): String
    @TypeConverter fun toExtraFields(value: String): Map<String, ExtraField>
    ```
    JSON: `{"<name>":{"t":"<title>","v":"<value>"}}`; пустая карта → `"{}"`.
  - `Migrate8To9: Migration` (8 → 9), `DatabaseModule` регистрирует его.

- [ ] **Step 1: Написать падающий тест `Migrate8To9Test`**

По образцу `Migrate7To8Test`: открыть raw `SupportSQLiteOpenHelper` на версии 8 со схемой `books` (колонки v8 как в `Migrate7To8.kt`, но `print_pages` уже REAL и **без** `extra_fields`), вставить строку книги, выполнить `Migrate8To9.migrate(db)`, прочитать обратно.

Assertions:
- в `PRAGMA table_info(books)` есть колонка `extra_fields` типа `TEXT`;
- старая строка книги на месте, её `extra_fields` == `"{}"`.

- [ ] **Step 2: Запустить тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "*Migrate8To9Test*"`
Expected: FAIL — «Unresolved reference: Migrate8To9».

- [ ] **Step 3: Реализовать миграцию, конвертер и поле сущности**

`Migrate8To9`:
```sql
ALTER TABLE books ADD COLUMN extra_fields TEXT NOT NULL DEFAULT '{}'
```
(колонка добавляется — таблица не пересоздаётся, каскады не затрагиваются).

`Converters`:
```kotlin
private val EXTRA_JSON = Json { ignoreUnknownKeys = true }

@TypeConverter
fun fromExtraFields(value: Map<String, ExtraField>): String =
    if (value.isEmpty()) "{}" else EXTRA_JSON.encodeToString(value)

@TypeConverter
fun toExtraFields(value: String): Map<String, ExtraField> =
    if (value.isBlank() || value == "{}") emptyMap()
    else runCatching { EXTRA_JSON.decodeFromString<Map<String, ExtraField>>(value) }.getOrDefault(emptyMap())
```
`ExtraField` помечена `@Serializable` с `@SerialName("t")`/`@SerialName("v")` — результат `{"<name>":{"t":…,"v":…}}` ✓.

`BookEntity`: поле `extraFields` в конце списка (после `isSold`), со значением по умолчанию `emptyMap()`.

`SnapshotBuilder`: `extraFields = emptyMap()` → `extraFields = book.extraFields`.

`KwBooktrackDatabase`: `version = 9`.
`DatabaseModule`: `.addMigrations(…, Migrate8To9)`.

- [ ] **Step 4: Запустить тест — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "*Migrate8To9Test*" --tests "*SnapshotBuilderTest*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
# versionCode = 46, versionName = "0.5.38"
git commit -m "v 0.5.37 — чужие поля Tellico в books.extra_fields, миграция 8→9"
```

---

### Task 3: Промежуточная модель Tellico и CSV-ридер

**Files:**
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/tellico/TellicoRecord.kt`
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/tellico/TellicoFields.kt`
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/tellico/TellicoCsvReader.kt` (перенос из `data/importer/`)
- Delete: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/importer/TellicoCsvReader.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/tellico/TellicoCsvReaderTest.kt`

**Interfaces:**
- Consumes: `ExtraField` из Task 1.
- Produces:
  ```kotlin
  data class TellicoRecord(
      val fields: Map<String, String> = emptyMap(),   // внутреннее имя поля → значение
      val extra: Map<String, ExtraField> = emptyMap(),// чужие поля: имя → (title, value)
  )

  object TellicoFields {
      val TITLE: Map<String, String>         // внутреннее имя → русский заголовок
      val NAME_BY_TITLE: Map<String, String> // русский заголовок → внутреннее имя
      val KNOWN: Set<String>                 // = TITLE.keys
      val SKIP_TITLES: Set<String>           // = setOf("Номер") — служебный id записи Tellico
      fun nameFromTitle(title: String): String
  }

  class TellicoCsvReader @Inject constructor() {
      fun readRecords(reader: Reader): List<TellicoRecord>
  }
  ```
  В Task 3 рядом остаётся **старая** `fun read(reader: Reader): List<CSVRecord>` (её используют существующие тесты и импортёры); в Task 6 старая удаляется, а `readRecords` переименовывается в `read`.

- [ ] **Step 1: Написать падающий тест `TellicoCsvReaderTest`**

Чистый JUnit (без Robolectric — CSV не трогает Android). Хелпер `csvRecord(...)` — тот же, что сейчас в `TellicoBookMapperTest` (заголовки и значения через `;`).

Cases:
- Заголовок «Заголовок» → `fields["title"] == "Властелин Колец"`.
- Заголовок «Автор» → `fields["author"]`.
- Заголовок «Продано?» → `fields["продано-new"]` (**Review Focus #5**).
- Заголовок «ISBN#» → `fields["isbn"]`.
- Заголовок «Усл. печ. л.» → `fields["усл.-печ.-л."]`.
- Заголовок «Чтец» (нет в словаре) → `extra["чтец"] == ExtraField(title = "Чтец", value = "Иванова")`.
- Заголовок «livelib/fantlab» → `extra["livelib-fantlab"]` (имя выведено по правилу Tellico; расхождение с XML-именем `livelibfantlab` — известная потеря из §8 спеки).
- Заголовок «Номер» → **пропущен**: ни в `fields`, ни в `extra`.
- Пустая ячейка → ключа нет в `fields` (пустое значение не пишется).

- [ ] **Step 2: Запустить тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "*TellicoCsvReaderTest*"`
Expected: FAIL — «Unresolved reference: TellicoCsvReader» в пакете `data.tellico`.

- [ ] **Step 3: Реализовать `TellicoRecord`, `TellicoFields`, `TellicoCsvReader`**

`TellicoFields.TITLE` — полный словарь (внутреннее имя → русский заголовок):

встроенные: `title`→«Заголовок», `subtitle`→«Подзаголовок», `author`→«Автор», `publisher`→«Издательство», `pub_year`→«Год издания», `cr_year`→«Год сдачи в печать», `isbn`→«ISBN#», `pages`→«Количество страниц», `language`→«Язык», `genre`→«Жанр», `keyword`→«Ключевые слова», `series`→«Серия», `series_num`→«Номер серии», `condition`→«Состояние», `translator`→«Переводчик», `binding`→«Переплёт», `pur_price`→«Цена покупки», `pur_date`→«Дата покупки», `read`→«Прочитано», `gift`→«Подарок», `loaned`→«Одолжено», `rating`→«Рейтинг», `cover`→«Лицевая сторона обложки», `comments`→«Аннотация», `cdate`→«Создано», `mdate`→«Изменено»;
приложение: `место-покупки`→«Место покупки», `продано-new`→«Продано?», `дата-прочтения`→«Дата прочтения», `дата-начала`→«Дата начала», `прочитал-часть`→«Прочитал часть», `брошено`→«Брошено», `усл.-печ.-л.`→«Усл. печ. л.», `язык-оригинала`→«Язык оригинала», `оригинальное-название`→«Оригинальное название», `цитаты`→«Цитаты», `рецензия`→«Рецензия», `заметки`→«Заметки», `теги`→«Теги», `сессии-чтения`→«Сессии чтения», `фото-заметок`→«Фото заметок».

`nameFromTitle(title)`: `title.lowercase()`, каждый символ, который не `isLetterOrDigit()` и не `-` и не `.`, заменить на `-`, затем схлопывание серий `-` в одну и обрезка `-` по краям. **Точка сохраняется**: в реальной коллекции Tellico поле называется `усл.-печ.-л.`, и имя с точкой совпадает с существующим полем (иначе Tellico завёл бы дубликат). («ISBN#» → «isbn», «livelib/fantlab» → «livelib-fantlab», «Продано?» → «продано», «Усл. печ. l.» → «усл.-печ.-л.»; «Продано?» есть в словаре → «продано-new» ✓.)

`TellicoCsvReader.readRecords(reader)`: использует текущий `CSVFormat` (разделитель `;`, кавычки `"`, перевод строки `\n`, `setHeader()`), но вместо `CSVRecord` собирает `TellicoRecord`:
- для каждого заголовка: если он в `SKIP_TITLES` — пропустить;
- `name = NAME_BY_TITLE[header] ?: nameFromTitle(header)`;
- значение = ячейка, обрезанная по краям; пустое — пропустить;
- если `name` в `KNOWN` → `fields[name] = value`; иначе → `extra[name] = ExtraField(title = header, value = value)`.

- [ ] **Step 4: Запустить тест — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "*TellicoCsvReaderTest*"`
Expected: PASS.

- [ ] **Step 5: Поправить ссылки на старый пакет**

`ImportTellicoUseCase` и `ImportTellicoZipUseCase` импортируют `ru.antonkw.kwbooktrack.data.importer.TellicoCsvReader` → заменить на `ru.antonkw.kwbooktrack.data.tellico.TellicoCsvReader`. Файлы тестов, использующие `TellicoCsvReader` без явного импорта (они в пакете `data.importer`) — добавить импорт `data.tellico.TellicoCsvReader`.

Старая функция `read(reader): List<CSVRecord>` остаётся: маппер до Task 6 принимает `CSVRecord`, и переводить его раньше нельзя.

Run: `./gradlew compileDebugKotlin testDebugUnitTest` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 6: Коммит**

```bash
# versionCode = 47, versionName = "0.5.39"
git commit -m "v 0.5.38 — промежуточная модель Tellico и словарь полей, CSV-ридер"
```

---

### Task 4: XML-ридер Tellico

**Files:**
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/tellico/TellicoXmlReader.kt`
- Modify: `.gitignore` (см. первый шаг ниже)
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/tellico/TellicoXmlReaderTest.kt`

**Interfaces:**
- Consumes: `TellicoRecord`, `TellicoFields` из Task 3.
- Produces:
  ```kotlin
  class TellicoXmlReader @Inject constructor() {
      fun read(bytes: ByteArray): List<TellicoRecord>
      fun read(file: File): List<TellicoRecord>
  }
  ```
  Бросает `IllegalStateException("Это не коллекция книг")`, если в файле нет коллекции с `type="2"`; при невалидном XML — `IllegalStateException` с причиной парсера.

- [ ] **Step 1: Поправить `.gitignore` — новый пакет `data/tellico/` не должен игнорироваться**

В `.gitignore` есть паттерн `tellico/` (без ведущего слэша) — он совпадает с **любым** каталогом `tellico` на любой глубине, включая новый пакет `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/tellico/`. Из-за этого файлы пакета приходится добавлять через `git add -f`, а обычный `git add .` их молча пропустит.

За anchors: `tellico/` → `/tellico/` (каталог с данными пользователя лежит в корне репозитория). Если нужно оставить игнорирование фикстур — добавь отдельную строку `app/src/test/resources/fixtures/tellico/`.

Проверка: `git check-ignore -v app/src/main/kotlin/ru/antonkw/kwbooktrack/data/tellico/TellicoXmlReader.kt` должен **не** найти совпадения.

- [ ] **Step 2: Написать падающий тест `TellicoXmlReaderTest`**

Чистый JUnit (DOM-парсер — чистый JVM). Фикстура — короткий XML-строка.

Cases:
- **Контейнер:** `<authors><author>А</author><author>Б</author></authors>` → `fields["author"] == "А; Б"`.
- **Таблица в контейнере:** `<цитатыs><цитаты>1::первая\n2::вторая</цитаты><цитаты>3::третья</цитаты></цитатыs>` при объявленном `<field name="цитаты" type="2"><prop name="columns">2</prop></field>` → `fields["цитаты"] == "1::первая\n2::вторая\n3::третья"` (строки обеих таблиц, разделитель `\n`).
- **Плоская форма:** `<publisher>Litres</publisher>` → `fields["publisher"] == "Litres"`.
- **Дата:** `<дата-прочтения calendar="gregorian"><year>2023</year><month>02</month><day>14</day></дата-прочтения>` → `fields["дата-прочтения"] == "2023-02-14"`.
- **Частичная дата:** только `<year>2023</year>` → `"2023"` (**Review Focus #4**).
- **Пользовательское поле:** `<место-покупки>Лабиринт</место-покупки>` → `fields["место-покупки"] == "Лабиринт"`.
- **Чужое поле:** `<чтец>Иванова</чтец>` (в `<fields>` объявлено как `name="чтец" title="Чтец"`) → `extra["чтец"] == ExtraField("Чтец", "Иванова")`.
- **Служебный `<id>`:** `<id>42</id>` → ни в `fields`, ни в `extra`.
- **Булев:** `<read>true</read>` → `fields["read"] == "true"`.
- **Коллекция не книжная:** `<collection type="3">` → `IllegalStateException` с сообщением «Это не коллекция книг».
- **XXE-защита:** XML с `<!DOCTYPE … [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>` и `&xxe;` в тексте → значение НЕ содержит содержимого `/etc/passwd` (внешние сущности не раскрываются).

- [ ] **Step 3: Запустить тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "*TellicoXmlReaderTest*"`
Expected: FAIL — «Unresolved reference: TellicoXmlReader».

- [ ] **Step 4: Реализовать `TellicoXmlReader`**

Парсер:
```kotlin
val dbf = DocumentBuilderFactory.newInstance()
runCatching { dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false) }
runCatching { dbf.setFeature("http://xml.org/sax/features/external-general-entities", false) }
runCatching { dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
runCatching { dbf.isExpandEntityReferences = false }
runCatching { dbf.setXIncludeAware(false) }
```
`load-external-dtd=false` обязателен: файл Tellico содержит `<!DOCTYPE … 'http://periapsis.org/tellico/dtd/v12/tellico.dtd'>` — без этого парсер полезет в сеть.

Разбор:
1. `document.documentElement` → `tellico`; найти все элементы `collection` (namespace Tellico `http://periapsis.org/tellico/`, поэтому искать по `localName`).
2. Выбрать коллекцию с `type == "2"`; если нет — `IllegalStateException("Это не коллекция книг")`.
3. Из `<fields>` собрать `name → (title, columns)`: `columns` = `<prop name="columns">` (если есть).
4. По элементам `<entry>`:
   - для каждого дочернего элемента:
     - **пропустить** элемент с именем `id` (служебный id записи);
     - определить имя поля и значение:
       - если у элемента есть дочерние элементы и **все** они с одним и тем же именем `C` → это контейнер: имя поля = `C`, значения = тексты детей;
       - иначе → имя поля = имя элемента, значение = текст элемента;
     - если у элемента есть атрибут `calendar` → значение = дата из детей `year`/`month`/`day` → ISO `yyyy` / `yyyy-MM` / `yyyy-MM-dd` (месяц и день двузначные, с ведущими нулями);
     - склеить значения: если поле табличное (`columns > 1`) → `\n`, иначе `; `;
     - если имя поля в `TellicoFields.KNOWN` → `fields[name] = значение`; иначе → `extra[name] = ExtraField(title = title из <fields> или имя, value = значение)`.
   - пустые значения (пустая строка) в `fields` не пишем.

- [ ] **Step 5: Запустить тест — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "*TellicoXmlReaderTest*"`
Expected: PASS.

- [ ] **Step 6: Коммит**

```bash
# versionCode = 48, versionName = "0.5.40"
git commit -m "v 0.5.39 — XML-ридер Tellico: контейнеры, таблицы, даты"
```

---

### Task 5: Слияние снимка в БД

**Files:**
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/snapshot/ImportAssets.kt`
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/snapshot/SnapshotApplier.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/storage/Storages.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/dao/BookDao.kt` (`mergeWith`)
- Modify: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/TestStorages.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/snapshot/SnapshotApplierTest.kt`

**Interfaces:**
- Consumes: `BookSnapshot` и подтипы (Task 1), `BookRepository`, `NoteRepository`, `SessionRepository`, `TagRepository`, `PhotoRepository`, `PhotoStorage`, `CoverTranscoder`.
- Produces:
  ```kotlin
  /** Файлы распакованного архива: обложки и фото. */
  class ImportAssets(val root: File?, val coverOutputDir: File?) {
      /** Найти файл: сначала путь относительно root, затем поиск по имени файла вглубь. */
      fun find(name: String): File?
  }

  data class ApplyStats(val books: Int = 0, val notes: Int = 0, val sessions: Int = 0,
                        val tags: Int = 0, val photos: Int = 0, val covers: Int = 0)

  class SnapshotApplier @Inject constructor(
      private val bookRepository: BookRepository,
      private val noteRepository: NoteRepository,
      private val sessionRepository: SessionRepository,
      private val tagRepository: TagRepository,
      private val photoRepository: PhotoRepository,
      private val photoStorage: PhotoStorage,
      private val transcoder: CoverTranscoder,
  ) {
      suspend fun apply(snapshot: BookSnapshot, assets: ImportAssets): ApplyStats
  }
  ```
- `PhotoStorage` получает метод:
  ```kotlin
  /** Сохранить байты под заданным именем (без расширения) в каталог фото; вернуть абсолютный путь. */
  fun saveNamed(name: String, source: InputStream, extension: String): String
  ```
  `FilePhotoStorage` пишет `filesDir/photos/{name}.{extension}`; `NoOpPhotoStorage` возвращает `"/photos/{name}.{extension}"` (детерминированно — иначе дедуп фото в тестах не работает).
- `BookDao.mergeWith` получает одну строку: `extraFields = existing.extraFields + extraFields` (импорт побеждает по ключу, прежние значения не затираются пустым импортом).

- [ ] **Step 1: Написать падающий тест `SnapshotApplierTest`**

Robolectric, in-memory БД. Строится `BookSnapshot` вручную (книга + 2 заметки + сессия + тег + фото + `extraFields`).

Cases:
- **Книга:** `apply` вставляет книгу; в БД одна книга с правильными `title` и `status`.
- **Заметки без дублей:** применить один и тот же снимок дважды → число заметок в БД не меняется (совпадение по `kind` + `body` + `page`).
- **Сессии без дублей:** дважды → число сессий не меняется (совпадение по `startedAt`).
- **Теги:** дважды → в `book_tags` одна строка, в `tags` одна строка.
- **Фото без дублей** (**Review Focus #2**): дважды → число строк в `photos` не меняется; локальный файл назван `{md5}_{bookId}.{ext}`.
- **Фото привязано к заметке:** `PhotoSnapshot.noteIndex = 2` → `photos.note_id` указывает на вторую заметку снимка.
- **Чужие поля:** `extraFields = mapOf("чтец" to ExtraField("Чтец", "Иванова"))` → в БД `book.extraFields["чтец"]?.value == "Иванова"`; после повторного импорта с другим значением — новое значение, а ключи, которых нет в импорте, сохранены.
- **Обложка:** `snapshot.book.coverPath = "images/abc.webp"`, в staging-каталоге лежит `images/abc.webp` → после `apply` `book.coverPath` указывает на существующий файл в `<coverOutputDir>/covers/`; `coverPath` в БД — **не** имя файла из архива.
- **Без обложки:** `snapshot.book.coverPath = null` → у существующей книги обложка не стирается.

- [ ] **Step 2: Запустить тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "*SnapshotApplierTest*"`
Expected: FAIL — «Unresolved reference: SnapshotApplier».

- [ ] **Step 3: Реализовать `ImportAssets`, `SnapshotApplier`, `saveNamed`, `mergeWith`**

`ImportAssets.find(name)`:
1. `File(root, name)` — если существует и это файл → вернуть;
2. иначе — рекурсивный поиск по `root` файла с basename == `File(name).name` (первый найденный);
3. иначе `null`.

`SnapshotApplier.apply(snapshot, assets)`:
1. `bookId = bookRepository.upsertFromImport(snapshot.book.copy(coverPath = null))` — архивное имя обложки не должно попасть в БД как локальный путь.
2. **Обложка:** если `snapshot.book.coverPath` непустой → `assets.find(coverPath)`; если файл найден → прочитать байты, `transcoder.toWebp(bytes)` (при null — оставить исходные байты и исходное расширение) → записать в `File(assets.coverOutputDir, "covers")/{bookId}.{ext}` → `bookRepository.update(book.copy(id = bookId, coverPath = абсолютный путь))` → `covers++`.
3. **Заметки:** взять существующие заметки книги (`noteRepository.observeByBook(bookId).first()`), для каждой заметки снимка проверить совпадение по (`kind`, `body`, `page`) → если нет, вставить (`noteRepository.insert(NoteEntity(bookId = bookId, page, bodyMd, kind, createdAt = createdAt ?: now, updatedAt = now))`). Собирать `noteIdsInOrder` — id БД для каждой заметки снимка по порядку (вставленный или найденный).
4. **Сессии:** существующие — `sessionRepository.observeByBook(bookId).first()`; вставить, если нет сессии с тем же `startedAt`.
5. **Теги:** для каждого имени — `tagRepository.findOrCreate(name)` → `tagRepository.attachToBook(bookId, tagId)`.
6. **Фото:** существующие фото книги — `photoRepository.observeByBook(bookId).first()` (из них взять `path`); для каждого фото снимка: `archivePath` = `photos/{md5}.{ext}` → `md5` = basename без расширения, `ext` = расширение; локальное имя = `"{md5}_{bookId}"`; `photoStorage.saveNamed("{md5}_{bookId}", bytes, ext)` (байты — из `assets.find(archivePath)`; файла нет — фото пропускается, импорт продолжается); если полученный путь ещё не среди существующих путей книги → вставить `PhotoEntity(bookId, noteId = noteIdsInOrder[noteIndex-1], path, createdAt = createdAt ?: now)`.
7. Вернуть `ApplyStats`.

- [ ] **Step 4: Запустить тест — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "*SnapshotApplierTest*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
# versionCode = 49, versionName = "0.5.41"
git commit -m "v 0.5.40 — слияние снимка в БД без дублей: заметки, сессии, теги, фото"
```

---

### Task 6: Маппер и перевод импорта Tellico на снимки

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/importer/TellicoBookMapper.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/tellico/TellicoCsvReader.kt` (удалить старую `read(): List<CSVRecord>`)
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/importer/ImportFormatDetector.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/importer/ImportTellicoUseCase.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/importer/ImportTellicoZipUseCase.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/importer/TellicoBookMapperTest.kt` (переписать)
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/importer/ImportFormatDetectorTest.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/importer/TellicoTcImportTest.kt`

**Interfaces:**
- Consumes: `TellicoRecord`, `TellicoFields`, `TellicoCsvReader`, `TellicoXmlReader` (Tasks 3–4), `SnapshotApplier`, `ImportAssets`, `BookSnapshot` и подтипы (Tasks 1, 5).
- Produces:
  ```kotlin
  class TellicoBookMapper @Inject constructor() {
      fun map(record: TellicoRecord): BookSnapshot?
  }
  ```
  Старый data-класс `MappedBook` удаляется.

  ```kotlin
  enum class ImportFormat { KWBOOKTRACK_ZIP, TELLICO_XML, TELLICO_CSV }

  object ImportFormatDetector {
      /** Определить формат по содержимому файла. Бросает IllegalStateException с понятным сообщением. */
      fun detect(file: File): ImportFormat
  }
  ```
  Правила: сигнатура `PK` → ZIP; в ZIP наличие `manifest.json` → `KWBOOKTRACK_ZIP`, иначе `tellico.xml` → `TELLICO_XML`, иначе любой `*.csv` → `TELLICO_CSV`, иначе `IllegalStateException("в архиве нет ни tellico.xml, ни CSV")`. Не-ZIP: первые 4 КБ содержат `<tellico` или `<collection` → `TELLICO_XML`, иначе `TELLICO_CSV`.

  ```kotlin
  class ImportTellicoZipUseCase @Inject constructor(
      private val csvReader: TellicoCsvReader,
      private val xmlReader: TellicoXmlReader,
      private val mapper: TellicoBookMapper,
      private val applier: SnapshotApplier,
  ) {
      suspend operator fun invoke(
          file: File,
          format: ImportFormat,
          outputDir: File,
          dryRun: Boolean = false,
      ): ImportTellicoResult
  }
  ```
  `ImportTellicoOptions` переименовать поля: `coverSourceDir` → `assetRoot: File?`; `coverOutputDir: File?` и `dryRun: Boolean` остаются.
  `ImportTellicoResult` (в `data/importer/ImportTellicoUseCase.kt`) расширяется полями `errors: List<Throwable> = emptyList()`, `val error: Throwable?`, `val isSuccess: Boolean`.

- [ ] **Step 1: Переписать `TellicoBookMapperTest` под `TellicoRecord`**

Хелпер:
```kotlin
private fun record(vararg pairs: Pair<String, String>) =
    TellicoRecord(fields = pairs.toMap())
```
Ключи — **внутренние имена Tellico** (`"title"`, `"author"`, `"isbn"`, …).

Cases (смысл существующих тестов сохранён, ключи переименованы):
- «Заголовок/Автор» → `book.title`, `book.author`.
- ISBN короче 13 цифр → `book.isbn13 == null`.
- Рейтинг вне 1..5 → `book.rating == null`.
- Расширенные поля (`"binding"`, `"место-покупки"`, `"series"`, `"series_num"`, `"condition"`, `"translator"`, `"gift"`, `"loaned"`, `"продано-new"`, `"comments"`, `"дата-прочтения"`, `"усл.-печ.-л."`, `"язык-оригинала"`, `"оригинальное-название"`, `"cr_year"`).
- HTML → markdown: `fields["comments"] = "<p>Пустыня и <b>песок</b>&nbsp;везде.</p><p>Вторая.</p>"` → `book.annotation == "Пустыня и **песок** везде.\n\nВторая."`.
- **Статус:** `fields["брошено"] = "true"` → `ABANDONED`; `fields["read"] = "true"` → `FINISHED`; `fields["прочитал-часть"] = "true"` → `READING`; при конфликте `брошено` + `read` → `ABANDONED` (приоритет: брошено → прочитано → прочитал часть).
- **Цитаты:** `fields["цитаты"] = "1::первая\n3::вторая\n4"` → две заметки `QUOTE`: `("первая", page=1)`, `("вторая", page=3)`; строка `4` (только цифры) пропущена.
- **Цитата без страницы:** `fields["цитаты"] = "просто текст"` → одна заметка `QUOTE` с `page == null`.
- **Цитата в формате CSV** `1:текст` → `page == 1`, `body == "текст"`.
- **Рецензия:** `fields["рецензия"] = "<p>Отличная <i>книга</i>.</p>"` → одна заметка `REVIEW` с `body == "Отличная *книга*."`.
- **Заметки:** `fields["заметки"] = "12::мысль::2024-03-01"` → заметка `NOTE` с `page == 12`, `body == "мысль"`, `createdAt` = epoch полуноти 2024-03-01.
- **Значение с `::` в последней колонке** (**Review Focus #3**): `fields["цитаты"] = "1::хвост с :: внутри"` → текст цитаты == `хвост с :: внутри` (последняя колонка забирает остаток).
- **Частичная дата** (**Review Focus #4**): `fields["дата-прочтения"] = "2023"` → `book.finishedAt` == epoch 2023-01-01; `"2023-02"` → 2023-02-01.
- **Дата в ISO:** `fields["pur_date"] = "2023-08-04"` → `book.purchaseDate`.
- **Сессии:** `fields["сессии-чтения"] = "1730000000000::1730000600000::1::20::600::читал"` → одна `SessionSnapshot(startedAt=1730000000000, endedAt=1730000600000, startPage=1, endPage=20, durationSec=600, note="читал")`.
- **Теги:** `fields["теги"] = "фэнтези; классика"` → `snapshot.tags == ["фэнтези", "классика"]`.
- **Фото:** `fields["фото-заметок"] = "photos/abc.jpg::2::1730000000000"` → `snapshot.photos` содержит `PhotoSnapshot(archivePath = "photos/abc.jpg", noteIndex = 2, createdAt = 1730000000000)`.
- **Чужие поля:** `TellicoRecord(fields = mapOf("title" to "X"), extra = mapOf("чтец" to ExtraField("Чтец", "Иванова")))` → `snapshot.extraFields == mapOf("чтец" to ExtraField("Чтец", "Иванова"))`.
- Пустые название и автор → `map()` возвращает `null`.

- [ ] **Step 2: Запустить тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "*TellicoBookMapperTest*"`
Expected: FAIL — «Unresolved reference: map(TellicoRecord)» / несовпадение типов.

- [ ] **Step 3: Реализовать маппер**

Точка входа: `fun map(record: TellicoRecord): BookSnapshot?`.

- Аксессор: `private fun f(record: TellicoRecord, name: String): String? = record.fields[name]?.trim()?.takeUnless { it.isBlank() }`.
- `title = f(record, "title")?.takeUnless { it.isEmpty() } ?: "(без названия)"`, `author = f(record, "author")?.takeUnless { it.isEmpty() } ?: "(автор неизвестен)"`; если обе пустые → `null` (заглушки — прежнее поведение маппера, пустой заголовок в списке библиотеки недопустим).
- Поля `BookEntity` — те же, что сейчас, но читаются по внутренним именам:
  `subtitle`←`subtitle`, `isbn13`←`isbn` (нормализация: только цифры, 13 → иначе null), `pageCount`←`pages`, `language`←`language` (нормализация: `ru`/`русский`→`ru`, `en`/`английский`→`en`, иначе первые 2 буквы; пусто → `"ru"`), `genre`←`genre`, `publisher`←`publisher`, `year`←`pub_year`, `coverPath`←`cover`, `rating`←`rating` (1..5), `binding`←`binding`, `purchasePlace`←`место-покупки`, `series`←`series`, `seriesNumber`←`series_num`, `condition`←`condition`, `translator`←`translator`, `printYear`←`cr_year` (первое число из `; `-списка), `printPages`←`усл.-печ.-л.` (через `parseDecimal`), `originalLanguage`←`язык-оригинала`, `originalTitle`←`оригинальное-название`, `annotation`←`comments` (через `HtmlToMarkdown`), `isGift`←`gift`, `isLent`←`loaned`, `isSold`←`продано-new`, `purchasePrice`←`pur_price` (запятая → точка), `purchaseDate`←`pur_date`, `finishedAt`←`дата-прочтения`, `startedAt`←`дата-начала`, `createdAt`←`cdate`, `updatedAt`←`mdate`.
- Даты: `parseDate(raw)`: пробует `yyyy-MM-dd`, `yyyy-MM`, `yyyy`, `dd.MM.yyyy`; результат — `LocalDate.toEpochMs()` (неполная дата дополняется 1-м числом/январём).
- Статус: `when { f("брошено") == "true" → ABANDONED; f("read") == "true" → FINISHED; f("прочитал-часть") == "true" → READING; else → WISHLIST }`. Булево читается как `true`/`да`/`yes`/`1` (без учёта регистра).
- `source = BookSource.TELLICO`.
- Заметки (порядок групп QUOTE → REVIEW → NOTE):
  - `цитаты`: строки по `\n`; строка из одних цифр — пропуск; префикс-страница: если строка начинается с цифр и следом `:` или `::` → `page` = цифры, текст = остаток (для `::` убрать второе двоеточие); иначе `page = null`, текст = вся строка. Текст обрезать по краям.
  - `рецензия`: одна заметка `REVIEW`, `body` = `HtmlToMarkdown.convert(value)`, если не пусто.
  - `заметки`: строки по `\n`; строка делится по `::` максимум на 3 части (последняя забирает остаток): `страница`, `текст`, `дата`; `page` = число из первой части (или null), `createdAt` = дата из третьей части (или null). Экранирование: `\` → `\\`, LF → `\n`, CR → `\r` при записи; при чтении — развернуть.
- Сессии: `сессии-чтения` — строки по `\n`, каждая делится по `::` максимум на 6 частей: `начало`, `конец`, `стр.начала`, `стр.конца`, `длительность`, `заметка`; числа — `toLongOrNull()`/`toIntOrNull()`, `startedAt`/`endedAt`/`durationSec` — epoch millis напрямую.
- Теги: `теги` — делить по `"; "`, trim, отбросить пустые.
- Фото: `фото-заметок` — строки по `\n`, каждая делится по `::` максимум на 3 части: `путь`, `индекс`, `дата`; `noteIndex` = число из второй части (или null), `createdAt` = число из третьей (или null).
- `extraFields = record.extra`.

- [ ] **Step 4: Написать падающий тест `ImportFormatDetectorTest`**

Чистый JUnit. Собираются во временном каталоге:
- zip с `manifest.json` + `books.csv` → `KWBOOKTRACK_ZIP`;
- zip с `tellico.xml` → `TELLICO_XML`;
- zip с `tellico/tellico.csv` → `TELLICO_CSV`;
- zip без ничего → `IllegalStateException` с сообщением, содержащим «tellico.xml»;
- файл `<?xml …><tellico>…` → `TELLICO_XML`;
- файл с `Заголовок;Автор` → `TELLICO_CSV`.

- [ ] **Step 5: Реализовать `ImportFormatDetector`**

Чистый JVM: `java.util.zip.ZipFile(file)` для списка записей; первые 4 КБ файла — через `file.inputStream()`.

- [ ] **Step 6: Написать падающий тест `TellicoTcImportTest`**

Robolectric. Собирается `.tc` во временном файле: zip с `tellico.xml` (одна книга) + `images/{md5}.webp` (байты картинки) + `photos/{md5}.jpg`.

В `tellico.xml` книга содержит: `<title>`, `<authors><author>…</author></authors>`, `<read>true</read>`, `<cover>{md5}.webp</cover>`, `<цитатыs><цитаты>1::цитата</цитаты></цитатыs>`, `<рецензия>Отзыв</рецензия>`, `<тегиs><теги>фэнтези</теги></тегиs>`, `<сессии-чтенияs><сессии-чтения>1730000000000::1730000600000::1::20::600::читал</сессии-чтения></сессии-чтенияs>`, `<фото-заметокs><фото-заметок>photos/{md5}.jpg::1::1730000000000</фото-заметок></фото-заметокs>`, `<чтец>Иванова</чтец>`.

Assertions после `invoke(file, ImportFormat.TELLICO_XML, outputDir)`:
- `result.imported == 1`;
- в БД 1 книга с правильными `title`, `author`, `status == FINISHED`;
- 1 заметка `QUOTE` с `page == 1` и телом «цитата»;
- 1 заметка `REVIEW` с телом «Отзыв»;
- 1 сессия с `startedAt == 1730000000000`;
- 1 тег «фэнтези»;
- 1 фото, привязанное к заметке `QUOTE` (`noteId` == id заметки `QUOTE`), и файл фото существует на диске;
- `book.extraFields["чтец"]?.value == "Иванова"`;
- файл обложки существует, `book.coverPath` указывает на файл в `<outputDir>/covers/`.

- [ ] **Step 7: Перевести импортёров на снимки**

> **Перенесено из ревью задачи 3 (обязательно к выполнению здесь, `readRecords` становится боевым путём):**
> - `TellicoCsvReader`: строка CSV короче заголовка не должна ронять импорт — commons-csv `CSVRecord.get(header)` при нехватке значений бросает `IllegalArgumentException`, а не возвращает null. Значение брать по индексу с проверкой `index < record.size()`, иначе пустая строка.
> - `TellicoFields.nameFromTitle`: если выведенное имя пустое (заголовок из одних знаков препинания) — **пропускать колонку**, а не класть её в `extra[""]`.
> - Тест-хелпер построения CSV в `TellicoCsvReaderTest` должен экранировать внутренние кавычки (`"` → `""`) — иначе следующий расширяющий тест получит молчаливо битый CSV.

`TellicoCsvReader`: удалить `read(reader): List<CSVRecord>`, переименовать `readRecords` → `read`.

`ImportTellicoUseCase`:
- конструктор: `csvReader`, `mapper`, `applier` (вместо `bookRepository`/`noteRepository`/`transcoder`);
- `suspend operator fun invoke(records: List<TellicoRecord>, options: ImportTellicoOptions): ImportTellicoResult` — маппит каждый `TellicoRecord` в снимок и передаёт в `applier.apply(snapshot, ImportAssets(options.assetRoot, options.coverOutputDir))`;
- `suspend operator fun invoke(input: Reader, options: ImportTellicoOptions): ImportTellicoResult` — тонкая обёртка: `csvReader.read(input)` → предыдущая перегрузка (её используют существующие тесты);
- удалить прежнюю логику `persistCover`/`persistNotes` — её делает апплиер;
- `ImportTellicoOptions`: `coverSourceDir` → `assetRoot`.

`ImportTellicoZipUseCase`:
1. `staging = File(outputDir, ".import-staging")` — удалить перед распаковкой, распаковать туда весь zip (пути сохранять); для не-zip файла корень = его родительский каталог.
2. По `format`:
   - `TELLICO_XML` → найти `tellico.xml` в staging (по имени файла, без учёта вложенности) → `xmlReader.read(bytes)`;
   - `TELLICO_CSV` → найти первый `*.csv` → `csvReader.read(reader)`.
3. `snapshots = records.mapNotNull { mapper.map(it) }`.
4. `assets = ImportAssets(staging, outputDir)`.
5. Для каждого снимка: `applier.apply(snapshot, assets)`; при `dryRun` — не применять, только посчитать.
6. Собрать `ImportTellicoResult(total = records.size, imported = применённых, skipped = записей без снимка, coversCopied = сумма covers, notesImported = notes + sessions + tags + photos, dryRun)`.
7. В `finally` — удалить staging.

- [ ] **Step 8: Запустить тесты — должны пройти**

Run: `./gradlew testDebugUnitTest`
Expected: PASS. Поправить при этом:
- `TellicoImportIntegrationTest` — конструктор `ImportTellicoUseCase` теперь `(csvReader, mapper, applier)`; поле опции `coverSourceDir` → `assetRoot`;
- `TellicoZipImportRealTest` — конструктор `ImportTellicoZipUseCase` теперь `(csvReader, xmlReader, mapper, applier)`, вызов — `useCase(zipFile, ImportFormat.TELLICO_CSV, tempOut)` (в тестовом `tellico.zip` лежит `tellico/tellico.csv`, не `tellico.xml`).

- [ ] **Step 9: Коммит**

```bash
# versionCode = 50, versionName = "0.5.42"
git commit -m "v 0.5.41 — маппер Tellico и импорт .tc: снимки, статусы, цитаты, фото"
```

---

### Task 7: Импорт своего бэкапа без потерь

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/importer/ImportKwBooktrackZipUseCase.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/importer/ImportKwBooktrackZipUseCaseTest.kt` (обновить)

**Interfaces:**
- Consumes: `SnapshotApplier`, `ImportAssets`, `BookSnapshot` (Tasks 1, 5), `ExportManifest`.
- Produces: `ImportKwBooktrackZipUseCase` возвращает `ImportTellicoResult` (старый тип `ImportResult` удаляется).

- [ ] **Step 1: Обновить `ImportKwBooktrackZipUseCaseTest`**

Тест `import_rejectsForeignManifest` сейчас ждёт `result.error?.message` — тип меняется на `ImportTellicoResult`, у которого тоже есть `error` и `isSuccess` ✓ (Task 6 добавил их). Остальные тесты не меняются по смыслу.

Добавить кейс: zip без `manifest.json` → `result.error?.message` содержит «manifest.json».

- [ ] **Step 2: Запустить тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "*ImportKwBooktrackZipUseCaseTest*"`
Expected: FAIL — тип `ImportResult` удалён / нет чтения `extra.json`.

- [ ] **Step 3: Реализовать импорт своего бэкапа**

1. Читать из zip: `manifest.json`, `books.csv`, `extra.json`, `covers/*`, `photos/*`.
2. `books.csv` → список `BookEntity` (все колонки, включая `purchase_date`, `purchase_price`, `source`; `id` — во внутреннем ключе, не в `BookEntity`).
3. `extra.json` → `Map<Long, BookExtras>` (по id книги): `notes`, `sessions`, `tags`, `photos`, `extraFields`.
4. Собрать `BookSnapshot` для каждой книги: `book` + `notes` (`NoteSnapshot`) + `sessions` (`SessionSnapshot`) + `tags` + `photos` (`PhotoSnapshot(archivePath = значение "file")`) + `extraFields`.
5. Применить каждый снимок через `applier.apply(snapshot, ImportAssets(staging, outputDir))`; обложка — по basename из колонки `cover_path` (файл лежит в `covers/` архива, ищется через `ImportAssets.find`).
6. Вернуть `ImportTellicoResult(total, imported, skipped, coversCopied, notesImported, dryRun = false)`.

- [ ] **Step 4: Запустить тест — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "*ImportKwBooktrackZipUseCaseTest*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
# versionCode = 53, versionName = "0.5.45"
git commit -m "v 0.5.42 — импорт своего бэкапа: extra.json, фото, все поля книги"
```

---

### Task 8: Экспорт в Tellico `.tc`

**Files:**
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/tellico/TellicoXmlWriter.kt`
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/exporter/ExportTellicoTcUseCase.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/exporter/TellicoTcRoundTripTest.kt`

**Interfaces:**
- Consumes: `SnapshotBuilder` (Task 1), `BookSnapshot` (Task 1), `TellicoFields` (Task 3), `ExportManifest`/`ExportCounts`/`AppVersionProvider` (существующие).
- Produces:
  ```kotlin
  /** Одна запись каталога для экспорта. */
  data class TcEntry(val snapshot: BookSnapshot, val coverArchiveName: String?)

  object TellicoXmlWriter {
      /** Собрать содержимое tellico.xml по записям. */
      fun write(entries: List<TcEntry>): String
  }

  class ExportTellicoTcUseCase @Inject constructor(
      private val snapshotBuilder: SnapshotBuilder,
      private val database: KwBooktrackDatabase,
      private val versionProvider: AppVersionProvider,
  ) {
      suspend operator fun invoke(output: OutputStream): ExportManifest
      suspend operator fun invoke(outputFile: File): ExportManifest
  }
  ```

- [ ] **Step 1: Написать падающий тест `TellicoTcRoundTripTest`**

Robolectric. Две in-memory базы: исходная (книга с заметками, сессией, тегом, фото, обложкой и чужим полем) и целевая (пустая).

Assertions:
- экспорт даёт zip, в котором есть `tellico.xml`, `images/*`, `photos/*`;
- `tellico.xml` начинается с `<?xml version="1.0" encoding="UTF-8"?>` и содержит `<tellico xmlns="http://periapsis.org/tellico/" syntaxVersion="12">` и `<collection title="kwbooktrack" type="2">`;
- **`<authors><author>` — контейнером**, не плоско: `tellicoXml.contains("<authors><author>")` ✓;
- **`<image id=… format=…/>` — ровно два атрибута**, без `width`/`height` (**Review Focus #1**);
- `<images>` отсутствует, если обложек нет;
- после импорта экспортированного файла в целевую базу: 1 книга, заметки/сессия/тег/фото на месте, чужие поля на месте;
- повторный импорт того же файла не меняет число книг, заметок, сессий, тегов и фото (**Review Focus #2**).

- [ ] **Step 2: Запустить тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "*TellicoTcRoundTripTest*"`
Expected: FAIL — «Unresolved reference: ExportTellicoTcUseCase».

- [ ] **Step 3: Реализовать `TellicoXmlWriter`**

Каркас — ровно как у Tellico (см. раздел «Формат Tellico»):
```xml
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE tellico PUBLIC '-//Robby Stephenson/DTD Tellico V12.0//EN' 'http://periapsis.org/tellico/dtd/v12/tellico.dtd'>
<tellico xmlns="http://periapsis.org/tellico/" syntaxVersion="12">
 <collection title="kwbooktrack" type="2">
  <fields>…</fields>
  <entry id="1">…</entry>
  <images>…</images>
 </collection>
</tellico>
```

`<fields>` — объявляем **только свои и чужие** поля (встроенные Tellico знает сам):
- всегда: `брошено` (type 4, format 4, flags 2, category «Личное»), `дата-начала` (12/3/0, «Личное»), `дата-прочтения` (12/3/0, «Личное»), `прочитал-часть` (4/4/2, «Личное»), `место-покупки` (1/4/0, «Покупка»), `продано-new` (4/4/2, «Личное»), `усл.-печ.-л.` (6/4/0, «Издательство»), `язык-оригинала` (1/4/0, «Издательство»), `оригинальное-название` (1/4/0, «Общее»), `цитаты` (2/4, flags 1, columns 2, пропы `column1`=«страница», `column2`=«Цитата», category «Цитаты»), `рецензия` (2/4/0, «Рецензия»), `заметки` (2/4, flags 1, columns 3, пропы `column1`=«страница», `column2`=«Текст», `column3`=«Дата», «Личное»), `теги` (1/4, flags 1, «Классификация»), `сессии-чтения` (2/4, flags 1, columns 6, «Личное»), `фото-заметок` (2/4, flags 1, columns 3, «Личное»);
- плюс объявления чужих полей из снимков: `name` = имя поля, `title` = его title, `type="1"`, `format="4"`, `flags="0"`, `category="Общее"`.

Атрибуты `<field>` в порядке: `category`, `description=""`, `flags`, `format`, `name`, `title`, `type` (как у Tellico).

`<entry id="N">` — `N` сквозной с 1. Внутри:
- встроенные поля — по таблице §5.8 спеки: `title`, `subtitle`, `publisher`, `pub_year`, `isbn`, `pages`, `series`, `series_num`, `condition`, `binding`, `pur_price`, `cover`, `comments`, `cdate`, `mdate`;
- многозначные — **контейнерами**: `authors`/`author`, `genres`/`genre`, `languages`/`language`, `translators`/`translator`, `cr_years`/`cr_year`, `теги`/`теги`, `цитаты`/`цитаты`, `заметки`/`заметки`, `сессии-чтения`/`сессии-чтения`, `фото-заметок`/`фото-заметок` — значения режутся по `"; "`;
- даты — `<поле calendar="gregorian"><year>…</year><month>…</month><day>…</day></поле>` (месяц/день двузначные);
- булевы — только `true`: `read` (при FINISHED), `gift`, `loaned`, `продано-new`, `брошено`, `прочитал-часть`;
- `рецензия` — текст первой заметки REVIEW по `createdAt` (или единственной);
- `цитаты` — строки `страница::текст` (пустая страница → `::текст`), строки через `\n`;
- `заметки` — строки `страница::текст::дата`;
- `сессии-чтения` — строки из 6 колонок: `начало::конец::стр.начала::стр.конца::длительность::заметка` (начало/конец/длительность — epoch millis);
- `фото-заметок` — строки `путь::индекс::дата`;
- чужие поля — `<имя>значение</имя>`.

Экранирование XML: `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;`, `"` → `&quot;`, `'` → `&apos;`.
Экранирование ячеек таблиц: `\` → `\\`, LF → `\n`, CR → `\r` (перед записью в XML).

`<images>` — по одному `<image id="<имя файла>" format="<расширение>"/>` на обложку; если обложек нет — блок не выводится.

- [ ] **Step 4: Реализовать `ExportTellicoTcUseCase`**

1. `snapshots = database.withTransaction { snapshotBuilder.buildAll() }`.
2. Для каждой книги с существующим файлом обложки (`File(book.coverPath)`) — прочитать байты, `md5 = MD5(байты)` (`MessageDigest.getInstance("MD5")`), имя файла в архиве = `{md5}.{ext}`, положить в zip как `images/{md5}.{ext}`, `coverArchiveName = "{md5}.{ext}"` (в `<cover>` — **только имя файла**, без `images/`), а в `<images>` — `<image id="{md5}.{ext}" format="{ext}"/>`.
3. Для каждого фото снимка — `archivePath` = `photos/{md5}.{ext}` → записать в zip файл с байтами из `localPath` (файла нет — фото пропустить).
4. `tellico.xml` — `TellicoXmlWriter.write(entries)`.
5. Вернуть `ExportManifest(counts = ExportCounts(books, sessions, notes, quotes, photos, tags))`.

- [ ] **Step 5: Запустить тест — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "*TellicoTcRoundTripTest*"`
Expected: PASS.

- [ ] **Step 6: Коммит**

```bash
# versionCode = 54, versionName = "0.5.46"
git commit -m "v 0.5.43 — экспорт в Tellico .tc: tellico.xml, обложки, фото, контейнеры"
```

---

### Task 9: Экспорт `books.csv` без потерь (`extra.json` + `photos/`)

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/exporter/ExportZipUseCase.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/exporter/ExportZipUseCaseTest.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/data/exporter/KwBooktrackZipRoundTripTest.kt`

**Interfaces:**
- Consumes: `SnapshotBuilder` (Task 1), `ExtraField` (Task 1).
- Produces: в zip-экспорте дополнительно `extra.json` и `photos/`; в `books.csv` дополнительные колонки.

- [ ] **Step 1: Написать падающий тест `KwBooktrackZipRoundTripTest`**

Robolectric. Исходная база: книга с 2 заметками (разные `kind`), сессией, тегом, фото, чужим полем `extraFields`.

Assertions:
- в zip есть `manifest.json`, `books.csv`, `extra.json`;
- `books.csv` содержит колонки `purchase_date`, `purchase_price`, `source`, `id`;
- `extra.json` для id книги содержит `notes` (2 шт., с `kind`, `page`, `body`, `createdAt`), `sessions`, `tags`, `photos` (с `file`, `noteIndex`, `createdAt`), `extraFields`;
- после импорта этого zip в чистую базу: книга, заметки, сессия, тег, фото и чужие поля на месте, `createdAt` заметок совпадают с исходными.

- [ ] **Step 2: Запустить тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "*KwBooktrackZipRoundTripTest*"`
Expected: FAIL — в архиве нет `extra.json`.

- [ ] **Step 3: Реализовать экспорт `books.csv` с `extra.json`**

- `books.csv`: заголовок — текущий `BOOKS_HEADER` + `;purchase_date;purchase_price;source;id`. Значения: `purchase_date`/`purchase_price` — из книги, `source` — `book.source.name`, `id` — `book.id`.
- `extra.json` — JSON вида:
  ```json
  {"books":{"620":{"notes":[{"kind":"QUOTE","page":12,"body":"…","createdAt":1730000000000}],
             "sessions":[{"startedAt":…,"endedAt":…,"startPage":1,"endPage":20,"durationSec":600,"note":"…"}],
             "tags":["фэнтези"],
             "photos":[{"file":"photos/abc.jpg","noteIndex":2,"createdAt":…}],
             "extraFields":{"посоветовал":{"t":"Посоветовал","v":"Книжный поезд"}}}}}
  ```
  (ключ — id книги строкой). Сериализация — `kotlinx.serialization`.
- `photos/` — файлы фото под именем `{md5}.{ext}` (тот же MD5, что и в `.tc`), путь в `extra.json` = `photos/{md5}.{ext}`.

- [ ] **Step 4: Запустить тест — должен пройти**

Run: `./gradlew testDebugUnitTest --tests "*KwBooktrackZipRoundTripTest*" --tests "*ExportZipUseCaseTest*"`
Expected: PASS.

- [ ] **Step 5: Коммит**

```bash
# versionCode = 55, versionName = "0.5.47"
git commit -m "v 0.5.44 — экспорт books.csv без потерь: extra.json, фото, новые колонки"
```

---

### Task 10: UI — импорт любого формата и экспорт `.tc`

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/importer/TellicoImportViewModel.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/importer/TellicoImportScreen.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/about/AboutExportViewModel.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/about/AboutScreen.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/ui/importer/TellicoImportViewModelTest.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/ui/about/AboutExportViewModelTest.kt`

**Interfaces:**
- Consumes: `ImportFormatDetector`, `ImportFormat` (Task 6), `ImportTellicoZipUseCase`, `ImportKwBooktrackZipUseCase`, `ExportTellicoTcUseCase` (Task 8), `ExportZipUseCase`.
- Produces:
  ```kotlin
  // TellicoImportViewModel
  fun importFromUri(uri: Uri)   // без изменений в сигнатуре
  // AboutExportViewModel
  fun exportToUri(uri: Uri)                 // свой бэкап (без изменений)
  fun exportTellicoToUri(uri: Uri)          // новая — .tc
  companion object { fun suggestedFilename(now: LocalDateTime = LocalDateTime.now()): String
                     fun suggestedTellicoFilename(now: LocalDateTime = LocalDateTime.now()): String }
  ```
  `suggestedTellicoFilename` → `kwbooktrack-tellico-<yyyy-MM-dd_HH-mm-ss>.tc`.

- [ ] **Step 1: Написать падающий тест `TellicoImportViewModelTest`**

Дополнить существующий тест:
- при `importFromUri` с URI, за которым лежит zip с `tellico.xml` → в состоянии `result != null` и `result.imported == 1`;
- при URI с zip `manifest.json` + `books.csv` → `result != null` (маршрут в свой бэкап);
- при URI с «не-Tellico» файлом → `state.error != null`.

- [ ] **Step 2: Запустить тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "*TellicoImportViewModelTest*"`
Expected: FAIL — `importFromUri` не маршрутизирует по формату.

- [ ] **Step 3: Переписать `TellicoImportViewModel.importFromUri`**

1. Временный файл: `File(cacheDir, "imports")/import-${System.currentTimeMillis()}.tmp` (имя больше не `tellico-…`, файл может быть не zip).
2. Скопировать URI во временный файл.
3. `ImportFormatDetector.detect(temp)`:
   - `KWBOOKTRACK_ZIP` → `importKwBooktrackZipUseCase(temp, context.filesDir)` (второй аргумент — каталог для обложек; сигнатура `invoke(zipFile: File, outputDir: File)`, см. задачу 7);
   - `TELLICO_XML` / `TELLICO_CSV` → `importTellicoZipUseCase(temp, format, context.filesDir)`.
4. Ошибки → `UiText.Raw(e.message)`; в `finally` — удалить временный файл.

- [ ] **Step 4: Обновить `TellicoImportScreen`**

- Кнопка «Выбрать файл» (строка `tellico_import_pick_file`), mime: `arrayOf("application/zip", "application/xml", "text/xml", "text/csv", "application/octet-stream", "*/*")`.
- Описание (`tellico_import_description`) — про `.tc`/XML/CSV/свой бэкап.
- **Показывать ошибки импорта:** сейчас экран рисует карточку «Импорт завершён» даже когда `result.errors` непуст (частично упавший импорт выглядит как успешный). Если `result != null && !result.isSuccess` — показывать `result.error` как ошибку, а не как успех.
- **Поправить подписи счётчиков:** `notesImported` теперь = заметки + сессии + теги + фото, а строка `tellico_import_notes_format` говорит «Заметок добавлено: %1$d» — вводит в заблуждение. Либо переименовать строку в «Заметок, сессий, тегов и фото добавлено», либо считать и показывать раздельно (раздельно — значит расширить `ImportTellicoResult`, что выходит за рамки задачи; выбери переименование).

- [ ] **Step 5: Написать падающий тест `AboutExportViewModelTest` (дополнение)**

- `suggestedTellicoFilename()` начинается с `kwbooktrack-tellico-` и заканчивается на `.tc`, дата-часть совпадает `\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2}`.
- `exportTellicoToUri` пишет в поток zip с `tellico.xml` (в тесте — file-URI через `contentResolver`, либо проверка через сам use case).

- [ ] **Step 6: Запустить тест — должен упасть**

Run: `./gradlew testDebugUnitTest --tests "*AboutExportViewModelTest*"`
Expected: FAIL — «Unresolved reference: exportTellicoToUri».

- [ ] **Step 7: Реализовать экспорт `.tc` в UI**

`AboutExportViewModel`:
- поле `exportTellicoUseCase: ExportTellicoTcUseCase` в конструкторе;
- `exportTellicoToUri(uri)`: открыть поток через `contentResolver.openOutputStream(uri)`, вызвать use case, состояние — как у `exportToUri` (`lastManifest`/`error`).

`AboutScreen`:
- третья кнопка «Экспорт в Tellico (.tc)» (`action_export_tellico`), `rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip"))` → `exportViewModel.exportTellicoToUri(uri)`, имя файла — `AboutExportViewModel.suggestedTellicoFilename()`;
- snackbar для успеха `.tc` — тот же `export_success_format` (у `.tc` тоже есть `ExportManifest`).

- [ ] **Step 8: Запустить тесты — должны пройти**

Run: `./gradlew testDebugUnitTest --tests "*TellicoImportViewModelTest*" --tests "*AboutExportViewModelTest*"`
Expected: PASS.

- [ ] **Step 9: Коммит**

```bash
# versionCode = 56, versionName = "0.5.48"
git commit -m "v 0.5.45 — импорт любого формата и экспорт .tc в UI"
```

---

### Task 11: Новые поля в живой коллекции Tellico пользователя

**Files:**
- Вне репозитория: `~/Документы/Tellico/tellico.xml`, `~/Документы/Tellico/Books.tc` (zip).

**Interfaces:**
- Consumes: имена полей из Task 8 (`брошено`, `дата-начала`, `теги`, `заметки`, `сессии-чтения`, `фото-заметок`).
- Produces: правка `<fields>` в коллекции пользователя.

- [ ] **Step 1: Сделать резервную копию**

```bash
cp ~/Документы/Tellico/tellico.xml ~/Документы/Tellico/tellico.xml.bak-2026-11-05
cp ~/Документы/Tellico/Books.tc ~/Документы/Tellico/Books.tc.bak-2026-11-05
```

- [ ] **Step 2: Дописать шесть `<field>` в `<fields>` файла `tellico.xml`**

Вставить перед `</fields>` (формат — как у соседних полей):
```xml
<field category="Личное" description="" flags="2" format="4" name="брошено" title="Брошено" type="4"/>
<field category="Личное" description="" flags="0" format="3" name="дата-начала" title="Дата начала" type="12"/>
<field category="Классификация" description="" flags="1" format="4" name="теги" title="Теги" type="1"/>
<field category="Личное" description="" flags="1" format="4" name="заметки" title="Заметки" type="2">
 <prop name="column1">страница</prop>
 <prop name="column2">Текст</prop>
 <prop name="column3">Дата</prop>
 <prop name="columns">3</prop>
</field>
<field category="Личное" description="" flags="1" format="4" name="сессии-чтения" title="Сессии чтения" type="2">
 <prop name="column1">начало</prop>
 <prop name="column2">конец</prop>
 <prop name="column3">стр. начала</prop>
 <prop name="column4">стр. конца</prop>
 <prop name="column5">длительность</prop>
 <prop name="column6">заметка</prop>
 <prop name="columns">6</prop>
</field>
<field category="Личное" description="" flags="1" format="4" name="фото-заметок" title="Фото заметок" type="2">
 <prop name="column1">файл</prop>
 <prop name="column2">заметка</prop>
 <prop name="column3">дата</prop>
 <prop name="columns">3</prop>
</field>
```

- [ ] **Step 3: Пересобрать `Books.tc`**

`Books.tc` — zip с `tellico.xml` + `images/`. Пересобрать его с обновлённым `tellico.xml` (структуру внутри проверить через `unzip -l` до пересборки — имя `tellico.xml` и каталог `images/`).

- [ ] **Step 4: Проверить в Tellico**

Открыть Tellico → Import → Tellico Files → выбрать `Books.tc` → режим **Replace** в тестовую коллекцию → убедиться, что поля «Брошено», «Дата начала», «Теги», «Заметки», «Сессии чтения», «Фото заметок» появились и прежние данные на месте.

- [ ] **Step 5: Коммит не требуется** — файлы вне репозитория.

---

## Финальная проверка

- [ ] `./gradlew testDebugUnitTest` — весь набор зелёный.
- [ ] Ручной прогон в Tellico (§11 спеки):
  1. Экспортированный `.tc` открыть через **Import → Tellico Files** в режиме **Append** к «Мои книги» и в режиме **Replace** — поля на месте, обложки видны, чужие поля не превратились в дубликаты.
  2. Отдельно проверить: поля-контейнеры (`authors`, `genres`, `languages`, `translators`, `cr_years`, `цитатыs`) читаются как значения; `<images>` без `width`/`height` — обложки показываются; `format="webp"` совпадает с реальным файлом; табличные поля «Заметки», «Сессии чтения», «Фото заметок» открываются как таблицы.
  3. `~/Документы/Tellico/kwbook/backup.tc` разбирается приложением (обратный путь) — импорт даёт 605 книг без ошибок.
