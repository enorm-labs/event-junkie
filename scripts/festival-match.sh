#!/usr/bin/env bash
#
# festival-match.sh — propose which of our events belong to a festival, from its published programme.
#
# Usage:
#   scripts/festival-match.sh <festival-slug> <programme-url> [--env local|staging|production] [--html <file>] [--out <tsv>]
#   scripts/festival-match.sh --help
#
# Reads the festival, the venues and the events of each programme date from the admin API, through
# the forward `scripts/ej.sh up <env>` opens (production by default). Fetches the programme page, or
# reads the saved copy --html names. Writes build/festival-match/<slug>.tsv and nothing else: the
# operator sets the festival on each event in the admin editor. scripts/festival_match.py has the rule.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

exec python3 "$(dirname "${BASH_SOURCE[0]}")/festival_match.py" "$@"
