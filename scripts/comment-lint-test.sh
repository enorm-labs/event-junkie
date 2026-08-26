#!/usr/bin/env bash
#
# comment-lint-test.sh — assert what `comment-lint.sh` counts, not merely that it runs.
#
# The rule it guards is invisible when it breaks: counting a blank ` # ` between paragraphs toward a
# block's length makes the same words cost more as three readable paragraphs than as one wall of
# text, and nothing about a passing run would say so (#741, #750). Fixtures are written to a temp
# directory rather than pointed at real files, because a real file is a moving target — the first
# sweep that compacts it turns a passing assertion into one that passes for the wrong reason.
#
# Usage: scripts/comment-lint-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
LINT="$REPO_ROOT/scripts/comment-lint.sh"

failures=0

fail() {
  printf '  FAIL  %s\n' "$1" >&2
  if [[ -n "${2:-}" ]]; then
    printf '%s\n' "$2" | sed 's/^/          /' >&2
  fi
  failures=$((failures + 1))
}

pass() {
  printf '  ok    %s\n' "$1"
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# findings <file-content> — the rule names the linter reports for one fixture, one per line.
#
# The fixture is a git repository of its own: `comment-lint.sh` enumerates files with `git ls-files`,
# so an untracked fixture is a file it never opens — a test that would pass whatever the awk did.
findings() {
  local body="$1" dir="$WORK/case"

  rm -rf "$dir"
  mkdir -p "$dir/scripts"
  git -C "$dir" init -q
  git -C "$dir" config user.email t@example.invalid
  git -C "$dir" config user.name t

  printf '%s' "$body" >"$dir/fixture.yaml"
  git -C "$dir" add -A
  cp "$LINT" "$dir/scripts/comment-lint.sh"

  (cd "$dir" && ./scripts/comment-lint.sh report 2>/dev/null) |
    awk 'NF >= 3 && $1 ~ /:[0-9]+$/ { print $2 }'
}

# assert_findings <description> <expected, space-separated or "none"> <file-content>
assert_findings() {
  local description="$1" expected="$2" body="$3" actual
  actual="$(findings "$body" | sort -u | tr '\n' ' ')"
  actual="${actual% }"
  [[ -z "$actual" ]] && actual=none

  if [[ "$expected" == "$actual" ]]; then
    pass "$description"
  else
    fail "$description" "expected: $expected
  actual: $actual"
  fi
}

# directive <text> — a `comment-lint:` line, built rather than written literally. A directive at the
# start of a line in this file is one the linter reads as this file's own when it scans `scripts/`.
directive() {
  printf '# comment-lint: %s' "$1"
}

# block <n> — a comment block of n lines, each carrying content.
block() {
  local n="$1" i
  for ((i = 1; i <= n; i++)); do printf '# line %d\n' "$i"; done
}

# paragraphed <n> — the same n lines of content, split into paragraphs by blank ` # ` separators.
paragraphed() {
  local n="$1" i
  for ((i = 1; i <= n; i++)); do
    printf '# line %d\n' "$i"
    ((i % 5 == 0 && i < n)) && printf '#\n'
  done
  return 0
}

# code <n> — n lines of YAML, enough to keep a fixture under the density cap so that `long-block` is
# the only rule with anything to say about it. Without this every fixture here is 100% comment and
# every assertion reads `dense-file`, whatever the block cap did.
code() {
  local n="$1" i
  for ((i = 1; i <= n; i++)); do printf 'key%d: value\n' "$i"; done
}

printf 'comment-lint.sh\n'

# Every fixture puts code before the block under test. A block starting within the first three lines
# is a file header and gets `MAX_HEADER_BLOCK` (40) instead of `MAX_BLOCK` (25) — so a block-cap
# assertion written at the top of a file passes whatever the cap does, which is the trap these
# fixtures were written into first.
assert_findings 'a block in the first three lines is a header and gets the header cap' \
  none "$(block 30)
$(code 30)"

assert_findings 'the same block below the header line is not' \
  long-block "$(code 30)
$(block 30)"

# The assertion this file exists for. Twenty-four lines of content is under the cap of 25; splitting
# them into paragraphs adds four separators and must not push the block over it.
assert_findings 'a blank separator between paragraphs is not length' \
  none "$(code 30)
$(paragraphed 24)"

# Content is what counts, not the character: a line of bare hashes carries nothing either.
assert_findings 'a separator written as bare hashes is not length either' \
  none "$(code 30)
$(block 24)
#
#"

# The suppression path, which shares the accumulator the change touched.
assert_findings 'a reasoned suppression still silences an over-cap block' \
  none "$(code 30)
$(directive 'allow the fixture is deliberately long')
$(block 30)"

assert_findings 'a bare suppression is still itself a violation' \
  bare-suppression "$(code 30)
$(directive allow)"

# Density counts every comment line, blank ones included, in both halves of the ratio — the one place
# all three implementations already agreed, and the thing #750 must not have changed. Six short
# blocks rather than one long one, so `long-block` has nothing to say and only density is under test.
assert_findings 'density still counts a blank comment line' \
  dense-file "$(for i in 1 2 3 4 5 6; do paragraphed 5; printf 'key%d: value\n' "$i"; done)"

if ((failures != 0)); then
  printf '\n%s\n%s\n' \
    "$failures assertion(s) failed." \
    'A block cap that counts paragraph breaks makes a readable comment cost more than an unreadable one (#750).' >&2
  exit 1
fi

printf '\nAll assertions passed.\n'
