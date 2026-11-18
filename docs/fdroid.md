# Публикация в F-Droid

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
  changelog сборки 114, иконка 512×512 и по 8 скриншотов на язык.
- Ветка `fdroid`: `versionName = 0.9.5-fdroid`, `versionCode = 114`.
  Суффикс нужен, чтобы сборка из F-Droid отличалась от собственных APK
  и от сборок рабочих веток.
- Тег `v0.9.5-fdroid` на коммите с этой версией.
- `docs/fdroid/ru.antonkw.kwbooktrack.yml` — метаданные для `fdroiddata`.
- Проверено локально: если вырезать из `app/build.gradle.kts` блок `signingConfigs`
  и строку `signingConfig = ...` (именно это делает fdroidserver), сборка выдаёт
  `app-release-unsigned.apk`. F-Droid подпишет его своим ключом.

## Что нужно сделать (по порядку)

1. **Открыть репозиторий.** GitHub → Settings → Danger Zone → Change visibility → Public.
   F-Droid собирает только из публичного репозитория.

2. **Залить ветку и тег.**

       git push origin fdroid
       git push origin v0.9.5-fdroid

3. **Взять SHA тега** — он нужен в метаданных вместо `__COMMIT__`:

       git rev-parse v0.9.5-fdroid

4. **Форк `fdroiddata`**: <https://gitlab.com/fdroid/fdroiddata> → Fork, затем:

       git clone https://gitlab.com/<твой-логин>/fdroiddata.git
       cd fdroiddata
       git checkout -b ru.antonkw.kwbooktrack
       mkdir -p metadata
       cp /path/to/kwbooktrack/docs/fdroid/ru.antonkw.kwbooktrack.yml metadata/
       sed -i "s/__COMMIT__/$(git -C /path/to/kwbooktrack rev-parse v0.9.5-fdroid)/" \
         metadata/ru.antonkw.kwbooktrack.yml

5. **Проверить метаданные и сборку** (нужен [fdroidserver](https://gitlab.com/fdroid/fdroidserver)):

       pip install git+https://gitlab.com/fdroid/fdroidserver.git
       fdroid readmeta
       fdroid lint ru.antonkw.kwbooktrack
       fdroid build ru.antonkw.kwbooktrack

   То же самое делает CI форка после пуша ветки.

6. **Открыть Merge Request** в <https://gitlab.com/fdroid/fdroiddata/-/merge_requests/new>:
   source branch `ru.antonkw.kwbooktrack`, target `master`.

7. **Ответить ревьюерам.** Проверяют лицензию, отсутствие проприетарных
   зависимостей, сборку из исходников и метаданные.

8. **Дождаться сборки**: 24–48 часов после merge.

## Обновления после первой публикации

Релизы идут из `main` без суффикса: бампнуть версию, закоммитить, поставить тег
`v<versionName>` и запушить тег. `AutoUpdateMode: Version` + `UpdateCheckMode: Tags`
подхватят новую версию сами. В `fastlane/metadata/android/<locale>/changelogs/`
положить `<versionCode>.txt`, иначе версия не попадёт во вкладку Latest.

## Подводные камни

- **`subdir` указывать не нужно.** Gradle-корень проекта — корень репозитория
  (`settings.gradle.kts` там же), а не `app/`. `subdir: app` в старой версии
  метаданных был ошибкой: fdroidserver запускал бы сборку не в том каталоге.
- **Описание и картинки лежат в fastlane/, а не в fdroiddata.** Поле `Description`
  в yml перекрывает fastlane-описание — поэтому его там нет намеренно.
- **`commit:` в yml** — полный SHA тега, не имя тега.
- **Правило «3 месяца активной разработки»** (Inclusion Policy). Проект стартовал
  2026-09-29, формально не хватает до 2026-12-29. Обходится, если у приложения
  уже есть пользователи (ссылка на обсуждение) — в MR это стоит упомянуть.
- **Подпись.** `release` подписывается ключом из `keystore.properties`, если файл
  есть, иначе debug-ключом. На сервере F-Droid файла нет, а блок `signingConfigs`
  и присвоение `signingConfig` вырезаются — получается unsigned APK.
- **Лицензия в yml** — `GPL-3.0-only`. Для «или более поздняя версия» поменяйте
  на `GPL-3.0-or-later` и добавьте заголовки в исходники.
- **`gradle-wrapper.jar` в репозитории** fdroidserver удаляет сам при сканировании;
  отдельные `scandelete` не нужны.
- **Скриншоты** 1084×2412 (9:20). F-Droid их принимает, RuStore — нет; см.
  [rustore.md](rustore.md).