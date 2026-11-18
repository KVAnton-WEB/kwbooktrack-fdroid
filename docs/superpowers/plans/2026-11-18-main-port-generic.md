# Перенос общих правок в main — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Одним коммитом перенести в main все не-фичевые правки из `feature/isbn-lookup` (af88b78) и применить его в main-worktree без push.

**Architecture:** Временная ветка `port/main-generic` от main в текущем worktree. 73 целых общих файла берутся дословно `git checkout af88b78 -- …`; 9 смешанных файлов собираются точечно (только общие части); версия 0.8.1/88 + changelog; полный прогон тестов и сборка; `merge --ff-only` в main-worktree.

**Tech Stack:** Kotlin, Room, Hilt, Jetpack Compose, Gradle (Kotlin DSL), Robolectric/JUnit4.

**Spec:** `docs/superpowers/specs/2026-11-18-main-port-generic-design.md`

## Global Constraints

- Версия: `versionCode = 88`, `versionName = "0.8.1"`; changelog `fastlane/metadata/android/{ru,en-US}/changelogs/88.txt`.
- Один коммит в конце (pre-commit хук требует смены versionName И versionCode); без push; тег не ставим.
- Целые файлы — дословно из `af88b78`, не переписывать.
- В коде main не должно остаться фичевых идентификаторов: `CatalogLookup`, `catalogLookup`, `lookupByUrl`, `searchByTitle`, `MovieLookupState`, `LookupState`, `normalizeChecked`, `findByKinopoiskId`, `filledFromCatalog`, `MOVIE_PLOT_PREVIEW`, `lookup_`/`movie_lookup_` строки, INTERNET. `MediaSource.CATALOG` в `Enums.kt` уже есть в main — не трогаем и не считаем утечкой.
- Исключённые файлы не создавать: `data/catalog/**`, `AddBookScreen.kt`, `Isbn.kt`/`IsbnTest.kt`, `TestImages.kt`, `MovieDaoTest.kt`, `AddBookViewModelTest.kt`, `AddBookViewModelLookupTest.kt`, `MovieEditViewModelLookupTest.kt`, `AndroidManifest.xml` (остаётся main-версия), `docs/fdroid.md`, changelogs 87–90, доки isbn/movie-catalog-lookup и review-fixes.
- Тесты: `./gradlew :app:testDebugUnitTest`; сборка: `./gradlew :app:assembleDebug`. Никаких production-режимов.
- Ответы пользователю — по-русски; тексты changelog — в стиле существующих (проза, обращения к пользователю).

## Review Focus

1. **Утечка фичи в main** (каталожный код/строки/разрешения) → grep-проверка в Task 5, ожидание пусто.
2. **Потеря общих правок при сборке смешанных файлов** (ошибки сохранения фильма, isDirty, очистка temp-обложек) → `MovieEditViewModelTest`, `MovieEditDatesTest` + построчный diff-review в Task 3.
3. **Миграция v14 трогает данные** → `Migrate13To14Test`, `MigrationsFromV1Test`, `MigrationsSchemaTest` (Task 5).
4. **Регресс импорта: потеря фото/дублей/кириллицы** → `BookDaoMergeTest`, importer-тесты, `TellicoTcRoundTripTest` (Task 5).
5. **Хук версий / рассинхрон changelog** → коммит проходит без `--no-verify`; 88.txt в обеих локалях (Task 5).

---

### Task 1: Ветка и целые файлы

**Files:**
- Create: ветка `port/main-generic`; 73 файла из списка ниже (включая новые `Migrate13To14.kt`, `StagingDirs.kt`, `NotePhotoTile.kt`, `Zoom.kt`, тесты, 2 дока ui-covers)
- Delete: `app/schemas/ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase/{1,2,3,4}.json`

**Interfaces:**
- Consumes: main (`85c1704`), ветка `af88b78`
- Produces: дерево, где все целые общие файлы идентичны ветке; смешанные файлы ещё в main-версии

- [ ] **Step 1: Создать ветку от main**

```bash
git switch -c port/main-generic main
```

Проверка: `git rev-parse HEAD` = `85c1704…`; незакоммиченный спек остаётся в дереве (`git status --porcelain` показывает только `?? docs/superpowers/specs/2026-11-18-main-port-generic-design.md`).

