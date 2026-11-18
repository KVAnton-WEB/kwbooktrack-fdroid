# kwbooktrack

Android book tracker. Kotlin, Jetpack Compose, Hilt, Room.

## License

GNU General Public License v3 — see [LICENSE](LICENSE).

## Publishing

F-Droid: подготовленные метаданные и пошаговая инструкция — [docs/fdroid.md](docs/fdroid.md).

## Build

    ./gradlew assembleDebug

## Tests

    ./gradlew test

## Contributing — version discipline

Every commit must bump the app version. A `pre-commit` hook enforces this:
both `versionName` and `versionCode` in `app/build.gradle.kts` must change
relative to the previous commit. The first commit of a brand-new clone is
exempt.

Enable once per clone:

    git config core.hooksPath hooks

If the hook blocks you, do the bump it asks for. For deliberate no-bump
commits (rebase, doc-only), use `git commit --no-verify`.

Self-check the hook locally:

    bash hooks/test-pre-commit.sh

### Как нумеровать версии в ветках

Две вещи, которые F-Droid и Play считают разными:

- **`versionCode`** — целое число, порядковый номер сборки. Новая опубликованная
  версия должна иметь код **больше** предыдущей, а коды разных сборок не
  должны совпадать. Именно по коду F-Droid понимает, что версия новее, и по
  коду же называется changelog.
- **`versionName`** — строка для человека (показывается в «О приложении»).
  Всё, что не мешает сортировке, — можно.

**Коды — одна сквозная нумерация на весь репозиторий.** Новое значение =
максимум по всем веткам + 1:

    git log --all --format=%h -- app/build.gradle.kts

Две ветки, начавшиеся от одного коммита, нумеруют коды независимо и
схлопываются: в этом репозитории `main` занял коды 71–86, а
`feature/isbn-lookup` — те же 71–83, но с другими сборками. Поэтому перед
бампом берём максимум по всем веткам, а не «последний в своей».

**`versionName` на ветке — с суффиксом.** Без суффикса сборка ветки
неотличима от релиза, и при слиянии в `main` пришлось бы гадать, какая из
них «настоящая». Формат:

    0.8.0            — релиз из main
    0.8.0-lookup.1   — сборка ветки фичи: базовая версия + суффикс ветки
    0.8.0-lookup.2   — следующая сборка той же ветки

Суффикс идёт **после** версии, а не перед: так строка остаётся разбираемой
парсерами, которые ждут цифры в начале (сортировка, F-Droid), и по
смыслу пре-релиз сортируется раньше релиза.

**Релиз из `main`** — всегда без суффикса: `0.8.0`, дальше `0.8.1`. Публикуем
только из `main` (F-Droid собирает по тегу), поэтому суффиксные сборки в
метаданных F-Droid не попадают.

Changelog лежит в `fastlane/metadata/android/<locale>/changelogs/` и
называется **кодом сборки**: `87.txt` — это changelog сборки с кодом 87, а не
«версии 0.8.7». Код релиза известен только на `main` — файл переименовываем
в момент релиза.