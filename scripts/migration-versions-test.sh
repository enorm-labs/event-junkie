#!/usr/bin/env bash
#
# migration-versions-test.sh — assert what `migration-versions.sh` refuses, not merely that it runs.
#
# A check that passes everything looks exactly like one that works, until two branches take the same
# number. Fixtures are empty files in a temp directory; the script reads names only.
#
# Usage: scripts/migration-versions-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
  -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHECK="$REPO_ROOT/scripts/migration-versions.sh"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

failures=0

# expect <want-exit> <case-name> <file>... — plant the files in a fresh directory and compare the exit code.
expect() {
  local want="$1" label="$2" dir="$WORK/case" got=0 output
  shift 2
  rm -rf "$dir" && mkdir -p "$dir"
  for file in "$@"; do : >"$dir/$file"; done
  output="$("$CHECK" "$dir" 2>&1)" || got=$?
  if [[ "$got" -eq "$want" ]]; then
    printf '  ok    %s\n' "$label"
  else
    printf '  FAIL  %s (exit %s, want %s)\n' "$label" "$got" "$want" >&2
    printf '%s\n' "$output" | sed 's/^/          /' >&2
    failures=$((failures + 1))
  fi
}

expect 0 "distinct versions pass" V001__init.sql V002__add.sql V072__fold.sql
expect 1 "two files with one version fail" V071__a.sql V072__fold_typed_apostrophes.sql V072__merge_ctm_festival_promoters.sql
expect 1 "072 and 72 are one version" V072__a.sql V72__b.sql
expect 1 "a single underscore fails, because Flyway skips it" V072_fold.sql
expect 1 "a file without the V prefix fails" 072__fold.sql
expect 0 "an empty directory passes"
# A missing directory is a usage error, not a pass: a moved module must not turn the check off.
got=0
"$CHECK" "$WORK/none" >/dev/null 2>&1 || got=$?
if [[ "$got" -eq 2 ]]; then
  printf '  ok    %s\n' "a missing directory exits 2"
else
  printf '  FAIL  a missing directory exits %s, want 2\n' "$got" >&2
  failures=$((failures + 1))
fi

if [[ "$failures" -gt 0 ]]; then
  echo "migration-versions-test: $failures failure(s)" >&2
  exit 1
fi
echo "migration-versions-test: all cases pass"
