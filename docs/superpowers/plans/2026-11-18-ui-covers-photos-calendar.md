# UI: обложки, фото заметок, календарь, зум — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Убрать обрезку/искажение обложек и фото заметок, добавить сетку дней и переходы в календаре, зум в просмотрщике фото, просмотрщик обложки в детали фильма.

**Architecture:** Правки существующих Compose-компонент (CoverImage, CalendarScreen, PhotoGalleryDialog) + один новый общий компонент NotePhotoTile + чистый класс Zoom для математики зума. Без новых зависимостей.

**Tech Stack:** Kotlin, Jetpack Compose (foundation gestures, graphicsLayer), Coil AsyncImage, JUnit4 (юнит), adb + uiautomator (визуальная проверка).

**Spec:** `docs/superpowers/specs/2026-11-18-ui-covers-photos-calendar-design.md`

## Global Constraints

- Без новых зависимостей (зум — штатные жесты Compose).
- Не трогать обложки в AddBookScreen:791-803, MovieEditScreen:448-460, NoteDetailScreen:315-320 (уже 2:3).
- Не менять данные, экспорт/импорт, маршруты навигации.
- Коммиты делает пользователь (установленный порядок сессии); в репо параллельно работает другая сессия (фильмовые каталоги) — её файлы не трогать, её RED-фаза может временно ронять компиляцию (повторить прогон).
- Layout-правки без юнит-тестов (Compose UI-test инфраструктуры нет, заводить — не-цель спеки): верификация = сборка + скриншот на устройстве. TDD обязателен для логики зума (Task 4).
- Устройство: Nothing Phone A142, 1084×2412; скриншоты «до»: /tmp/opencode/uicheck/00..13; «после»: /tmp/opencode/uifix/.
- Правило пользователя: перед свайпом убедиться, что клавиатура закрыта.

## Review Focus

1. Pan при зуме не уводит картинку за границы (чёрные поля сверх леттербокса) — клампинг в Zoom.transform, юнит-тест Task 4 + device double-tap/pan.
2. Конфликт свайпа страниц и pan: при scale>1 горизонтальный drag панит, при scale==1 листает страницы — device-проверка Task 5.
3. Клик по обложке в календаре ведёт в деталь правильного типа (фильм→movieDetail, книга→bookDetail) — device-проверка обоих типов Task 3.
4. В редакторе заметки кнопка удаления фото остаётся рабочей поверх нового тайла, тап по фото открывает просмотрщик — device-проверка Task 2.
5. Placeholder обложки (нет файла) рендерится в новом ratio 2:3; onClick обложки в детали книги по-прежнему открывает просмотрщик — device-проверка Task 1/6.

---

### Task 1: Обложки 2:3 (CoverImage + сетка)

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/common/CoverImage.kt:33-36`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/library/LibraryScreen.kt:506-511` (BookCoverCard), `:672-677` (MovieCoverCard)

**Interfaces:**
- Produces: CoverImage(sizeDp) теперь рисует коробку высотой sizeDp и шириной sizeDp×2/3; сигнатура не меняется, вызывающие не правятся.

