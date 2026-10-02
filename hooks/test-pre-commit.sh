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

git init -q "$WORK/repo"
cd "$WORK/repo"
mkdir -p app
git config user.email t@t && git config user.name t
git config core.hooksPath "$PROJECT_ROOT/hooks"

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
sed -i 's/versionCode = 10/versionCode = 11/'          app/build.gradle.kts
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