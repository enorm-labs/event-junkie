#!/usr/bin/env bash
#
# sign-in-limits.sh — the BFF's own sign-in limits engage behind Traefik, keyed on an address a client cannot forge (#4107).
#
# Usage:
#   scripts/sign-in-limits.sh <origin> [host-header]
#   scripts/sign-in-limits.sh http://localhost:8080 event-junkie.localhost   # k3d, through Traefik
#
# Requires: curl, a BFF with no mail configured, and a fresh BFF process: the counts live in its memory for an hour,
# so a restart is what empties them. Writes no rows: without mail, no sign-in token is issued.
#
# `POST /api/auth/link` counts 20 an hour per client before it looks for mail (`SignInLinks.kt`): 503 until the count
# is spent, then 429. `POST /api/auth/passkey/options` allows 30 (`Passkeys.kt`): 200, then 429.
# Every request forges a different `X-Forwarded-For`. The BFF keys on the last entry, the one Traefik appends,
# so a key read from the forged first entry would never reach the limit.
#
# It cannot show that the key is the visitor's address: on k3d every request comes from one host, through one of
# two node addresses.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
    "")
        echo "usage: $0 <origin> [host-header]" >&2
        exit 2
        ;;
esac

ORIGIN="${1%/}"
HOST_HEADER="${2:-}"
CSRF=sign-in-limits
LINKS_PER_CLIENT=20
PASSKEY_OPTIONS_PER_CLIENT=30
# More than k3d's two, fewer than the forged addresses a reset would spread the requests over.
MAX_SOURCES=3

# One POST from the same client, under a forged address; prints the status.
post() {
    local path="$1" body="$2" n="$3"
    local args=(-sS -o /dev/null -w '%{http_code}' -X POST
        -b "XSRF-TOKEN=$CSRF" -H "X-XSRF-TOKEN: $CSRF" -H 'Content-Type: application/json'
        -H "X-Forwarded-For: 203.0.113.$((n % 250 + 1))" --data "$body")
    [[ -n "$HOST_HEADER" ]] && args+=(-H "Host: $HOST_HEADER")
    curl "${args[@]}" "$ORIGIN$path"
}

fail() {
    echo "sign-in-limits: FAIL — $*" >&2
    exit 1
}

# Requests answer `allowed` until the first 429, which must come after a whole multiple of `limit`: one count per
# address Traefik sees. Production's hostPort gives Traefik the visitor's own address; k3d's ServiceLB gives it one
# per node, so k3d counts two. A key read from the forged header would allow every request up to the cap.
check() {
    local label="$1" path="$2" allowed="$3" limit="$4" body="$5"
    local cap=$((limit * MAX_SOURCES + 1)) n status allowed_count=0
    for n in $(seq 1 "$cap"); do
        status="$(post "$path" "${body//N/$n}" "$n")"
        [[ "$status" == 429 ]] && break
        [[ "$status" == "$allowed" ]] ||
            fail "$label request $n answered $status, expected $allowed or 429"
        allowed_count=$n
    done
    [[ "$status" == 429 ]] ||
        fail "$label: no 429 in $cap requests. The limit does not engage, or a forged X-Forwarded-For resets it"
    ((allowed_count > 0)) || fail "$label: the first request answered 429. A spent count needs a BFF restart first"
    ((allowed_count % limit == 0)) ||
        fail "$label: $allowed_count requests passed before the 429, not a multiple of $limit. A spent count needs a BFF restart first"
    echo "$label: $allowed_count x $allowed, then 429 ($((allowed_count / limit)) source addresses seen)"
}

check "POST /api/auth/link" /api/auth/link 503 "$LINKS_PER_CLIENT" '{"email":"limit-N@example.de","locale":"en"}'
check "POST /api/auth/passkey/options" /api/auth/passkey/options 200 "$PASSKEY_OPTIONS_PER_CLIENT" '{}'

echo "sign-in-limits: both limits engage at their count, and a forged X-Forwarded-For does not reset them"