- [ ] **Step 2: Сгенерировать список целых файлов и взять их из ветки**

```bash
git diff main af88b78 --name-only | grep -v -E 'data/catalog/|docs/fdroid\.md|isbn-catalog-lookup|movie-catalog-lookup|review-fixes|changelogs/(87|88|89|90)\.txt|AndroidManifest\.xml|data/text/Isbn\.kt|IsbnTest\.kt|AddBookScreen\.kt|TestImages\.kt|MovieDaoTest\.kt|AddBookViewModelTest\.kt|AddBookViewModelLookupTest\.kt|MovieEditViewModelLookupTest\.kt|^README\.md|app/build\.gradle\.kts|dao/MovieDao\.kt|MovieRepository\.kt|AddBookViewModel\.kt|MovieEditScreen\.kt|MovieEditViewModel\.kt|strings\.xml|app/schemas/' > /tmp/opencode/port-whole-files.txt
wc -l < /tmp/opencode/port-whole-files.txt   # ожидается 73
xargs -a /tmp/opencode/port-whole-files.txt git checkout af88b78 --
git rm -q app/schemas/ru.antonkw.kwbooktrack.data.db.KwBooktrackDatabase/{1,2,3,4}.json
```

Проверка: `git status --porcelain | grep -c '^[MADR]'` = 77 (73 checkout + 4 удаления); `git diff af88b78 -- $(cat /tmp/opencode/port-whole-files.txt)` пусто.

- [ ] **Step 3: Убедиться, что фичевые файлы не появились**

```bash
git status --porcelain | grep -E 'catalog/|TestImages|MovieDaoTest|IsbnTest|AddBookViewModelTest|LookupTest|fdroid|AndroidManifest|changelogs/(87|89|90)'
```

Ожидание: пусто (changelogs/88 ветки тоже не берём — grep по 88 не включаем, проверяем отдельно: `git status --porcelain | grep changelogs` пусто).

---

### Task 2: Смешанные файлы — малые правки

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/db/dao/MovieDao.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/data/repository/MovieRepository.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/add/AddBookViewModel.kt`
- Modify: `app/src/main/res/values/strings.xml`, `app/src/main/res/values-en/strings.xml`
- Modify: `README.md`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: дерево Task 1; эталонные тексты — `git show af88b78:<path>` и `/tmp/opencode/mixed-rest.diff`, `/tmp/opencode/mixed-ui.diff`
- Produces: `MovieEditError`-независимые правки готовы; версия 0.8.1/88 выставлена

- [ ] **Step 1: MovieDao.kt — удалить `delete()`**

Убрать `import androidx.room.Delete` и метод `@Delete suspend fun delete(movie: MovieEntity): Int`. `findByKinopoiskId` НЕ добавлять. Остальное — как в main.

- [ ] **Step 2: MovieRepository.kt — удалить `delete()` и `count()`**

Убрать метод `suspend fun delete(movie: MovieEntity): Int { … }` целиком (вместе с чтением photoPaths, deleteWithContent, deleteFiles, coverStorage.delete) и `suspend fun count(): Int = movieDao.count()`. Passthrough `findByKinopoiskId` НЕ добавлять. Блок импортов привести к виду `git show af88b78:app/src/main/kotlin/ru/antonkw/kwbooktrack/data/repository/MovieRepository.kt` (у ветки импорты уже очищены под отсутствие delete(); findByKinopoiskId новых импортов не требует).

- [ ] **Step 3: AddBookViewModel.kt — 4 замены в save()**

Только внутри `save()`:
- `_state.value = s.copy(error = AddBookError.DUPLICATE_ISBN)` → `_state.value = _state.value.copy(error = AddBookError.DUPLICATE_ISBN)` — 3 места (разный отступ);
- `_state.value = s.copy(saved = true, error = null)` → `_state.value = _state.value.copy(saved = true, error = null)`.

Больше ничего не менять (isDirty, import-блок, lookup — не трогаем). Проверка: `git diff main -- …/AddBookViewModel.kt` показывает ровно 4 изменённые строки.

- [ ] **Step 4: strings.xml обеих локалей**

ru: добавить `<string name="note_photos_failed">Заметка сохранена, но фото не прикрепились: %1$d</string>` (рядом с note-строками); `tellico_import_imported_format`: «Импортировано книг: %1$d» → «Импортировано записей: %1$d»; удалить `tellico_import_dry_run`.
en: `note_photos_failed` = `Note saved, but photos were not attached: %1$d`; `Books imported: %1$d` → `Records imported: %1$d`; удалить `Dry run (no writes)`.
Блок `lookup_*`/`movie_lookup_*` НЕ добавлять. Проверка: `git diff main -- …/strings.xml` не содержит `lookup`.

- [ ] **Step 5: README.md — раздел о версионировании**

Из `git show af88b78:README.md` взять дословно раздел «Как нумеровать версии в ветках» и вставить в то же место, где он в ветке (после раздела о сборке/версиях — сверить с веткой); плюс фикс перевод строки в конце файла как в ветке. Два раздела про поиск НЕ брать. Проверка: `git diff main -- README.md` не содержит `каталог`/`lookup` кроме примера формата `0.8.0-lookup.1` внутри раздела о версионировании (это пример формата, допустим).

- [ ] **Step 6: build.gradle.kts — версия и ksp-блок**

`versionCode = 88`, `versionName = "0.8.1"`. Блок

```kotlin
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}
```

заменить комментарием ветки:

```kotlin
// Схемы Room не экспортируются: `exportSchema = false` в KwBooktrackDatabase,
// все переходы — рукописные миграции в DatabaseModule.
```

Блок `doNotCacheIf` ниже не трогать.

---

### Task 3: Смешанные файлы — форма фильма

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/movies/MovieEditViewModel.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/movies/MovieEditScreen.kt`

