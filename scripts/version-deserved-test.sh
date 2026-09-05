#!/usr/bin/env bash
#
# version-deserved-test.sh — assert what `scripts/version.sh deserved` decides.
#
# The command turns the commits since the last release into a version number, and the rule it
# applies (docs/ops/RELEASING.md § What a release deserves) is what `cut-release.yml` enforces. A
# wrong verdict is not a red build: it is a release numbered as if nothing changed, or a raise
# pull request for a fix-only cycle. So each rule is asserted here against a fabricated repository
# with known commits and tags, driven through VERSION_GIT_ROOT.
#
# Usage: scripts/version-deserved-test.sh
#
# Requires: git. Reaches no network and writes only under a temp dir it removes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_SH="$REPO_ROOT/scripts/version.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0

fail() {
  printf '  FAIL  %s\n' "$1" >&2
  if [[ -n "${2:-}" ]]; then printf '%s\n' "$2" | sed 's/^/          /' >&2; fi
  failures=$((failures + 1))
}

pass() {
  printf '  ok    %s\n' "$1"
}

# A fresh repository with one commit tagged [tag], as the release every case measures from.
fresh_repo() {
  local tag="$1" dir
  dir="$(mktemp -d "$WORK/repo.XXXXXX")"
  git -C "$dir" init -q -b main
  git -C "$dir" config user.name test
  git -C "$dir" config user.email test@example.invalid
  git -C "$dir" config commit.gpgsign false
  git -C "$dir" commit -q --allow-empty -m "chore(release): the release"
  git -C "$dir" tag "$tag"
  printf '%s\n' "$dir"
}

# An empty commit with [subject] and an optional [body].
commit() {
  local dir="$1" subject="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    git -C "$dir" commit -q --allow-empty -m "$subject" -m "$body"
  else
    git -C "$dir" commit -q --allow-empty -m "$subject"
  fi
}

# Runs `deserved` against [dir] with any further arguments, printing the version and swallowing
# the reasoning.
deserved() {
  local dir="$1"
  shift
  VERSION_GIT_ROOT="$dir" "$VERSION_SH" deserved "$@" 2>/dev/null
}

assert_deserved() {
  local description="$1" expected="$2" dir="$3"
  shift 3
  local actual
  if ! actual="$(deserved "$dir" "$@")"; then
    fail "$description" "deserved failed instead of printing $expected"
    return
  fi
  if [[ "$actual" == "$expected" ]]; then
    pass "$description"
  else
    fail "$description" "expected: $expected
actual:   $actual"
  fi
}

assert_refuses() {
  local description="$1" dir="$2" pattern="$3" output
  if output="$(VERSION_GIT_ROOT="$dir" "$VERSION_SH" deserved 2>&1)"; then
    fail "$description" "deserved printed '$output' instead of refusing"
  elif grep -q "$pattern" <<<"$output"; then
    pass "$description"
  else
    fail "$description" "refused, but not for the expected reason:
$output"
  fi
}

# --- Before 1.0.0 ----------------------------------------------------------------------------------

repo="$(fresh_repo v0.3.12)"
commit "$repo" "fix(importer): read the date"
commit "$repo" "docs: say so"
commit "$repo" "chore(deps): bump something"
assert_deserved "fixes, docs and chores are a patch" 0.3.13 "$repo"

commit "$repo" "feat(importer): add a venue"
assert_deserved "one feat among them is a minor" 0.4.0 "$repo"

repo="$(fresh_repo v0.3.12)"
commit "$repo" "refactor(api)!: drop the v1 route"
assert_deserved "a ! before 1.0.0 is a minor, not a major" 0.4.0 "$repo"

repo="$(fresh_repo v0.3.12)"
commit "$repo" "fix(chart): rename the values key" "BREAKING CHANGE: database.host moved under database.primary"
assert_deserved "a BREAKING CHANGE footer counts the same as the !" 0.4.0 "$repo"

repo="$(fresh_repo v0.3.12)"
commit "$repo" "Apply suggested fix from a bot"
assert_deserved "a subject that is not Conventional Commits is a patch" 0.3.13 "$repo"

repo="$(fresh_repo v0.3.12)"
commit "$repo" "revert: feat(importer): add a venue"
assert_deserved "a revert is a patch" 0.3.13 "$repo"

repo="$(fresh_repo v0.3.12)"
commit "$repo" "FEAT(frontend): shout the type"
assert_deserved "the type is matched case-insensitively, like label-pr.yml" 0.4.0 "$repo"

# --- From 1.0.0 on ---------------------------------------------------------------------------------

repo="$(fresh_repo v1.2.3)"
commit "$repo" "feat(api): add a field"
assert_deserved "a feat after 1.0.0 is a minor" 1.3.0 "$repo"

commit "$repo" "feat(api)!: remove the field"
assert_deserved "a breaking change after 1.0.0 is a major" 2.0.0 "$repo"

# --- The floor -------------------------------------------------------------------------------------

repo="$(fresh_repo v0.3.12)"
commit "$repo" "fix: one fix"
assert_deserved "--at-least minor raises a patch to a minor" 0.4.0 "$repo" --at-least minor
assert_deserved "--at-least major before 1.0.0 is how 1.0.0 is cut" 1.0.0 "$repo" --at-least major

repo="$(fresh_repo v1.2.3)"
commit "$repo" "feat!: break it"
assert_deserved "the floor never lowers a major to a minor" 2.0.0 "$repo" --at-least minor

# --- What counts as the last release ---------------------------------------------------------------

repo="$(fresh_repo v0.3.12)"
commit "$repo" "fix: on main"
git -C "$repo" branch -q side
git -C "$repo" checkout -q side
commit "$repo" "fix: on a branch"
git -C "$repo" tag v0.9.0
git -C "$repo" checkout -q main
assert_deserved "a tag on a branch that never landed is not the baseline" 0.3.13 "$repo"

repo="$(fresh_repo v0.3.12)"
commit "$repo" "fix: first"
git -C "$repo" tag v0.3.13
commit "$repo" "feat: second"
assert_deserved "the newest reachable tag is the baseline, not the first" 0.4.0 "$repo"

repo="$(fresh_repo v0.3.12)"
git -C "$repo" tag v0.3.13-rc1
commit "$repo" "fix: after the prerelease tag"
assert_deserved "a prerelease-shaped tag is not a release" 0.3.13 "$repo"

repo="$(fresh_repo v0.3.12)"
assert_refuses "no commits since the last release is refused" "$repo" "nothing to release"

repo="$(mktemp -d "$WORK/repo.XXXXXX")"
git -C "$repo" init -q -b main
git -C "$repo" -c user.name=t -c user.email=t@example.invalid -c commit.gpgsign=false commit -q --allow-empty -m "fix: untagged"
assert_refuses "a history with no release tag is refused" "$repo" "no release tag"

# ---------------------------------------------------------------------------------------------------

if ((failures != 0)); then
  printf '\n%s\n' "$failures assertion(s) failed." >&2
  exit 1
fi

printf '\nAll assertions passed.\n'
