#!/usr/bin/env bash
#
# migration-collisions-test.sh — assert what `migration-collisions.sh decide` rules, not merely that it runs.
#
# A rule that passes every pull request looks exactly like one that works, until a sibling merges the
# same number. Fixtures are name lists in a temp directory; `decide` reads names only.
#
# Usage: scripts/migration-collisions-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
  -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/migration-collisions.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0

# expect <case-name> <want-output> <main> <pr> <taken> — each list is space-separated names, written one per line.
expect() {
  local label="$1" want="$2" got list
  for list in main pr taken; do
    tr ' ' '\n' <<<"$3" | sed '/^$/d' >"$WORK/$list"
    shift
  done
  got="$("$CHECK" decide "$WORK/main" "$WORK/pr" "$WORK/taken")"
  if [[ "$got" == "$want" ]]; then
    printf '  ok    %s\n' "$label"
  else
    printf '  FAIL  %s\n          got:  %s\n          want: %s\n' "$label" "$got" "$want" >&2
    failures=$((failures + 1))
  fi
}

TAB=$'\t'
expect "a PR without a migration passes" "success${TAB}Adds no migration" \
  "V086__a.sql V087__b.sql" "" ""
expect "a free version passes" "success${TAB}No added version is taken on main" \
  "V086__a.sql V087__b.sql" "V088__c.sql" "V088__c.sql"
expect "a taken version names main's file and the next free number" \
  "failure${TAB}V087 is taken on main by V087__b.sql; renumber to V090 or above" \
  "V086__a.sql V087__b.sql" "V087__c.sql" "V087__c.sql V089__d.sql"
expect "087 and 87 are one version" "failure${TAB}V087 is taken on main by V087__b.sql; renumber to V088 or above" \
  "V087__b.sql" "V87__c.sql" "V87__c.sql"
expect "the PR's own file on main is not a collision" "success${TAB}No added version is taken on main" \
  "V086__a.sql V087__b.sql" "V087__b.sql" "V087__b.sql"
# #2436: merged beside another V087, so main holds both and the other one is named.
expect "a merged PR still names the other file" "failure${TAB}V087 is taken on main by V087__b.sql; renumber to V088 or above" \
  "V087__b.sql V087__c.sql" "V087__c.sql" "V087__c.sql"
expect "two collisions are counted" "failure${TAB}V086 is taken on main by V086__a.sql (+1 more); renumber to V088 or above" \
  "V086__a.sql V087__b.sql" "V086__x.sql V087__y.sql" "V086__x.sql V087__y.sql"
expect "a name Flyway skips is not a version" "success${TAB}Adds no migration" \
  "V087__b.sql" "V087_c.sql" "V087_c.sql"
# The status description is capped at 140 characters by the API.
long="V087__$(printf 'x%.0s' {1..200}).sql"
got="$("$CHECK" decide <(echo "$long") <(echo V087__c.sql) <(echo V087__c.sql))"
if [[ ${#got} -le 140 && "$got" == failure* ]]; then
  printf '  ok    %s\n' "a long file name is cut to 140 characters"
else
  printf '  FAIL  a long file name gives %s characters\n' "${#got}" >&2
  failures=$((failures + 1))
fi

if [[ "$failures" -gt 0 ]]; then
  echo "migration-collisions-test: $failures failure(s)" >&2
  exit 1
fi
echo "migration-collisions-test: all cases pass"
