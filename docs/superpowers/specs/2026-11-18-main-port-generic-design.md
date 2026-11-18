# Перенос общих правок из feature/isbn-lookup в main

Дата: 2026-11-18. Статус: на утверждение.

## Интент

В ветке `feature/isbn-lookup` (36 коммитов после мерджа main, голова `af88b78`)
накопились правки, которые не относятся к фиче ветки — поиску книг и фильмов
во внешних каталогах. Это исправления потери данных при импорте, ошибки
сохранения, UI-улучшения и чистка мёртвого кода. Пользователь хочет получить
их в main отдельным качественным патчем, не дожидаясь релиза фичи поиска.

Критерий включения: изменение не зависит от `data/catalog` и поискового UI
и имеет смысл в main без фичи поиска.

## Цели

1. В main попадает всё общее: импорт (дедуп фото по md5, trimmed title,
   кодировка CSV по всему файлу, реестр staging-каталогов), снимок (фото с
   одинаковым содержимым не теряются), статистика (отрицательные месяцы,
   сумма дневных страниц), UI (обложки 2:3 / `heightDp`, тайл фото заметок,
   зум просмотрщика, сетка и навигация календаря, просмотр обложки фильма),
   ошибки сохранения (`s.copy` → `_state.value.copy` у книги; дата-ошибка,
   `saving`-флаг, isDirty/диалог «отменить?» у фильма), фото заметок
   (`failedPhotos` + snackbar), миграция v13→v14 (удаление бесполезных
   индексов), чистка неиспользуемых методов DAO/репозиториев, WhileSubscribed.
2. Патч проверяем: полный прогон юнит-тестов и сборка проходят на main-коде.
3. В патче нет ни одного упоминания каталогов/поиска в коде (кроме документов
   о процессе версионирования, где `0.8.0-lookup.1` — пример формата).

## Не-цели

- Не переносим фичу поиска: `data/catalog/**`, поисковый UI и его тесты,
  INTERNET-разрешение, `ApiKeys`, `Isbn.normalizeChecked`/`candidates`,
  `findByKinopoiskId`, `MediaSource.CATALOG` в формах, поисковые строки,
  README/fdroid про каталоги, changelogs 87–90 ветки.
- Не меняем поведение main сверх того, что делала ветка в общих частях.
- Не пушим никуда: коммит локальный.
- Не релизим: тег и публикация — отдельный процесс пользователя.

## Анти-цели

- «Тихая» поломка main: патч не должен компилироваться только за счёт
  случайно затянутого фичевого кода.
- Потеря данных при миграции: v14 удаляет только индексы, данные не трогает
  (как в ветке).
- Регресс хука версий: коммит обязан менять versionName и versionCode.

## Ограничения

- main живёт в worktree `/home/kvanton/projects/pet/kwbooktrack` (чистый,
  `85c1704`, v0.8.0 / code 87, БД v13). Текущий worktree — ветка фичи.
- Pre-commit хук (`core.hooksPath=hooks`) требует сменить и versionName, и
  versionCode. Решение пользователя: **0.8.1 / code 88** + новый changelog
  `88.txt` (ru и en-US). При будущем мердже фичи ожидается тривиальный
  конфликт в `88.txt` (обе стороны добавили файл) — это осознанная цена.
- Строки `error_invalid_date`, `dialog_unsaved_*`, `action_discard`,
  `label_cover` уже есть в main — новых строк кроме `note_photos_failed`
  и правки `tellico_import_imported_format` не нужно.
- Тесты: Robolectric для data/importer, data/snapshot, ui; plain JUnit4 для
  stats/zoom. Полный прогон `:app:testDebugUnitTest` + `:app:assembleDebug`.

## Подходы (выбор)

- **A. Файловый checkout + hunk-хирургия (выбран).** Временная ветка
  `port/main-generic` от main; целые общие файлы берутся
  `git checkout af88b78 -- <path>`; смешанные файлы правятся точечно
  (фильтрованный патч / ручная сборка). Плюс: точно повторяет проверенный
  код ветки, минимум ручного переписывания. Минус: смешанные файлы требуют
  внимательной сборки и проверки тестами.
- B. Cherry-pick коммитов ветки — не подходит: коммиты смешаны на уровне
  hunk'ов внутри файлов (один коммит содержит и фичу, и общие правки),
  36 коммитов дали бы каскад конфликтов.