- [ ] **Step 1:** В CoverImage заменить `modifier.size(sizeDp.dp)` на `modifier.height(sizeDp.dp).aspectRatio(2f / 3f)` (Crop оставить).
- [ ] **Step 2:** В LibraryScreen обе карточки сетки: `aspectRatio(3f / 4f)` → `aspectRatio(2f / 3f)`.
- [ ] **Step 3:** `./gradlew :app:assembleDebug` → BUILD SUCCESSFUL; `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- [ ] **Step 4:** Device: библиотека список (книги) → скрин `/tmp/opencode/uifix/01-list-books.png`; сетка книг → `02-grid-books.png`; dropdown → Фильмы, список+сетка → `03-list-movies.png`, `04-grid-movies.png`. Обложки целиком по высоте, без искажений; placeholder (книга без обложки) — коробка 2:3. Сравнить с uicheck/02,03.

### Task 2: NotePhotoTile — единый вид фото заметок

**Files:**
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/common/NotePhotoTile.kt`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/notes/NoteEditorScreen.kt:262-274`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/notes/NoteDetailScreen.kt:353-366`
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/books/BookDetailScreen.kt:565-574` (NoteRow), `:611-620` (QuoteRow)

**Interfaces:**
- Produces: `@Composable fun NotePhotoTile(path: String, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null)` — 120dp квадрат, clip RoundedCornerShape(8.dp), background surfaceVariant, AsyncImage(photoImageModel(path), ContentScale.Fit, fillMaxSize), clickable если onClick != null. photoImageModel — существующая функция из PhotoGalleryDialog.kt.

- [ ] **Step 1:** Создать NotePhotoTile с сигнатурой выше.
- [ ] **Step 2:** NoteEditorScreen: заменить 96dp Crop-бокс на NotePhotoTile(path, onClick = существующий клик просмотра); кнопку удаления 28dp оставить в Box-обёртке TopEnd поверх тайла.
- [ ] **Step 3:** NoteDetailScreen: заменить 120dp Crop на NotePhotoTile(path, onClick = существующий клик → PhotoGalleryDialog).
- [ ] **Step 4:** BookDetailScreen NoteRow/QuoteRow: баннер fillMaxWidth×160/140 Crop → NotePhotoTile(path) без onClick (клик строки ведёт в заметку, как сейчас).
- [ ] **Step 5:** assembleDebug + install. Device: редактор заметки с фото (`05-editor.png`) — тайл 120dp, фото целиком, удаление работает (Review Focus 4); деталь заметки (`06-note.png`); деталь книги — строка заметки с фото (`07-booknote.png`), тап по строке → заметка, тап по фото в заметке → просмотрщик. Одно фото выглядит одинаково на всех трёх экранах.

### Task 3: Календарь — Fit, сетка дней, клик → деталь

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/calendar/CalendarScreen.kt:61-65` (сигнатура), `:242-329` (DayCell), `:332-358` (CoverTile)
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/navigation/KwBooktrackNavHost.kt:113-115`

**Interfaces:**
- Consumes: Routes.bookDetail(id)/Routes.movieDetail(id), MediaType — существующие.
- Produces: `CalendarScreen(onBack: () -> Unit, onOpenMedia: (MediaType, Long) -> Unit)`; DayCell/CoverTile принимают и пробрасывают onOpenMedia; тип дня = if (isMovies) MediaType.MOVIES else MediaType.BOOKS.

- [ ] **Step 1:** CoverTile: ContentScale.Crop → Fit; добавить `clickable { onOpenMedia(mediaType, book.id) }` (параметры пробросить).
- [ ] **Step 2:** DayCell: граница ячейки `Modifier.border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)` (сетка дней); проброс onOpenMedia в CoverTile.
- [ ] **Step 3:** CalendarScreen: параметр onOpenMedia, проброс в DayCell.
- [ ] **Step 4:** NavHost: `CalendarScreen(onBack = {...}, onOpenMedia = { type, id -> navController.navigate(if (type == MediaType.MOVIES) Routes.movieDetail(id) else Routes.bookDetail(id)) })` — зеркало NoteDetail:139-147.
- [ ] **Step 5:** assembleDebug + install. Device: календарь → заголовок месяца → год 2025 → Фев → OK (`08-calendar.png`): обложки целиком (Fit), видна сетка границ дней. Тап по обложке фильма (2 или 22 февраля) → деталь фильма (`09-movie-from-cal.png`), назад; переключить календарь на книги (тумблер Обложки/Время или тип носителя — проверить оба типа, Review Focus 3), тап по обложке книги → деталь книги.

### Task 4: Zoom — чистая математика (TDD)

**Files:**
- Create: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/common/Zoom.kt`
- Test: `app/src/test/kotlin/ru/antonkw/kwbooktrack/ui/common/ZoomTest.kt`

**Interfaces:**
- Produces: `data class Zoom(val scale: Float = 1f, val offsetX: Float = 0f, val offsetY: Float = 0f)` с `fun transform(pan: Offset, zoomFactor: Float, size: IntSize, imageSize: IntSize = size): Zoom`, `fun doubleTap(size: IntSize): Zoom`; companion: `MAX_SCALE = 4f`, `DOUBLE_TAP_SCALE = 2.5f`. Offset/IntSize — androidx.compose.ui.geometry/unit (JVM-совместимы). Клампинг: scale ∈ [1, 4]; при scale==1 offsets=0; иначе offsets ∈ ±max(0, (imageSize×fitScale×scale − size)/2) по каждой оси, где fitScale = ContentScale.Fit.computeScaleFactor(imageSize, size) — граница ВПИСАННОГО фото, не контейнера (иначе фото уводится в леттербокс; дефект исходной формулы ±size×(scale−1)/2 найден финальным ревью, исправлен в фикс-пассе). imageSize==Zero (фото ещё грузится) → pan запрещён. Зум к центру (centroid не учитывается — решение реализатора, спека формулу не фиксирует).