**Interfaces:**
- Consumes: дерево Tasks 1–2; спека (раздел «Смешанные»); `/tmp/opencode/mixed-ui.diff`
- Produces: `MovieEditError.INVALID_DATE`, `error`/`errorNonce`, `isDirty`/`hasInput`, `saving` — используются портированными `MovieEditViewModelTest`/`MovieEditDatesTest` и экраном

Стратегия: взять версию ветки (`git checkout af88b78 -- <оба файла>`) и удалить из неё фичевые части — общие правки останутся дословно. Удалять по списку спеки; ориентир по hunk'ам — `/tmp/opencode/mixed-ui.diff`.

- [ ] **Step 1: MovieEditViewModel.kt — удалить фичевое**

Удалить: импорты каталога/поиска; `MovieLookupState`, `MovieLookupField`, `MovieLookupFieldValue`; параметр конструктора `catalogLookup`; поле `lookup` в `MovieEditState`; `filledFromCatalog`; `lookupJob`/`generation`/`newLookup`/`updateLookup`; `lookupByUrl`, `searchByTitle`, `chooseCandidate`, `showSourceFor`, `findDuplicate`, `pendingOrFound`, `importFound`, `dismiss*`, `importableFields`, `preview`, `applyDraft`, `attachCoverFromUrl`; `MOVIE_PLOT_PREVIEW`; в `save()`/импорте источник `MediaSource.CATALOG` → `MediaSource.MANUAL`; в `isDirty` убрать lookup-исключение (оставить сравнение с `initialSnapshot`, обнуляя `error`/`errorNonce` как в ветке).
Оставить (дословно из ветки): `MovieEditError`, `error`/`errorNonce` в состоянии, `initialSnapshot`/`isDirty`/`hasInput`, флаг `saving` (синхронно до launch, сброс в finally), валидацию `watchedDates` до launch, `try/finally` в `save()`, очистку прошлой temp-обложки в `attachCover` и немедленную в `clearCover`, сброс `error` в `update()`, `initialSnapshot = _state.value` после загрузки.

- [ ] **Step 2: MovieEditScreen.kt — удалить фичевое**

Удалить: lookup-диалоги (Pending/Candidates), кнопки `searchByTitle`/`lookupByUrl`, статус поиска, `fieldLabel`, импорты `Search`/`MovieLinks` и прочие неиспользуемые после удаления.
Оставить: `BackHandler` + `handleBack` + диалог unsaved (`isDirty`/`hasInput`), snackbar `INVALID_DATE` (`error`/`errorNonce`), `navigationIcon → handleBack`, импорты `BackHandler`/`SnackbarHost`/`SnackbarHostState`/`AlertDialog`/`mutableStateOf`/`remember`/`setValue`.

- [ ] **Step 3: Diff-review против main**

