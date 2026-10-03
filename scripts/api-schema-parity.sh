#!/usr/bin/env bash
#
# api-schema-parity.sh — the frontend's committed API types, against the BFF's OpenAPI document.
#
# Usage:
#   ./gradlew :events-bff:test --tests '*OpenApiDocumentTest'   # writes the document; run it first
#   scripts/api-schema-parity.sh check   # exits 1 if schema.d.ts is stale; leaves it untouched
#   scripts/api-schema-parity.sh         # regenerates schema.d.ts in place, for committing
#
# Reaches the npm registry for the pinned openapi-typescript. Writes only schema.d.ts, and only
# without `check`. Needs Node, and no running BFF.
#
# `events-frontend/src/api/schema.d.ts` is generated and committed (#370). A BFF change that skips
# the regeneration type-checks against an API that no longer exists and fails in the visitor's
# browser. `OpenApiDocumentTest` boots the BFF in the test suite and writes its document, so this
# reads a file, not a server. The generator version is the one `npm run generate:api` pins in
# package.json, read from there, so the two can never disagree. The output is the generator's raw
# text, never formatted (events-frontend/AGENTS.md § API Communication), so a plain diff is exact.

set -euo pipefail

case "${1:-}" in
    -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SCHEMA="events-frontend/src/api/schema.d.ts"
DOCUMENT="events-bff/build/openapi/api-docs.json"
REGENERATE="./gradlew :events-bff:test --tests '*OpenApiDocumentTest' && scripts/api-schema-parity.sh"
MODE="${1:-fix}"

case "$MODE" in
    check | fix) ;;
    *)
        printf 'api-schema-parity.sh: unknown mode %s — expected "check" or nothing\n' "$MODE" >&2
        exit 2
        ;;
esac

[[ -f "$DOCUMENT" ]] || {
    printf 'api-schema-parity.sh: no OpenAPI document at %s.\n' "$DOCUMENT" >&2
    printf "Write it first: ./gradlew :events-bff:test --tests '*OpenApiDocumentTest'\n" >&2
    exit 1
}

VERSION="$(node -p "(require('./events-frontend/package.json').scripts['generate:api'].match(/openapi-typescript@(\d+\.\d+\.\d+)/) || [])[1] || ''")"
[[ -n "$VERSION" ]] || {
    printf 'api-schema-parity.sh: no exact openapi-typescript@X.Y.Z in the generate:api script of events-frontend/package.json\n' >&2
    exit 1
}

if [[ "$MODE" == "fix" ]]; then
    npx -y "openapi-typescript@$VERSION" "$DOCUMENT" -o "$SCHEMA"
    exit 0
fi

GENERATED="$(mktemp)"
trap 'rm -f "$GENERATED"' EXIT

# A generator failure is not a staleness result; unguarded, it would read as one.
if ! npx -y "openapi-typescript@$VERSION" "$DOCUMENT" -o "$GENERATED" >/dev/null; then
    printf 'api-schema-parity.sh: openapi-typescript@%s failed. This is not a staleness result.\n' "$VERSION" >&2
    exit 1
fi

if diff -u --label "$SCHEMA (committed)" --label "$SCHEMA (from the BFF)" "$SCHEMA" "$GENERATED"; then
    printf '%s matches the BFF OpenAPI document (openapi-typescript@%s).\n' "$SCHEMA" "$VERSION"
    exit 0
fi

MESSAGE="$SCHEMA does not match the BFF's API. Regenerate and commit it: $REGENERATE"
if [[ "${GITHUB_ACTIONS:-}" == "true" ]]; then
    printf '::error file=%s::%s\n' "$SCHEMA" "$MESSAGE"
fi
printf '\n%s\n' "$MESSAGE" >&2
exit 1
