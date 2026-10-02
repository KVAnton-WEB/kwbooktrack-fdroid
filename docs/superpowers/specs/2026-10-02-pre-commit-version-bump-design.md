# Pre-commit version-bump hook — design

Date: 2026-10-02
Project: kwbooktrack (Android, Kotlin, Compose, Gradle)
Status: approved by user, pending written-spec review

## Goal

Block `git commit` when neither `versionName` nor `versionCode` in
`app/build.gradle.kts` has changed relative to HEAD. Every shipped commit
must carry a version bump; nothing in the history is versionless.

## Non-goals (YAGNI)

- No semver validation (X.Y.Z format, monotonic patch, no downgrades).
- No auto-bump. The developer chooses the next version.
- No commit-msg lint (`v X.Y.Z — …`). Existing convention is preserved
  by social pressure only.
- No `--no-verify` carve-out at the hook level. Git already exposes the
  override; documenting it in the hook message is enough.

## Decisions captured in brainstorming

| Question                       | Answer                                                                |
| ----------------------------- | -------------------------------------------------------------------- |
| Behavior when version is same | **Block** the commit with a clear stderr message.                     |
| Fields that must change       | **Both** `versionName` *and* `versionCode`.                          |
| How the hook is shared        | Repo file `hooks/pre-commit` + `git config core.hooksPath hooks`.      |

## Architecture

One executable file in the repo, no dependencies.

```
hooks/
  pre-commit         # bash, ~30 lines, the only required artifact
  test-pre-commit.sh # bash, ~50 lines, runnable self-check (no test framework)
```

`hooks/pre-commit` is registered through `core.hooksPath`, not copied
into `.git/hooks/`. That means it survives clones (it's in the tree) and
is enabled per-clone with one command.

### Flow (`hooks/pre-commit`)

1. Resolve the project root (`git rev-parse --show-toplevel`).
2. Read `app/build.gradle.kts` from the working tree.
3. If HEAD does not exist (`git rev-parse --verify HEAD` exits non-zero),
   exit 0 — the very first commit has no previous version to compare
   against.
4. Read `app/build.gradle.kts` from `git show HEAD:app/build.gradle.kts`.
5. Extract `versionName` and `versionCode` from both via `grep -oE`.
6. If `CUR_NAME == HEAD_NAME` **or** `CUR_CODE == HEAD_CODE`, print the
   diagnostic to stderr and exit 1.
7. Otherwise exit 0.

### Parser

```bash
# versionName = "0.5.2"   →   0.5.2
grep -oE 'versionName\s*=\s*"[^"]+"' "$f" | head -1 | grep -oE '"[^"]+"' | tr -d '"'

# versionCode = 10        →   10
grep -oE 'versionCode\s*=\s*[0-9]+' "$f" | head -1 | grep -oE '[0-9]+'
```

This is fine: `versionName` and `versionCode` are stable keys of the
Android Gradle Plugin DSL, not arbitrary Kotlin identifiers. A rename
would require a deliberate AGP migration.

### Diagnostic (stderr)

```
✗ Pre-commit: versionName and versionCode must change in this commit.

  versionName     "0.5.2" → "0.5.2"   (unchanged)
  versionCode     10      →  10       (unchanged)

  Bump at least one in app/build.gradle.kts and `git add` it,
  e.g.  versionCode = 11 / versionName = "0.5.3".

  Override with `git commit --no-verify` only for deliberate no-bump
  commits (rebase, history rewrite, doc-only).
```

## Installation (one-time per clone)

```sh
git config core.hooksPath hooks
```

Documented in a short paragraph at the top of `README.md` (or the
contributing section, if present).

## Testing

`hooks/test-pre-commit.sh` is a runnable bash script. No test framework,
no fixture library. It builds a throwaway git repo in `/tmp`, copies in
a known `app/build.gradle.kts`, and exercises three cases:

1. **First commit** (no HEAD) → hook exits 0.
2. **Both bump** (versionCode 10 → 11, versionName "0.5.2" → "0.5.3")
   → hook exits 0.
3. **Only versionCode bumps** (10 → 11, versionName unchanged)
   → hook exits 1 (proves AND semantics, not OR).
4. **Only versionName bumps** ("0.5.2" → "0.5.3", versionCode unchanged)
   → hook exits 1 (same).
5. **No bump** (only a comment) → hook exits 1,
   stderr contains the marker `✗ Pre-commit:`.

The script runs from the repo root:

```sh
bash hooks/test-pre-commit.sh
```

Exit code 0 = pass; non-zero = at least one case failed.

## Risks and ceilings

- **Bypassable with `--no-verify`.** Acceptable: git has no way to make a
  hook unforgeable, and a developer who knows about `--no-verify` is
  making a conscious choice.
- **Submodule-only changes.** If `app/build.gradle.kts` is not in any
  submodule, the working-tree read returns the staged content; the HEAD
  read returns the last committed value. Both are correct.
- **Initial repo state.** First commit is exempted to allow the project to be
  bootstrapped without bumping from a non-existent `0.0.0`.
- **Branch with rebased history.** `git show HEAD:` reads the new tip
  after rebase. Correct: we compare the working tree to *what will become
  the parent of the new commit*, which is the right thing.

## Out of scope (explicit)

- CI-side enforcement (a GitHub Actions check that grep's the diff for
  `versionName =` / `versionCode =` and fails if absent). Possible
  later; not now.
- Release notes / CHANGELOG entries from a `changeset/` directory.
  Different tool, different workflow.

## Files added

- `hooks/pre-commit`           — the hook
- `hooks/test-pre-commit.sh`   — self-check
- One paragraph in `README.md` — install instruction