```bash
git diff main -- app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/movies/MovieEditViewModel.kt app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/movies/MovieEditScreen.kt
```

Прочитать каждый hunk: все изменения должны быть из списка «Оставить» спеки; ни одного фичевого идентификатора. Если hunk не опознаётся — сверить с `/tmp/opencode/mixed-ui.diff`.

---

### Task 4: Changelog 0.8.1

**Files:**
- Create: `fastlane/metadata/android/ru/changelogs/88.txt`
- Create: `fastlane/metadata/android/en-US/changelogs/88.txt`

**Interfaces:**
- Consumes: стиль `git show main:fastlane/metadata/android/ru/changelogs/84.txt`
- Produces: changelog для кода 88 в обеих локалях

- [ ] **Step 1: Написать changelogs**

Проза в стиле 84.txt, 1–2 абзаца, описать: реимпорт без дублей и потери фото (md5), кириллица в CSV-импорте, обложки 2:3 и тайл фото заметок, зум просмотрщика, календарь с переходом к записи, просмотр обложки фильма, ошибки сохранения (дата фильма, «отменить изменения?», двойное сохранение), заметка не теряет фото молча, статистика без отрицательных месяцев. Без упоминания каталогов/поиска. en-US — перевод ru.

- [ ] **Step 2: Проверить наличие обоих файлов**

`ls fastlane/metadata/android/{ru,en-US}/changelogs/88.txt` — оба существуют.

---

### Task 5: Верификация, коммит, применение в main

**Files:**
- Modify: все изменения Tasks 1–4 + незакоммиченные спек и этот план (`docs/superpowers/specs/2026-11-18-main-port-generic-design.md`, `docs/superpowers/plans/2026-11-18-main-port-generic.md`)

**Interfaces:**
- Consumes: полное дерево порта
- Produces: коммит в `port/main-generic`; main-worktree обновлён ff-only

- [ ] **Step 1: Grep-проверка на утечку фичи**

```bash
grep -rn -E 'CatalogLookup|catalogLookup|lookupByUrl|searchByTitle|MovieLookupState|LookupState|normalizeChecked|findByKinopoiskId|filledFromCatalog|MOVIE_PLOT_PREVIEW' app/src
grep -rn 'lookup_' app/src/main/res
grep -n 'INTERNET' app/src/main/AndroidManifest.xml
```

Ожидание: все три пусты.

- [ ] **Step 2: Полный прогон тестов**

```bash
./gradlew :app:testDebugUnitTest
```

Ожидание: BUILD SUCCESSFUL, все тесты зелёные (включая `MovieEditViewModelTest.doubleSaveWithoutWaitingDoesNotDuplicateMovie`, `MovieEditDatesTest`, `Migrate13To14Test`, `BookDaoMergeTest`, importer-тесты). При падении — чинить сборку смешанных файлов, не тесты.

- [ ] **Step 3: Сборка**

```bash
./gradlew :app:assembleDebug
```

Ожидание: BUILD SUCCESSFUL.

- [ ] **Step 4: Коммит**

```bash
git add -A
git status --porcelain   # глазами: только файлы порта + спек/план; ничего лишнего
git commit -m "v 0.8.1 — общие правки из feature/isbn-lookup: импорт без потерь и дублей, обложки 2:3 и тайл фото, зум, календарь, ошибки сохранения, статистика, БД v14"
```

Хук должен пройти без `--no-verify` (87→88, 0.8.0→0.8.1). Если в `git add -A` попало лишнее — убрать из индекса до коммита.

- [ ] **Step 5: Применить в main-worktree**

```bash
git -C /home/kvanton/projects/pet/kwbooktrack merge --ff-only port/main-generic
git -C /home/kvanton/projects/pet/kwbooktrack log --oneline -1
git -C /home/kvanton/projects/pet/kwbooktrack status --porcelain
```

Ожидание: ff-мердж прошёл, HEAD = коммит порта, дерево чистое. Без push. Ветку `port/main-generic` не удалять до подтверждения пользователя.

- [ ] **Step 6: Отчёт пользователю**

Кратко: что перенесено (группы), версия 0.8.1/88, тесты/сборка зелёные, main обновлён локально; напомнить про будущий конфликт 88.txt при мердже фичи и что ветка порта ждёт подтверждения на удаление.
