#!/usr/bin/env bash
#
# features-parity.sh — every surface a visitor can reach has a row in PRODUCT_OVERVIEW.md, and every row exists.
#
# Usage:
#   scripts/features-parity.sh          # exits 1 listing whatever is out of step
#
# Reaches no network and writes nothing.
#
# The feature inventory is prose, written by hand, so nothing stopped it going stale: it called the site
# "not yet publicly deployed" months after launch. This check cannot read prose. It reads four lists the
# code owns and holds them against the code spans in the first column of § Surfaces:
#
#   - the routes in events-frontend/src/router/index.ts, as `/<path>`
#   - the public API paths in events-frontend/src/api/schema.d.ts, the BFF's OpenAPI document
#   - the exact-match public files in events-frontend/docker/nginx.conf, such as `/feed.xml`
#   - the top-level e2e specs, because a feature without a route usually arrives with one (share-calendar)
#
# A new e2e spec that tests quality rather than a feature still takes a row, marked so. The decision is
# the point: the check cannot tell the two apart, and an unlisted spec is where a feature hides.

set -euo pipefail

case "${1:-}" in
    -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

DOC=docs/PRODUCT_OVERVIEW.md
ROUTER=events-frontend/src/router/index.ts
SCHEMA=events-frontend/src/api/schema.d.ts
NGINX=events-frontend/docker/nginx.conf
E2E=events-frontend/e2e

for f in "$DOC" "$ROUTER" "$SCHEMA" "$NGINX"; do
    [ -f "$f" ] || { echo "$f: missing" >&2; exit 1; }
done

# The first code span of each table row under `## Surfaces`, up to the next `## `.
# shellcheck disable=SC2016 # the backticks are Markdown code spans, not a substitution
listed="$(awk '/^## Surfaces/ { s = 1; next } s && /^## / { exit } s && /^\| `/ { print }' "$DOC" |
    sed -n 's/^| `\([^`]*\)`.*/\1/p' | sort -u)"
[ -n "$listed" ] || { echo "$DOC: no '## Surfaces' table with code spans in its first column" >&2; exit 1; }

# A child route's `path: 'events/:slug'` becomes `/events/:slug`, and the locale home `path: ''` becomes `/`.
# The locale shell is a template literal and the catch-all starts with `/`, so the pattern leaves both out.
routes="$(sed -n "s/^ *path: '\([^/'][^']*\)\{0,1\}',.*/\1/p" "$ROUTER" | sed 's|^|/|')"
api="$(sed -n 's/^    "\(\/api\/[^"]*\)": {$/\1/p' "$SCHEMA")"
files="$(sed -n 's/^ *location = \(\/[^ ]*\.[a-z]*\) {$/\1/p' "$NGINX" | grep -v '^/index\.html$' || true)"
specs="$(find "$E2E" -maxdepth 1 -name '*.spec.ts' -exec basename {} \;)"

expected="$(printf '%s\n' "$routes" "$api" "$files" "$specs" | grep . | sort -u)"

missing="$(comm -23 <(printf '%s\n' "$expected") <(printf '%s\n' "$listed"))"
stale="$(comm -13 <(printf '%s\n' "$expected") <(printf '%s\n' "$listed"))"

if [ -n "$missing" ] || [ -n "$stale" ]; then
    if [ -n "$missing" ]; then
        echo "In the code, with no row in $DOC § Surfaces:" >&2
        printf '%s\n' "$missing" | sed 's/^/  /' >&2
    fi
    if [ -n "$stale" ]; then
        echo "A row in $DOC § Surfaces, with nothing in the code behind it:" >&2
        printf '%s\n' "$stale" | sed 's/^/  /' >&2
    fi
    echo >&2
    echo "Add or remove the row, and update § What a visitor can do and the README's What it does with it." >&2
    exit 1
fi

printf 'Product overview agrees with the code: %s surfaces.\n' "$(printf '%s\n' "$expected" | grep -c .)"
