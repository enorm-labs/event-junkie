#!/usr/bin/env bash
#
# dev-seed-parity.sh — http/importer/dev-seed.http, against the venue files it is generated from.
#
# Usage:
#   scripts/dev-seed-parity.sh check   # exits 1 if dev-seed.http is stale; leaves it untouched
#   scripts/dev-seed-parity.sh         # regenerates dev-seed.http in place, for committing
#
# Reaches no network. Writes only dev-seed.http, and only without `check`. Needs Python 3.
#
# Every venue and its event sources live in http/importer/seed/venues/<slug>.json, one file each, so
# a venue pull request adds one file and conflicts with no other (#2824). dev-seed.http is the
# generated "Run all" file that IntelliJ and `scripts/dev-env.sh seed-all` run. scripts/seed_venues.py
# does the work and documents the file format.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
    check | "") ;;
    *)
        echo "dev-seed-parity: unknown argument '$1'; see --help" >&2
        exit 2
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec python3 "$REPO_ROOT/scripts/seed_venues.py" "${1:-fix}"