- [ ] **Step 1: failing test** — ZoomTest (JUnit4):

```kotlin
private val size = IntSize(1000, 2000)
@Test fun scaleClampedToMax() { assertEquals(4f, Zoom().transform(Offset.Zero, 100f, size).scale) }
@Test fun scaleNeverBelowOneAndOffsetsReset() { assertEquals(Zoom(), Zoom(2f, 10f, 10f).transform(Offset.Zero, 0.01f, size)) }
@Test fun panClampedToBounds() { val z = Zoom(2f).transform(Offset(10000f, 10000f), 1f, size); assertEquals(500f, z.offsetX); assertEquals(1000f, z.offsetY) }
@Test fun atScaleOnePanIgnored() { assertEquals(Zoom(), Zoom().transform(Offset(50f, 50f), 1f, size)) }
@Test fun doubleTapZoomsIn() { assertEquals(2.5f, Zoom().doubleTap(size).scale) }
@Test fun doubleTapZoomsOutFully() { assertEquals(Zoom(), Zoom(2.5f, 100f, 100f).doubleTap(size)) }
```

- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests "*ZoomTest"` → FAIL (unresolved reference: Zoom).
- [ ] **Step 3:** Реализовать Zoom по сигнатуре и правилам клампинга выше.
- [ ] **Step 4:** Тот же прогон → PASS (6 тестов).

### Task 5: Зум в PhotoGalleryDialog

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/common/PhotoGalleryDialog.kt`

**Interfaces:**
- Consumes: Zoom (Task 4).

- [ ] **Step 1:** В странице пейджера: `var zoom by remember { mutableStateOf(Zoom()) }`; `LaunchedEffect(pagerState.currentPage) { zoom = Zoom() }`; Box c `pointerInput { detectTransformGestures { _, pan, f, _ -> zoom = zoom.transform(pan, f, size) } }` + `pointerInput { detectTapGestures(onDoubleTap = { zoom = zoom.doubleTap(size) }) }`; AsyncImage modifier += `graphicsLayer { scaleX = zoom.scale; scaleY = zoom.scale; translationX = zoom.offsetX; translationY = zoom.offsetY }`.
- [ ] **Step 2:** HorizontalPager: `userScrollEnabled = zoom.scale == 1f`.
- [ ] **Step 3:** assembleDebug + install. Device: деталь заметки → фото → просмотрщик (`10-viewer.png`): double-tap → зум 2.5× (`11-zoom.png`), свайп по зумнутой картинке → pan без смены страницы и без ухода за границы (Review Focus 1,2); double-tap → сброс; свайп → следующая страница, зум сброшен; счётчик «N / M» и Close работают.

### Task 6: Просмотрщик обложки в детали фильма

**Files:**
- Modify: `app/src/main/kotlin/ru/antonkw/kwbooktrack/ui/movies/MovieDetailScreen.kt:155-159`

**Interfaces:**
- Consumes: PhotoGalleryDialog, photoImageModel — существующие; зеркало BookDetailScreen:180-189 (showCoverViewer state + onClick обложки).

- [ ] **Step 1:** Добавить state showCoverViewer, onClick обложки (если coverPath != null) и PhotoGalleryDialog зеркально BookDetailScreen:180-189.
- [ ] **Step 2:** assembleDebug + install. Device: деталь фильма с обложкой → тап по обложке → просмотрщик (`12-movie-cover-viewer.png`), зум работает; фильм без обложки — placeholder 2:3, клика нет (Review Focus 5). Деталь книги: обложка по-прежнему открывает просмотрщик.

### Task 7: Финальная верификация

- [ ] **Step 1:** `./gradlew :app:testDebugUnitTest` → BUILD SUCCESSFUL, 0 failures (сводка по XML одной командой сразу после прогона — параллельная сессия затирает debug XML release-прогоном).
- [ ] **Step 2:** `adb logcat -d | grep -c "FATAL EXCEPTION"` → 0 за сессию проверки.
- [ ] **Step 3:** Финальный обход всех экранов из Tasks 1-6, скриншоты в /tmp/opencode/uifix/; сравнение с «до» (uicheck/). Отчёт пользователю: что изменено, скриншоты до/после, остаточные риски.