- C. Переписать общие правки заново на main — дороже и рискованнее:
  код уже написан, отревьюен и покрыт тестами в ветке.

## Решения

### Версия и changelog

`versionName = "0.8.1"`, `versionCode = 88`. Новые файлы
`fastlane/metadata/android/{ru,en-US}/changelogs/88.txt` с описанием общих
правок (импорт без потери фото и кириллицы, дедуп при реимпорте, обложки 2:3,
тайл фото и зум, календарь, ошибки сохранения, статистика). Тексты — на
усмотрение реализатора, в стиле существующих changelog'ов.

### Документы

В патч включаются `docs/superpowers/specs/2026-11-18-ui-covers-photos-calendar-design.md`
и `docs/superpowers/plans/2026-11-18-ui-covers-photos-calendar.md` (общая фича),
а также настоящие спек и план переноса. Документы про поиск и review-fixes
не включаются. Спеки/планы мульти-дат уже есть в main.

### Классификация файлов

**Целиком в патч — основной код:**

- `data/db/KwBooktrackDatabase.kt` (v14), `data/db/dao/BookDao.kt` (чистка +
  trimmed-сравнение в upsert), `data/db/entity/BookEntity.kt` (без индексов),
  `data/db/migration/Migrate13To14.kt` (новый), `data/di/DatabaseModule.kt`
- `data/exporter/ExportTellicoTcUseCase.kt`, `ExportZipUseCase.kt`
  (namePhotos вне транзакции)
- `data/importer/ImportKwBooktrackZipUseCase.kt`, `ImportTellicoUseCase.kt`,
  `ImportTellicoZipUseCase.kt`, `StagingDirs.kt` (новый), `TellicoBookMapper.kt`
- `data/repository/BookRepository.kt`, `MediaDateRepository.kt`,
  `NoteRepository.kt`, `SessionRepository.kt`, `TagRepository.kt` (чистка)
- `data/snapshot/LibrarySnapshot.kt`, `SnapshotApplier.kt`, `SnapshotBuilder.kt`
- `data/stats/StatsCalculator.kt`
- `ui/books/BookDetailScreen.kt`, `BookDetailViewModel.kt` (дедуп сессий,
  транзакция markFinished)
- `ui/calendar/CalendarScreen.kt`, `CalendarViewModel.kt`
- `ui/common/CoverImage.kt`, `NotePhotoTile.kt` (новый),
  `PhotoGalleryDialog.kt`, `Zoom.kt` (новый)
- `ui/importer/TellicoImportScreen.kt` (без dry-run)
- `ui/library/LibraryScreen.kt`, `LibraryViewModel.kt`
- `ui/movies/MovieDetailScreen.kt` (просмотр обложки, heightDp)
- `ui/navigation/KwBooktrackNavHost.kt` (onOpenMedia календаря)
- `ui/notes/NoteDetailScreen.kt`, `NoteEditorScreen.kt`,
  `NoteEditorViewModel.kt`, `NotesListViewModel.kt`
- `ui/stats/StatisticsScreen.kt`, `StatisticsViewModel.kt`
- удаление `app/schemas/.../{1,2,3,4}.json` и ksp-блока schemaLocation в
  `app/build.gradle.kts` (версия — отдельно, см. выше)

**Целиком в патч — тесты:** `BookDaoMergeTest` (новый), `BookDaoTest`,
`NoteDaoTest`, `ReadingSessionDaoTest`, `Migrate13To14Test` (новый),
`Migrate11To12Test`, `MigrationsFromV1Test`, `MigrationsSchemaTest`,
`TellicoTcRoundTripTest`, `ImportKwBooktrackZipUseCaseTest`,
`ImportTellicoUseCaseTest`, `StagingDirsTest` (новый),
`TellicoImportIntegrationTest`, `TellicoTcImportTest`,
`TellicoZipImportRealTest`, `TagRepositoryTest`, `SnapshotApplierTest`,
`SnapshotBuilderTest`, `StatsCalculatorTest`, `BookDetailMultiDateTest`,
`BookDetailViewModelTest`, `ZoomTest` (новый), `TellicoImportViewModelTest`,
`MovieEditDatesTest` (новый), `MovieEditViewModelTest` (тест двойного
сохранения), `NoteEditorViewModelTest`, `StatisticsChartValuesTest` (новый).

**Смешанные — только общие части:**

