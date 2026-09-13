#!/usr/bin/env bash
#
# release-highlights.sh — the summary that goes on top of a release's notes.
#
# Usage:
#   scripts/release-highlights.sh                 # since the last release tag
#   scripts/release-highlights.sh v0.13.0         # since a named tag
#   scripts/release-highlights.sh --max 3         # fewer bullets (default 5)
#
# GitHub's generated notes are one bucket per label and list everything, in no order of importance
# — a reader looking for what changed for *them* reads twenty rows to find two. This prints a
# heading, one counted sentence and the few changes worth naming, which `cut-release.yml` puts
# above those buckets.
#
# What it names is the part of a release a visitor to the site would notice: breaking changes
# first, then features, new event sources, fixes and performance work. Everything else — chores,
# CI, docs, tests, refactors, dependency bumps and this project's own version bumps — is left to
# the buckets below. Prints nothing at all when no commit qualifies, so a maintenance release
# keeps the plain notes rather than gaining an empty heading.
#
# Requires: git. Reaches no network and writes nothing. VERSION_GIT_ROOT points the history
# commands at another repository, which is how `scripts/release-highlights-test.sh` fabricates one.

set -euo pipefail

case "${1:-}" in
  -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GIT_ROOT="${VERSION_GIT_ROOT:-$REPO_ROOT}"
MAX=5
SINCE=""

while [ $# -gt 0 ]; do
  case "$1" in
    --max) MAX="${2:?--max needs a number}"; shift 2 ;;
    -*) printf 'release-highlights.sh: unknown option %s\n' "$1" >&2; exit 1 ;;
    *) SINCE="$1"; shift ;;
  esac
done

# The record separators keep a multi-line body in one record, so `BREAKING CHANGE:` in a footer is
# still readable per commit.
RS=$'\x1e'
FS=$'\x1f'

latest_tag() {
  git -C "$GIT_ROOT" tag --list 'v*' --sort=-v:refname | head -1
}

[ -n "$SINCE" ] || SINCE="$(latest_tag)"
range="HEAD"
[ -z "$SINCE" ] || range="$SINCE..HEAD"

# Rank decides both the order and what is left out: 0 breaking, 1 feature, 2 new event source,
# 3 fix, 4 performance. Anything else answers nothing and never reaches the summary.
rank_of() {
  local subject="$1" body="$2" type scope
  case "$subject" in
    "chore(release)"*) return 1 ;;
    *"(deps)"*) return 1 ;;
    *"(deps-dev)"*) return 1 ;;
  esac
  type="${subject%%[(:!]*}"
  scope=""
  case "$subject" in
    *"("*")"*) scope="${subject#*(}"; scope="${scope%%)*}" ;;
  esac
  case "$subject" in
    *"!:"*) printf '0\n'; return 0 ;;
  esac
  case "$body" in
    *BREAKING\ CHANGE*) printf '0\n'; return 0 ;;
  esac
  case "$type" in
    feat) if [ "$scope" = importer ]; then printf '2\n'; else printf '1\n'; fi ;;
    fix) printf '3\n' ;;
    perf) printf '4\n' ;;
    *) return 1 ;;
  esac
}

# "feat(frontend): add a compact view" → "Add a compact view". The type and the scope are for the
# commit log; a reader of the Releases page is told what changed, not which prefix carried it.
headline() {
  local subject="$1" text
  text="${subject#*: }"
  printf '%s' "$(printf '%s' "${text:0:1}" | tr '[:lower:]' '[:upper:]')${text:1}"
}

ranked=""
breaking=0
features=0
sources=0
fixes=0
performance=0

while IFS= read -r -d "$RS" record; do
  # `git log --format` ends every record with a newline, so each record after the first opens with
  # one. Left in, the type reads as "\nfeat" and nothing ever matches.
  record="${record#$'\n'}"
  [ -n "$record" ] || continue
  subject="${record%%"$FS"*}"
  body="${record#*"$FS"}"
  rank="$(rank_of "$subject" "$body")" || continue
  case "$rank" in
    0) breaking=$((breaking + 1)) ;;
    1) features=$((features + 1)) ;;
    2) sources=$((sources + 1)) ;;
    3) fixes=$((fixes + 1)) ;;
    4) performance=$((performance + 1)) ;;
  esac
  ranked="$ranked$rank$FS$(headline "$subject")$RS"
done < <(git -C "$GIT_ROOT" log --no-merges --reverse --format="%s${FS}%b${RS}" "$range")

[ -n "$ranked" ] || exit 0

# One sentence of counts, in the same order as the bullets.
counted=""
plural() { [ "$1" = 1 ] && printf '%s %s' "$1" "$2" || printf '%s %s' "$1" "$3"; }
append() { [ "$1" -eq 0 ] || counted="${counted:+$counted · }$(plural "$1" "$2" "$3")"; }
append "$breaking" "breaking change" "breaking changes"
append "$features" "feature" "features"
append "$sources" "new event source" "new event sources"
append "$fixes" "fix" "fixes"
append "$performance" "performance change" "performance changes"

printf '## Highlights\n\n'
if [ -n "$SINCE" ]; then
  printf '**%s** since %s.\n\n' "$counted" "$SINCE"
else
  printf '**%s**.\n\n' "$counted"
fi

# Sorted by rank, then by the order they landed, and cut to [MAX]. A breaking change says so,
# because it is the one line a reader must not skim past.
printf '%s' "$ranked" |
  tr "$RS" '\n' |
  grep -v '^$' |
  awk -v FS="$FS" '{ printf "%s\t%06d\t%s\n", $1, NR, $2 }' |
  sort -k1,1 -k2,2 |
  head -n "$MAX" |
  awk -F'\t' '{ if ($1 == 0) printf "- **Breaking:** %s\n", $3; else printf "- %s\n", $3 }'
