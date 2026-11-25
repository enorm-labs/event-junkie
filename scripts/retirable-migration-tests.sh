#!/usr/bin/env bash
#
# retirable-migration-tests.sh — which migration tests cover only migrations a release already carries.
#
# Usage:
#   scripts/retirable-migration-tests.sh <version>      # the version production runs, such as 0.98.1
#
# Needs the release tags (`git fetch --tags origin`).
# One `RETIRE|KEEP|UNREADABLE<TAB>V<target><TAB>path` line per `*MigrationTest.kt`. The target is the
# highest quoted version in the test, so a doubtful file is kept, never retired. The caller passes the
# version that RUNS (`scripts/ej.sh versions`), not the newest release. Reads git only; changes nothing.
# Exit code: 0, or 2 when the version has no tag or the tag holds no migration.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    echo "retirable-migration-tests: $1" >&2
    exit 2
}

[ $# -eq 1 ] || die "needs one version; see --help"
cd "$(dirname "${BASH_SOURCE[0]}")/.."

TAG="v${1#v}"
git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || die "no tag $TAG; run git fetch --tags origin"

MIGRATIONS=events-importer/src/main/resources/db/migration
TESTS=events-importer/src/test/kotlin
# Every new cluster migrates as an owner that cannot create roles, so this one never goes (#4671).
PERMANENT=CreateAccountsSchemaMigrationTest.kt

applied=$(git ls-tree --name-only "$TAG" "$MIGRATIONS/" | sed -nE 's|.*/V0*([0-9]+)__.*|\1|p' | sort -n | tail -1)
[ -n "$applied" ] || die "$TAG holds no migration under $MIGRATIONS"

git ls-files "$TESTS" | { grep 'MigrationTest\.kt$' || true; } | while IFS= read -r file; do
    [ "${file##*/}" != "$PERMANENT" ] || continue
    [ -f "$file" ] || continue
    target=$({ grep -oE '"[0-9]{3,4}"' "$file" || true; } | tr -d '"' | sed -E 's/^0+//' | sort -n | tail -1)
    if [ -z "$target" ]; then
        printf 'UNREADABLE\t-\t%s\n' "$file"
    elif [ "$target" -le "$applied" ]; then
        printf 'RETIRE\tV%03d\t%s\n' "$target" "$file"
    else
        printf 'KEEP\tV%03d\t%s\n' "$target" "$file"
    fi
done
