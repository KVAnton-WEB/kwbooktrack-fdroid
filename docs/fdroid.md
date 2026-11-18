# Публикация в F-Droid

F-Droid собирает из **отдельного репозитория**
[KVAnton-WEB/kwbooktrack-fdroid](https://github.com/KVAnton-WEB/kwbooktrack-fdroid):
там ветка `main` — это релизная ветка F-Droid, а теги `v*-fdroid*` ставятся
только на ней. Основной репозиторий `kwbooktrack` продолжает жить своей жизнью,
и его `main` для F-Droid не используется.

Почему отдельный репозиторий: `fdroidserver` клонирует ветку по умолчанию
(обычно `main`/`master`), а в основном репозитории `main` содержит версии без
суффикса `-fdroid` и уходит вперёд. Отдельный репозиторий избавляет от ветки
`fdroid` в основном и от путаницы, какая версия «настоящая».

Готовый набор: `LICENSE` (GPL-3.0), метаданные в `fastlane/metadata/android/`
и build-метаданные в `docs/fdroid/ru.antonkw.kwbooktrack.yml`.

Официальные документы:
[Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/),
[Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/),
[описания и картинки](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/),
[Build Metadata Reference](https://f-droid.org/en/docs/Build_Metadata_Reference/).

## Что уже готово

- `LICENSE` — полный текст GNU GPL v3.
- `fastlane/metadata/android/{en-US,ru}` — название, короткое и полное описание,
  changelog сборки 115, иконка 512×512 и по 8 скриншотов на язык.
- Ветка `fdroid`: `versionName = 0.9.5-fdroid.2`, `versionCode = 115`.
  Суффикс нужен, чтобы сборка из F-Droid отличалась от собственных APK
  и от сборок рабочих веток.
- Тег `v0.9.5-fdroid.2` на коммите с этой версией.
- Ветка и тег уже запушены в `kwbooktrack-fdroid` (`main`).
- `docs/fdroid/ru.antonkw.kwbooktrack.yml` — метаданные для `fdroiddata`.
- Проверено локально: `fdroid build` с этими метаданными проходит успешно —
  собирается unsigned APK `versionName=0.9.5-fdroid.2`, `versionCode=115`,
  который F-Droid подпишет своим ключом.

## Что нужно сделать (по порядку)

1. **Открыть оба репозитория.** GitHub → Settings → Danger Zone →
   Change visibility → Public. F-Droid собирает только из публичных:
   нужен и `kwbooktrack-fdroid` (сборка), и `kwbooktrack` (ссылки в карточке).

2. **Взять SHA тега** — он нужен в метаданных вместо `__COMMIT__`.
   Тег аннотированный, поэтому `^{commit}` обязателен: без него получится SHA
   самого объекта тега, а не коммита.

       git rev-parse v0.9.5-fdroid.2^{commit}

3. **Форк `fdroiddata`**: <https://gitlab.com/fdroid/fdroiddata> → Fork, затем:

       git clone https://gitlab.com/<твой-логин>/fdroiddata.git
       cd fdroiddata
       git checkout -b ru.antonkw.kwbooktrack
       mkdir -p metadata
       cp /path/to/kwbooktrack/docs/fdroid/ru.antonkw.kwbooktrack.yml metadata/
       SHA=$(git -C /path/to/kwbooktrack rev-parse v0.9.5-fdroid.2^{commit})
       sed -i "s/__COMMIT__/$SHA/" metadata/ru.antonkw.kwbooktrack.yml

4. **Проверить метаданные и сборку** (нужен [fdroidserver](https://gitlab.com/fdroid/fdroidserver)):

       pip install git+https://gitlab.com/fdroid/fdroidserver.git
       fdroid readmeta
       fdroid lint ru.antonkw.kwbooktrack
       fdroid build ru.antonkw.kwbooktrack

   То же самое делает CI форка после пуша ветки.

5. **Открыть Merge Request** в <https://gitlab.com/fdroid/fdroiddata/-/merge_requests/new>:
   source branch `ru.antonkw.kwbooktrack`, target `master`.

6. **Ответить ревьюерам.** Проверяют лицензию, отсутствие проприетарных
   зависимостей, сборку из исходников и метаданные.

7. **Дождаться сборки**: 24–48 часов после merge.

## Обновления после первой публикации

Релиз идёт в два пуша: сначала версия с суффиксом и тег — в `kwbooktrack-fdroid`,
затем при желании та же версия без суффикса — в основной репозиторий.

    git checkout fdroid
    # бампнуть versionCode/versionName (суффикс -fdroid, номер — следующий)
    git commit -am "v <version>-fdroid — ..."
    git tag -a v<version>-fdroid
    git push fdroid HEAD:refs/heads/main
    git push fdroid refs/tags/v<version>-fdroid.2

`AutoUpdateMode: Version` + `UpdateCheckMode: Tags` подхватят новый тег сами.
В `fastlane/metadata/android/<locale>/changelogs/` положить `<versionCode>.txt`,
иначе версия не попадёт во вкладку Latest.

## Подводные камни

- **`subdir: app` обязателен.** fdroidserver запускает Gradle в `subdir` и там же
  ищет результат по пути `build/outputs/apk/release`. Наш APK появляется
  в `app/build/outputs/apk/release`, поэтому без `subdir: app` сборка
  завершается ошибкой `Failed to find any output apks`.
  Проверено: `fdroid build` с `subdir: app` проходит, без него — падает.
- **fdroidserver клонирует ветку по умолчанию**, поэтому в зеркале `main`
  должен быть именно релизной веткой. Не заливать туда `main` основного
  репозитория.
- **Описание и картинки лежат в fastlane/, а не в fdroiddata.** Поле `Description`
  в yml перекрывает fastlane-описание — поэтому его там нет намеренно.
- **`commit:` в yml** — полный SHA коммита, не имя тега.
- **Правило «3 месяца активной разработки»** (Inclusion Policy). Проект стартовал
  2026-09-29, формально не хватает до 2026-12-29. Обходится, если у приложения
  уже есть пользователи (ссылка на обсуждение) — в MR это стоит упомянуть.
- **Подпись.** `release` подписан debug-ключом, но на сервере F-Droid блок
  `signingConfigs` и строку `signingConfig` fdroidserver вырезает — получается
  unsigned APK, который он подпишет своим ключом. Проверено `fdroid build --test`.
- **Лицензия в yml** — `GPL-3.0-only`. Для «или более поздняя версия» поменяйте
  на `GPL-3.0-or-later` и добавьте заголовки в исходники.
- **`gradle-wrapper.jar` в репозитории** fdroidserver удаляет сам при сканировании;
  отдельные `scandelete` не нужны.
- **Скриншоты** 1084×2412 (9:20) — F-Droid их принимает.