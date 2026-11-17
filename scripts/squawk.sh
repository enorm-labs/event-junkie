#!/usr/bin/env bash
#
# squawk.sh — squawk at the version CI pins, so the commit hook and CI judge a migration alike.
#
# Usage:
#   scripts/squawk.sh <migration.sql>...   # lint these files; the pre-commit hook passes the staged migrations
#
# Reaches PyPI through `uvx` the first time a version is used, then runs from uv's cache. The
# version is SQUAWK_VERSION in .github/workflows/validate-migrations.yml, which Renovate bumps.
# A squawk installed once with `uv tool install` stays on its first version, so a local 2.67.0
# failed a migration whose `squawk-ignore ban-drop-constraint` line CI's 2.71.0 needs (#5250).
# Reading the pin here makes that drift impossible. Run it from the repository root, as the hook
# does: squawk reads `.squawk.toml` from the current directory.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/validate-migrations.yml"

die() {
    printf 'squawk.sh: %s\n' "$1" >&2
    exit 1
}

command -v uvx >/dev/null || die 'no uvx — install uv (brew install uv)'
version="$(sed -n 's/^  SQUAWK_VERSION: *//p' "$WORKFLOW")"
[ -n "$version" ] || die "no SQUAWK_VERSION in $WORKFLOW"
[ "$#" -gt 0 ] || die 'no file named; see --help'

exec uvx --from "squawk-cli==$version" squawk "$@"
