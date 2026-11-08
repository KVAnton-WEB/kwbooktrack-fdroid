# Публикация в F-Droid

Готовый набор: `LICENSE` (GPL-3.0), метаданные в `fastlane/metadata/android/`
и build-метаданные для `fdroiddata` (готовый файл — в конце этого документа).

Официальные документы:
[Inclusion Policy](https://f-droid.org/en/docs/Inclusion_Policy/),
[Quick Start Guide](https://f-droid.org/en/docs/Submitting_to_F-Droid_Quick_Start_Guide/),
[описания и картинки](https://f-droid.org/en/docs/All_About_Descriptions_Graphics_and_Screenshots/).

## Что уже сделано

- `LICENSE` — полный текст GNU GPL v3.
- `fastlane/metadata/android/en-US` и `fastlane/metadata/android/ru` — название, короткое и полное
  описание, changelog для 69 (версия 0.6.4), иконка 512×512 и скриншоты
  (6 для en-US, 5 для ru).
- Тег `v0.6.4` на коммите с этими файлами: F-Droid соберёт APK именно из него.
- Проверено локально: `./gradlew assembleRelease` собирается (unsigned APK — его подпишет сам F-Droid).

## Что нужно сделать (по порядку)

1. **Открыть репозиторий.** GitHub → Settings → Danger Zone → Change visibility → Public.
   Сейчас репозиторий приватный, а F-Droid собирает только из публичного: без этого пункт 5 не пройдёт.

2. **Залить ветку и тег.**

       git push origin main
       git push origin v0.6.4

3. **Скриншоты уже сняты** — `fastlane/metadata/android/{en-US,ru}/images/phoneScreenshots/`.
   Заменить их на свои можно так (интерфейс приложения русский; для английских кадров
   переключите язык приложения в системе: Настройки → Приложения → kwbooktrack → Язык → English):

       adb shell am start -n ru.antonkw.kwbooktrack/.MainActivity
       adb exec-out screencap -p > fastlane/metadata/android/ru/images/phoneScreenshots/1.png

4. **Форк `fdroiddata`**: <https://gitlab.com/fdroid/fdroiddata> → Fork, затем:

       git clone https://gitlab.com/<твой-логин>/fdroiddata.git
       cd fdroiddata
       git checkout -b ru.antonkw.kwbooktrack
       mkdir -p metadata
       # создай metadata/ru.antonkw.kwbooktrack.yml и вставь содержимое
       # из раздела «Метаданные для fdroiddata» в конце этого документа

   В файле должен стоять реальный SHA тега: `git rev-parse v0.6.4` в клоне kwbooktrack.

5. **Проверить метаданные и сборку** (нужен [fdroidserver](https://gitlab.com/fdroid/fdroidserver)):

       pip install git+https://gitlab.com/fdroid/fdroidserver.git
       fdroid readmeta
       fdroid lint ru.antonkw.kwbooktrack
       fdroid build ru.antonkw.kwbooktrack

   То же самое автоматически делает CI форка после пуша ветки.

6. **Открыть Merge Request** в <https://gitlab.com/fdroid/fdroiddata/-/merge_requests/new>:
   source branch `ru.antonkw.kwbooktrack`, target `master`.

7. **Ответить ревьюерам.** Они проверяют лицензию в репозитории, отсутствие проприетарных
   зависимостей, сборку из исходников и метаданные.

8. **Дождаться сборки**: 24–48 часов после merge.

## Обновления после первой публикации

Бампнуть `versionName`/`versionCode`, закоммитить, поставить тег `v<versionName>` и запушить тег.
F-Droid подхватит новую версию сам (`AutoUpdateMode: Version`, `UpdateCheckMode: Tags`).
В `fastlane/metadata/android/<locale>/changelogs/` положить `<versionCode>.txt` — без него
версия не попадёт во вкладку Latest.

## Подводные камни

- **Описание и картинки лежат в fastlane/, а не в fdroiddata.** Поле `Description` в yml
  перекрывает fastlane-описание — поэтому в yml его нет намеренно.
- **`commit:` в yml** обязан совпадать с SHA коммита, который помечен тегом `v0.6.4`.
- **Правило «3 месяца активной разработки»** (Inclusion Policy). Проект стартовал 2026-09-29,
  то есть формально не хватает до 2026-12-29. Обходится, если у приложения уже есть
  пользователи (ссылка на обсуждение) — в MR это стоит упомянуть.
- **Идентификатор приложения** должен быть уникален и желательно основан на вашем домене
  (`ru.antonkw.kwbooktrack`). Домен `antonkw.ru` стоит иметь в виду.
- **Лицензия в yml** — `GPL-3.0-only`. Если предпочитаете «или более поздняя версия»,
  поменяйте на `GPL-3.0-or-later` и добавьте соответствующие заголовки в исходники.

## Метаданные для fdroiddata

Файл `metadata/ru.antonkw.kwbooktrack.yml` в форке `fdroiddata`. Описание и картинки
намеренно не дублируются: они лежат в `fastlane/` и F-Droid берёт их оттуда.

```yaml
Categories:
  - Inventory
  - Note
License: GPL-3.0-only
AuthorName: KVAnton-WEB
AuthorEmail: KVAnton.WEB@yandex.ru
WebSite: https://github.com/KVAnton-WEB/kwbooktrack
SourceCode: https://github.com/KVAnton-WEB/kwbooktrack
IssueTracker: https://github.com/KVanton-WEB/kwbooktrack/issues

RepoType: git
Repo: https://github.com/KVAnton-WEB/kwbooktrack.git

Builds:
  - versionName: 0.6.4
    versionCode: 69
    commit: <SHA коммита тега v0.6.4>
    subdir: app
    gradle:
      - yes

AutoUpdateMode: Version
UpdateCheckMode: Tags
CurrentVersion: 0.6.4
CurrentVersionCode: 69

MaintainerNotes: |-
  Descriptions, changelogs and images live in the app repository
  (fastlane/metadata/android/en-US and fastlane/metadata/android/ru).
  Do not duplicate them here.
```

Что поправить перед подачей: `AuthorName`/`AuthorEmail` (сейчас взяты из git-конфига),
`WebSite` (если есть сайт приложения — он же пригодится для политики конфиденциальности
в Huawei AppGallery) и `commit`.
