#!/usr/bin/env bash
#
# dashboard-parity.sh — the operations page's generated files, against the Markdown and HTML they come from.
#
# Usage:
#   scripts/dashboard-parity.sh check   # exits 1 if any generated file is stale; leaves them untouched
#   scripts/dashboard-parity.sh         # regenerates all three in place, for committing
#
# Reaches no network. Needs python3, and Node with events-frontend/node_modules present (`npm ci` there).
#
# Three files are generated and committed, so the page works from a clone with nothing built:
#
#   docs/ops/dashboard/links.js          from docs/LINKS.md and docs/ops/DAILY_COMMANDS.md   (scripts/links_export.py)
#   docs/event-junkie-bookmarks.html     from docs/LINKS.md                                  (the same script)
#   docs/ops/dashboard/dashboard.css     from index.html and dashboard.js                    (events-frontend/scripts/ops-page-css.mjs)
#
# LINKS.md is the only place a link is typed; a hand-maintained bookmarks file is a second copy, and
# a copy drifts silently. Both generators write no timestamp, so unchanged inputs produce
# byte-identical output and `check` is a plain diff — the same property notices-parity.sh relies on.
#
# `check` restores the committed files before exiting, whatever happened, so a failing run leaves the
# tree exactly as it found it.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

MODE="${1:-fix}"
case "$MODE" in
    check | fix) ;;
    *)
        printf 'dashboard-parity.sh: unknown mode %s — expected "check" or nothing\n' "$MODE" >&2
        exit 2
        ;;
esac

LINKS_JS=docs/ops/dashboard/links.js
BOOKMARKS=docs/event-junkie-bookmarks.html
CSS=docs/ops/dashboard/dashboard.css
GENERATED=("$LINKS_JS" "$BOOKMARKS" "$CSS")

command -v python3 >/dev/null || {
    echo "dashboard-parity.sh: python3 is required" >&2
    exit 1
}
command -v node >/dev/null || {
    echo "dashboard-parity.sh: node is required" >&2
    exit 1
}
[ -d events-frontend/node_modules/@tailwindcss/node ] || {
    echo "dashboard-parity.sh: no events-frontend/node_modules/@tailwindcss/node — run 'npm ci' in events-frontend/ first" >&2
    exit 1
}

generate() {
    python3 scripts/links_export.py --js "$LINKS_JS" --bookmarks "$BOOKMARKS"
    (cd events-frontend && node scripts/ops-page-css.mjs)
}

if [ "$MODE" = fix ]; then
    generate
    exit 0
fi

# check: generate, compare, restore. The comparison is against the index if the file is staged and the
# working tree otherwise, which is what `git diff` does by default.
stash="$(mktemp -d)"
trap 'rm -rf "$stash"' EXIT
for f in "${GENERATED[@]}"; do
    mkdir -p "$stash/$(dirname "$f")"
    [ -f "$f" ] && cp "$f" "$stash/$f"
done

generate >/dev/null

stale=0
for f in "${GENERATED[@]}"; do
    if [ ! -f "$stash/$f" ]; then
        echo "  $f is not committed at all" >&2
        stale=1
    elif ! cmp -s "$f" "$stash/$f"; then
        echo "  $f is stale" >&2
        stale=1
    fi
done

for f in "${GENERATED[@]}"; do
    if [ -f "$stash/$f" ]; then cp "$stash/$f" "$f"; else rm -f "$f"; fi
done

if [ "$stale" -ne 0 ]; then
    echo >&2
    echo "The operations page's generated files do not match their sources. Regenerate and commit:" >&2
    echo "  scripts/dashboard-parity.sh" >&2
    exit 1
fi
echo "Operations page agrees: links.js, the bookmarks file and dashboard.css are what their sources produce."
