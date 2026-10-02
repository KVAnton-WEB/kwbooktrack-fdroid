# Pre-commit version-bump hook — implementation plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `pre-commit` git hook that blocks any commit in which neither `versionName` nor `versionCode` in `app/build.gradle.kts` has changed relative to HEAD, and document the one-time per-clone setup.

**Architecture:** One executable bash file (`hooks/pre-commit`) registered via `git config core.hooksPath hooks`. A second bash file (`hooks/test-pre-commit.sh`) provides a runnable self-check that builds a throwaway repo in `/tmp` and exercises five scenarios. No Gradle changes, no CI changes, no new dependencies.

**Tech Stack:** bash, grep, git. No frameworks, no test runners.

**Spec:** `docs/superpowers/specs/2026-10-02-pre-commit-version-bump-design.md`

## Global Constraints

- AND semantics: a commit is blocked when `versionName == HEAD.versionName` **or** `versionCode == HEAD.versionCode` (equivalently, allowed only when **both** differ).
- Initial commit (no HEAD yet) is exempt — exit 0.
- Hook must work without Gradle installed (no `./gradlew` calls).
- Hook reads the file at `app/build.gradle.kts` from the git toplevel.
- `--no-verify` override is documented in the hook's stderr message only.
- Commit messages follow the project convention: `v X.Y.Z — <description>`.
- The hook file is `hooks/pre-commit` (no extension), executable (`chmod +x`).
- No semver validation, no auto-bump, no commit-msg lint.

---

### Task 1: Pre-commit version-bump hook + tests

**Files:**
- Create: `hooks/pre-commit`           (executable bash, the hook itself)
- Create: `hooks/test-pre-commit.sh`   (bash, runnable self-check, NOT a git hook — needs no extension, executable)

**Testable deliverable:** `bash hooks/test-pre-commit.sh` exits 0 with all five scenarios verified.

- [ ] **Step 1: Write the failing self-check `hooks/test-pre-commit.sh`**

Create `hooks/test-pre-commit.sh` with executable bits set. The script must, for each of the five scenarios, build a tiny throwaway git repo in `/tmp`, install the real `hooks/pre-commit` from the project root as that repo's hook via `core.hooksPath`, perform a `git commit` (real, with `--no-verify` *not* used), and assert the exit code. Implementation:

```bash
#!/usr/bin/env bash
# Self-check for hooks/pre-commit. Not a git hook — just a runnable bash test.
# Exits 0 iff all five scenarios behave as documented in
# docs/superpowers/specs/2026-10-02-pre-commit-version-bump-design.md.

set -u

PROJECT_ROOT="$(git rev-parse --show-toplevel)"
HOOK="$PROJECT_ROOT/hooks/pre-commit"
[[ -x "$HOOK" ]] || { echo "FAIL: $HOOK not executable"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# Build a throwaway repo with app/build.gradle.kts and the hook installed.
git init -q "$WORK/repo"
cd "$WORK/repo"
mkdir -p app
git config user.email t@t && git config user.name t
git config core.hooksPath "$PROJECT_ROOT/hooks"
cp "$PROJECT_ROOT/hooks/pre-commit" "$WORK/_backup_hook"

cat > app/build.gradle.kts <<'EOF'
android {
    defaultConfig {
        versionCode = 10
        versionName = "0.5.2"
    }
}
EOF

fail=0
assert_exit () {
  local label="$1" expected="$2" actual="$3"
  if [[ "$expected" != "$actual" ]]; then
    echo "FAIL [$label]: exit $actual, want $expected"
    fail=1
  else
    echo "OK   [$label]: exit $actual"
  fi
}

# --- Scenario 1: first commit (no HEAD) — must exit 0 ---
git add app/build.gradle.kts
out=$(git commit -q -m "v 0.5.2 — initial" 2>&1); code=$?
assert_exit "first commit (no HEAD)" 0 "$code"

# --- Scenario 2: both bump — must exit 0 ---
sed -i 's/versionCode = 10/versionCode = 11/'         app/build.gradle.kts
sed -i 's/versionName = "0.5.2"/versionName = "0.5.3"/' app/build.gradle.kts
git add app/build.gradle.kts
out=$(git commit -q -m "bump both" 2>&1); code=$?
assert_exit "both bump" 0 "$code"
[[ "$fail" -eq 0 ]] || exit 1

# --- Scenario 3: only versionCode bumps — must exit 1 ---
sed -i 's/versionCode = 11/versionCode = 12/' app/build.gradle.kts
git add app/build.gradle.kts
out=$(git commit -q -m "bump code only" 2>&1); code=$?
assert_exit "only versionCode bumps" 1 "$code"
[[ "$out" == *"✗ Pre-commit:"* ]] || { echo "FAIL: missing ✗ Pre-commit: in stderr"; fail=1; }

# --- Scenario 4: only versionName bumps — must exit 1 ---
# Roll versionCode to original, then bump versionName again.
git checkout -q HEAD -- app/build.gradle.kts
sed -i 's/versionName = "0.5.3"/versionName = "0.6.0"/' app/build.gradle.kts
git add app/build.gradle.kts
out=$(git commit -q -m "bump name only" 2>&1); code=$?
assert_exit "only versionName bumps" 1 "$code"

# --- Scenario 5: no bump (only comment) — must exit 1 ---
git checkout -q HEAD -- app/build.gradle.kts
sed -i 's/versionCode = 11/versionCode = 11  # no-op/' app/build.gradle.kts
git add app/build.gradle.kts
out=$(git commit -q -m "no bump" 2>&1); code=$?
assert_exit "no bump at all" 1 "$code"

exit "$fail"
```

Make it executable: `chmod +x hooks/test-pre-commit.sh`.

- [ ] **Step 2: Run the test — must fail (no hook yet)**

