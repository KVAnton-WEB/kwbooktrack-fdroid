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