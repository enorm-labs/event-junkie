#!/usr/bin/env bash
#
# release-highlights-test.sh — assert what `scripts/release-highlights.sh` puts on top of the notes.
#
# The summary is the first thing a reader of the Releases page sees, and every way it can be wrong
# is quiet: a version bump listed as a highlight, a breaking change buried under a feature, or an
# empty heading on a maintenance release. So each rule is asserted against a fabricated repository
# with known commits and a tag, driven through VERSION_GIT_ROOT.
#
# Usage: scripts/release-highlights-test.sh
#
# Requires: git. Reaches no network and writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
  -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HIGHLIGHTS_SH="$REPO_ROOT/scripts/release-highlights.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0

fail() {
  printf '  FAIL  %s\n' "$1" >&2
  if [ -n "${2:-}" ]; then printf '%s\n' "$2" | sed 's/^/          /' >&2; fi
  failures=$((failures + 1))
}

pass() {
  printf '  ok    %s\n' "$1"
}

# A fresh repository whose first commit is tagged v1.0.0 — the release every case measures from.
fresh_repo() {
  local dir
  dir="$(mktemp -d "$WORK/repo.XXXXXX")"
  git -C "$dir" init -q -b main
  git -C "$dir" config user.name test
  git -C "$dir" config user.email test@example.invalid
  git -C "$dir" config commit.gpgsign false
  git -C "$dir" commit -q --allow-empty -m "chore(release): the release"
  git -C "$dir" tag v1.0.0
  printf '%s\n' "$dir"
}

commit() {
  local dir="$1" subject="$2" body="${3:-}"
  if [ -n "$body" ]; then
    git -C "$dir" commit -q --allow-empty -m "$subject" -m "$body"
  else
    git -C "$dir" commit -q --allow-empty -m "$subject"
  fi
}

highlights() {
  local dir="$1"
  shift
  VERSION_GIT_ROOT="$dir" "$HIGHLIGHTS_SH" "$@"
}

assert_contains() {
  local description="$1" expected="$2" actual="$3"
  case "$actual" in
    *"$expected"*) pass "$description" ;;
    *) fail "$description" "expected to contain: $expected
actual:
$actual" ;;
  esac
}

assert_lacks() {
  local description="$1" unexpected="$2" actual="$3"
  case "$actual" in
    *"$unexpected"*) fail "$description" "expected NOT to contain: $unexpected
actual:
$actual" ;;
    *) pass "$description" ;;
  esac
}

printf 'release-highlights.sh\n'

# --- What reaches the summary, and what does not -----------------------------------------------
repo="$(fresh_repo)"
commit "$repo" "feat(frontend): add a compact view that hides the posters"
commit "$repo" "fix(importer): keep a descriptor on a three-letter initialism"
commit "$repo" "chore(release): open the next development version at 1.0.1-SNAPSHOT"
commit "$repo" "chore(deps): bump kotlin from 2.4.10 to 2.4.20"
commit "$repo" "docs(adr): record the scraping decision"
commit "$repo" "refactor(modulith): drop two allowedDependencies entries"
commit "$repo" "ci: pin actionlint"
commit "$repo" "feat(ci): Nuclei runs behind ZAP in dast-k3d"
out="$(highlights "$repo")"

assert_contains "a feature is named" "- Add a compact view that hides the posters" "$out"
assert_contains "a fix is named" "- Keep a descriptor on a three-letter initialism" "$out"
assert_lacks "this project's own version bump is left out" "development version" "$out"
assert_lacks "a dependency bump is left out" "bump kotlin" "$out"
assert_lacks "documentation is left out" "scraping decision" "$out"
assert_lacks "a refactor is left out" "allowedDependencies" "$out"
assert_lacks "CI work is left out" "actionlint" "$out"
assert_lacks "a feat outside a product scope is left out, whatever its type says" "Nuclei" "$out"
assert_contains "the counts read in order" "**1 feature · 1 fix** since v1.0.0." "$out"

# --- A breaking change comes first and says so --------------------------------------------------
repo="$(fresh_repo)"
commit "$repo" "feat(frontend): add a compact view"
commit "$repo" "feat(bff)!: drop the legacy events endpoint"
commit "$repo" "fix(bff): correct a page size" "BREAKING CHANGE: the default page size is now 24"
out="$(highlights "$repo")"

assert_contains "a marked breaking change is labelled" "- **Breaking:** Drop the legacy events endpoint" "$out"
assert_contains "a footer-only breaking change is labelled" "- **Breaking:** Correct a page size" "$out"
first="$(printf '%s' "$out" | grep '^- ' | head -1)"
assert_contains "breaking changes are listed first" "**Breaking:**" "$first"
assert_contains "breaking changes are counted" "2 breaking changes" "$out"

# --- A new venue importer is counted as an event source -----------------------------------------
repo="$(fresh_repo)"
commit "$repo" "feat(importer): import ROSA from its flight payload"
out="$(highlights "$repo")"

assert_contains "an importer feature counts as an event source" "**1 new event source** since v1.0.0." "$out"

# --- The cap, and the empty case ----------------------------------------------------------------
repo="$(fresh_repo)"
for index in 1 2 3 4 5 6 7; do commit "$repo" "feat(frontend): feature $index"; done
out="$(highlights "$repo")"
count="$(printf '%s' "$out" | grep -c '^- ')"
if [ "$count" = 5 ]; then pass "at most five bullets by default"; else fail "at most five bullets by default" "got $count"; fi

out="$(highlights "$repo" --max 2)"
count="$(printf '%s' "$out" | grep -c '^- ')"
if [ "$count" = 2 ]; then pass "--max caps the bullets"; else fail "--max caps the bullets" "got $count"; fi

repo="$(fresh_repo)"
commit "$repo" "chore(release): open the next development version at 1.0.1-SNAPSHOT"
commit "$repo" "ci: pin actionlint"
out="$(highlights "$repo")"
if [ -z "$out" ]; then
  pass "a release worth no highlights prints nothing at all"
else
  fail "a release worth no highlights prints nothing at all" "$out"
fi

printf '\n'
if [ "$failures" -gt 0 ]; then
  printf '%d assertion(s) failed\n' "$failures" >&2
  exit 1
fi
printf 'All assertions passed.\n'