Run from the repo root: `bash hooks/test-pre-commit.sh`
Expected: FAIL because `hooks/pre-commit` does not exist yet (script will exit 1 with "FAIL: /.../hooks/pre-commit not executable" and "FAIL [first commit (no HEAD)]: exit 1, want 0").

- [ ] **Step 3: Write `hooks/pre-commit`**

Create the hook:

```bash
#!/usr/bin/env bash
# Pre-commit hook: require versionName AND versionCode to change.
# Spec: docs/superpowers/specs/2026-10-02-pre-commit-version-bump-design.md
# Install (one-time per clone):
#     git config core.hooksPath hooks

set -u

GRADLE="app/build.gradle.kts"
ROOT="$(git rev-parse --show-toplevel)"
FILE="$ROOT/$GRADLE"

# Initial commit: no HEAD yet — nothing to compare against.
git rev-parse --verify -q HEAD >/dev/null || exit 0

# Bail if the file is missing in either side.
if [[ ! -f "$FILE" ]] || ! git cat-file -e "HEAD:$GRADLE" 2>/dev/null; then
  echo "✗ Pre-commit: $GRADLE missing from working tree or HEAD; hook disabled." >&2
  exit 1
fi

CUR_RAW=$(git show    :"$GRADLE" 2>/dev/null || cat "$FILE")
HEAD_RAW=$(git show HEAD:"$GRADLE" 2>/dev/null)

extract_name () { printf '%s\n' "$1" | grep -oE 'versionName\s*=\s*"[^"]+"' | head -1 | grep -oE '"[^"]+"' | tr -d '"'; }
extract_code () { printf '%s\n' "$1" | grep -oE 'versionCode\s*=\s*[0-9]+'      | head -1 | grep -oE '[0-9]+'; }

CUR_NAME=$(extract_name "$CUR_RAW")
HEAD_NAME=$(extract_name "$HEAD_RAW")
CUR_CODE=$(extract_code "$CUR_RAW")
HEAD_CODE=$(extract_code "$HEAD_RAW")

if [[ "$CUR_NAME" != "$HEAD_NAME" && "$CUR_CODE" != "$HEAD_CODE" ]]; then
  exit 0
fi

cat >&2 <<EOF
✗ Pre-commit: versionName and versionCode must change in this commit.

  versionName     "$HEAD_NAME" → "$CUR_NAME"
  versionCode     $HEAD_CODE      → $CUR_CODE

  Bump at least one in $GRADLE and \`git add\` it,
  e.g.  versionCode = $((HEAD_CODE + 1)) / versionName = "<next>".

  Override with \`git commit --no-verify\` only for deliberate no-bump
  commits (rebase, history rewrite, doc-only).
EOF
exit 1
```

Make it executable: `chmod +x hooks/pre-commit`.

> Implementation note: the `git show :"$GRADLE"` form reads the index, which is what `git commit` is about to commit — i.e. the staged content, not the working-tree content. This matches the developer's intent at commit time.

- [ ] **Step 4: Run the test — must pass**

Run from the repo root: `bash hooks/test-pre-commit.sh`
Expected: five `OK   [...]` lines, exit 0.

- [ ] **Step 5: Commit**

```sh
git add hooks/pre-commit hooks/test-pre-commit.sh
git commit -m "v 0.5.3 — add pre-commit hook that requires versionName + versionCode to change"
```

(Note: the hook is not active in this clone because `core.hooksPath` is not yet configured. The commit goes through normally. Step 6 of Task 2 is the first time the hook will actually run for this repo.)

---

### Task 2: README + one-time install verification

**Files:**
- Create: `README.md` (the project has none today)

**Testable deliverable:** a fresh clone of this repo, after running the one install command documented in the README, blocks a no-bump commit and allows a bumped commit. We verify this by running the install command *here* (in this clone) and observing that the upcoming tasks trigger or get blocked correctly.

- [ ] **Step 1: Write `README.md`**

```markdown
# kwbooktrack

Android book tracker. Kotlin, Jetpack Compose, Hilt, Room.

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
```

- [ ] **Step 2: Verify the hook blocks here**

From the repo root:

```sh
git config core.hooksPath hooks
echo "# dummy" >> app/build.gradle.kts
git add app/build.gradle.kts
git commit -m "no bump test"
```

Expected: `git commit` fails with the hook's stderr block, exit 1, working tree left dirty. Then reset the staged change:

```sh
git restore --staged app/build.gradle.kts
git checkout -- app/build.gradle.kts
```

- [ ] **Step 3: Verify the hook allows a real bump**

```sh
sed -i 's/versionCode = 10/versionCode = 11/'         app/build.gradle.kts
sed -i 's/versionName = "0.5.2"/versionName = "0.5.3"/' app/build.gradle.kts
git add app/build.gradle.kts
git commit -m "v 0.5.3 — install core.hooksPath = hooks in this clone"
```

Expected: commit succeeds. (This commit *is* a real bump and is allowed.)

- [ ] **Step 4: Disable the hook for the rest of this session if not desired**

The hook is now active in this clone. Disable it with:

```sh
git config --unset core.hooksPath
```

(Keep it active if you want to feel the rule. Either way, this preference lives in `.git/config`, not in the repo, so other clones are unaffected.)

---

## Self-review notes

- Spec section "Architecture" → Task 1 (hook + test).
- Spec section "Installation" → Task 2 Step 1 (README paragraph).
- Spec section "Testing" five scenarios → Task 1 Step 1 test script.
- Spec section "Risks and ceilings" — bypassable with `--no-verify` is documented in the hook stderr (Task 1 Step 3) and the README (Task 2 Step 1).
- No semver validation, no auto-bump, no commit-msg lint — none implemented, all expected.
- Global constraints carried through both tasks.