- `data/db/dao/MovieDao.kt`: удалить `Delete`-импорт и `delete()`;
  **исключить** `findByKinopoiskId`.
- `data/repository/MovieRepository.kt`: удалить `delete()` и `count()`;
  **исключить** passthrough `findByKinopoiskId`.
- `ui/add/AddBookViewModel.kt`: только 4 замены `s.copy(...)` →
  `_state.value.copy(...)` внутри `save()` (поля, дописанные во время
  сохранения, не пропадают при ошибке). Всё остальное — фича.
- `ui/movies/MovieEditViewModel.kt`: `MovieEditError`, `error`/`errorNonce`
  в состоянии, `initialSnapshot`/`isDirty`/`hasInput` (без исключения
  `lookup`), флаг `saving`, валидация `INVALID_DATE` до запуска,
  `try/finally` в `save()` (источник остаётся `MediaSource.MANUAL`),
  очистка временных обложек в `attachCover`/`clearCover`, сброс ошибки в
  `update()`. **Исключить**: всё каталожное (импорты, `MovieLookupState`,
  `catalogLookup`, `filledFromCatalog`, lookup-методы, `MOVIE_PLOT_PREVIEW`).
- `ui/movies/MovieEditScreen.kt`: `BackHandler` + диалог «отменить изменения?»,
  snackbar ошибки даты (`error`/`errorNonce`), `handleBack` в navigationIcon,
  соответствующие импорты. **Исключить**: lookup-диалоги, кнопки поиска,
  статус поиска, `fieldLabel`, импорты `Search`/`MovieLinks`.
- `res/values/strings.xml` и `values-en/strings.xml`: `note_photos_failed`,
  «Импортировано записей» вместо «книг», удаление `tellico_import_dry_run`.
  **Исключить** блок `lookup_*`/`movie_lookup_*`.
- `README.md`: раздел «Как нумеровать версии в ветках» + фикс перевода
  строки. **Исключить** разделы про поиск.

**Целиком исключить:** `data/catalog/**` (main/test/androidTest),
`ui/add/AddBookScreen.kt` (все изменения — поисковые; смена лейбла
«ISBN-13»→«ISBN» косметическая и связанная с фичей — остаётся «ISBN-13»),
`data/text/Isbn.kt` и `IsbnTest.kt` (новые функции нужны только каталогам;
поведение `normalize()` не менялось), `AddBookViewModelLookupTest`,
`MovieEditViewModelLookupTest`, `AddBookViewModelTest` (+14 — lookup-тест),
`MovieDaoTest` (+26 — тесты `findByKinopoiskId`), `data/TestImages.kt`
(PNG_1PX используется только каталожными тестами), `AndroidManifest.xml`
(INTERNET), `docs/fdroid.md` (приватность каталогов), changelogs 87–90,
спеки/планы поиска и review-fixes.

### Процесс применения

1. В текущем worktree: `git switch -c port/main-generic main`.
2. Целые файлы: `git checkout af88b78 -- <paths>` (включая удаления схем).
3. Смешанные файлы: фильтрованные патчи / точечные правки по списку выше.
4. Версия 0.8.1/88, changelog 88.txt (ru/en), документы.
5. Верификация: `grep -ri 'catalog\|lookup' app/src` на портированном дереве
   (ожидается пусто), `./gradlew :app:testDebugUnitTest`,
   `./gradlew :app:assembleDebug`.
6. Коммит (хук проходит: 0.8.0→0.8.1, 87→88).
7. В worktree main: `git merge --ff-only port/main-generic`. Без push.
8. Временная ветка удаляется после подтверждения пользователем.

### Риски и их закрытие

- **Общий hunk зависит от фичи** (например, `isDirty` ссылается на `lookup`):
  собирается вручную без фичевых частей; ловится компиляцией и тестами.
- **Перемежающиеся hunk'и** в `MovieEditScreen/ViewModel`: ручная сборка,
  проверка тестами `MovieEditViewModelTest`, `MovieEditDatesTest` и сборкой.
- **Будущий мердж фичи в main**: конфликты в смешанных файлах, `88.txt`,
  `build.gradle.kts`. Общие правки на обеих сторонах идентичны — берутся
  один раз; фичевые части — со стороны ветки.

## Остаётся на усмотрение реализатора

Тексты changelog 88.txt, точный способ сборки смешанных файлов (патч или
ручная правка), порядок шагов внутри плана, формулировка сообщения коммита.
