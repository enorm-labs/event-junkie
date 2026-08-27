#!/usr/bin/env bash
#
# comment-density-test.sh — assert what `comment-density.sh` counts, not merely that it runs.
#
# A miscount is invisible: the number looks plausible and the baseline absorbs it. A comment block
# that never closes swallows the rest of the file in silence. Fixtures live in a temp directory,
# because a real file is a moving target.
#
# Usage: scripts/comment-density-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DENSITY="$REPO_ROOT/scripts/comment-density.sh"

failures=0

fail() {
  printf '  FAIL  %s\n' "$1" >&2
  printf '%s\n' "$2" | sed 's/^/          /' >&2
  failures=$((failures + 1))
}

pass() {
  printf '  ok    %s\n' "$1"
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# counts <filename> <content> — the "<comment> <code>" the script measures for one fixture.
#
# The fixture is a git repository of its own, holding its own copy of the script, because
# `comment-density.sh` resolves its root from its own path and enumerates with `git ls-files`. A
# file outside either is one it never opens — a test that would pass whatever the awk did.
counts() {
  local name="$1" body="$2" dir="$WORK/case"

  rm -rf "$dir"
  mkdir -p "$dir/scripts"
  git -C "$dir" init -q
  git -C "$dir" config user.email t@example.invalid
  git -C "$dir" config user.name t

  printf '%s' "$body" >"$dir/$name"
  cp "$DENSITY" "$dir/scripts/comment-density.sh"
  git -C "$dir" add -A

  (cd "$dir" && ./scripts/comment-density.sh report --json) |
    awk -F'[:,}]' '/"root"/ { gsub(/[^0-9]/, "", $3); gsub(/[^0-9]/, "", $5); print $3, $5 }'
}

# assert_counts <description> <expected "comment code"> <filename> <content>
assert_counts() {
  local description="$1" expected="$2" actual
  actual="$(counts "$3" "$4")"

  if [[ "$expected" == "$actual" ]]; then
    pass "$description"
  else
    fail "$description" "expected: $expected
  actual:   $actual"
  fi
}

assert_counts 'an HTML comment block ends at --> and the template below it is code' '2 4' fixture.vue \
  '<template>
  <!-- why this exists,
       across two lines -->
  <p>one</p>
  <p>two</p>
</template>
'

assert_counts 'a single-line HTML comment opens no block' '1 4' fixture.vue \
  '<template>
  <!-- why this exists -->
  <p>one</p>
  <p>two</p>
</template>
'

assert_counts 'a block comment still ends at its own delimiter' '3 2' fixture.ts \
  '/**
 * Why this exists.
 */
const a = 1
export { a }
'

assert_counts 'an unterminated block comment runs to the end, because it has to' '3 1' fixture.ts \
  'const a = 1
/**
 * Why this exists.
const b = 2
'

if ((failures > 0)); then
  printf '\n%d assertion(s) failed.\n' "$failures" >&2
  exit 1
fi

printf '\nAll assertions passed.\n'
