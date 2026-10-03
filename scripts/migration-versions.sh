#!/usr/bin/env bash
#
# migration-versions.sh — no two Flyway migrations share a version, and every file name parses.
#
# Usage:
#   scripts/migration-versions.sh [migration-dir]   # default: events-importer/src/main/resources/db/migration
#
# Requires: bash and coreutils. Reaches no network, writes nothing.
#
# Two branches can each add the next number under different file names. Git conflicts only on one
# path, so rebase-and-merge lands both, and Flyway then refuses to start: `Found more than one
# migration with version 072` (#2183). `build-backend.yml` runs this on a pull request's merge ref,
# frozen at its last push. `migration-versions.yml` re-checks every open PR whenever a migration lands
# on `main` (`migration-collisions.sh`). Two colliding PRs merged within one of its runs still pass both;
# `release.yml` runs this before it builds, so that tree is never published.
#
# A name that does not match `V<digits>__<description>.sql` fails too: Flyway skips it without a word,
# so `V072_fold.sql` with one underscore would never run.

set -euo pipefail

case "${1:-}" in
  -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIR="${1:-$REPO_ROOT/events-importer/src/main/resources/db/migration}"
[[ -d "$DIR" ]] || { echo "migration-versions: no directory $DIR" >&2; exit 2; }

failed=0
versions=()
for path in "$DIR"/*; do
  [[ -e "$path" ]] || continue
  name="$(basename "$path")"
  if [[ ! "$name" =~ ^V([0-9]+)__[^_].*\.sql$ ]]; then
    echo "migration-versions: $name does not match V<digits>__<description>.sql, and Flyway would skip it" >&2
    failed=1
    continue
  fi
  # 072 and 72 are one version to Flyway, so compare the number, not the text.
  versions+=("$((10#${BASH_REMATCH[1]})) $name")
done

duplicates="$(printf '%s\n' "${versions[@]}" | awk '{ count[$1]++; files[$1] = files[$1] " " $2 } END { for (v in count) if (count[v] > 1) print v ":" files[v] }' | sort -n)"
if [[ -n "$duplicates" ]]; then
  while IFS= read -r line; do
    echo "migration-versions: version ${line%%:*} is taken by more than one file:${line#*:}" >&2
  done <<<"$duplicates"
  echo "migration-versions: renumber the one that has not reached a cluster; see #2183" >&2
  failed=1
fi

if [[ "$failed" -eq 0 ]]; then
  echo "migration-versions: ${#versions[@]} migrations, every version unique"
fi
exit "$failed"
