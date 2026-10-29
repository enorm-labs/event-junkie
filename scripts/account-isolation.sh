#!/usr/bin/env bash
#
# account-isolation.sh — one account's session must not read or change another account's rows (#4105).
#
# Usage:
#   scripts/account-isolation.sh <origin> [host-header]
#   scripts/account-isolation.sh http://localhost:8080 event-junkie.localhost   # k3d, through Traefik
#   scripts/account-isolation.sh http://localhost:8080                          # dev-env.sh up bff
#
# Requires: curl, jq, and a BFF whose database holds fixtures/accounts.sql. Writes nothing of its own;
# the requests it sends as Ada change none of Ada's rows, and must change none of Bob's.
#
# Ada's session aims every write the API addresses by id, slug or endpoint at Bob's rows: rename and
# delete his saved search and his passkey, mark his notification read, unsubscribe his push endpoint,
# drop his follow and its bell, withdraw his RSVPs. The status codes are printed for the reader. The
# verdict is Bob's export before and after, because a 404 and a 204 that touched nothing are both
# correct, and only the rows say which happened. Ada's export must name none of Bob's ids either.
#
# **Both sessions are checked first.** An expired or missing fixture turns every request into a 401,
# and a 401 changes nothing, so the check would pass while testing nothing.

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

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORIGIN="${1%/}"
HOST_HEADER="${2:-}"
FIXTURE="$REPO_ROOT/fixtures/accounts.json"
CSRF=account-isolation

field() { jq -r --arg email "$1" --arg key "$2" '.accounts[] | select(.email == $email) | .[$key]' "$FIXTURE"; }
ADA_SESSION="$(field ada@example.de session)"
BOB_SESSION="$(field bob@example.de session)"
BOB_SEARCH="$(field bob@example.de savedSearchId)"
BOB_PASSKEY="$(field bob@example.de passkeyId)"
BOB_PUSH="$(field bob@example.de pushEndpoint)"
BOB_NOTIFICATION="$(field bob@example.de notificationId)"

# One request as the account the token names; prints the body, or the status with --status.
call() {
    local out=body
    [[ "$1" == --status ]] && out=status && shift
    local session="$1" method="$2" path="$3" data="${4:-}"
    local args=(-sS -X "$method" -b "__Host-ej_session=$session; XSRF-TOKEN=$CSRF" -H "X-XSRF-TOKEN: $CSRF")
    [[ -n "$HOST_HEADER" ]] && args+=(-H "Host: $HOST_HEADER")
    [[ -n "$data" ]] && args+=(-H 'Content-Type: application/json' --data "$data")
    if [[ "$out" == status ]]; then
        curl "${args[@]}" -o /dev/null -w '%{http_code}' "$ORIGIN$path"
    else
        curl "${args[@]}" -f "$ORIGIN$path"
    fi
}

fail() {
    echo "account-isolation: FAIL — $*" >&2
    exit 1
}

for pair in "$ADA_SESSION ada@example.de" "$BOB_SESSION bob@example.de"; do
    read -r token email <<<"$pair"
    signed_in="$(call "$token" GET /api/auth/session | jq -r '.account.email')"
    [[ "$signed_in" == "$email" ]] || fail "the fixture session for $email signs in as '$signed_in'; load fixtures/accounts.sql"
done

# Bob's rows as his own export lists them, in an order that does not depend on the database's.
bob_rows() {
    call "$BOB_SESSION" GET /api/me/export | jq -S '{
        follows: [.follows[] | {kind, slug, notify}] | sort_by(.kind, .slug),
        searches: [.searches[] | {id, name, query}] | sort_by(.id),
        rsvps: [.rsvps[] | {event, status}] | sort_by(.event),
        passkeys: [.passkeys[] | {id, name}] | sort_by(.id),
        notifications: [.notifications[] | {id, readAt}] | sort_by(.id),
        push: (.pushSubscriptions | length)
    }'
}

before="$(bob_rows)"
for kind in follows searches rsvps passkeys notifications; do
    [[ "$(jq ".$kind | length" <<<"$before")" -gt 0 ]] || fail "Bob has no $kind, so there is nothing to aim at"
done
[[ "$(jq .push <<<"$before")" -gt 0 ]] || fail "Bob has no push subscription, so there is nothing to aim at"

attempt() {
    local status
    status="$(call --status "$ADA_SESSION" "$@")"
    printf '  %s  %-6s %s\n' "$status" "$1" "$2"
}

echo "Ada's session against Bob's rows:"
attempt PATCH "/api/me/searches/$BOB_SEARCH" '{"name":"taken"}'
attempt DELETE "/api/me/searches/$BOB_SEARCH"
attempt PATCH "/api/me/passkeys/$BOB_PASSKEY" '{"name":"taken"}'
attempt DELETE "/api/me/passkeys/$BOB_PASSKEY"
attempt POST "/api/me/notifications/$BOB_NOTIFICATION/read"
attempt POST /api/me/push/unsubscribe "$(jq -cn --arg endpoint "$BOB_PUSH" '{endpoint: $endpoint}')"
for follow in $(jq -r '.follows[] | "\(.kind)/\(.slug)"' <<<"$before"); do
    attempt PUT "/api/me/follows/$follow/notify" '{"notify":"instant"}'
    attempt DELETE "/api/me/follows/$follow"
done
for slug in $(jq -r '.rsvps[].event' <<<"$before"); do
    attempt DELETE "/api/me/rsvps/$slug"
done

after="$(bob_rows)"
if [[ "$before" != "$after" ]]; then
    diff <(echo "$before") <(echo "$after") >&2 || true
    fail "Ada's session changed Bob's rows"
fi

ada_export="$(call "$ADA_SESSION" GET /api/me/export)"
for id in "$BOB_SEARCH" "$BOB_PASSKEY" "$BOB_NOTIFICATION" bob@example.de; do
    [[ "$ada_export" != *"$id"* ]] || fail "Ada's export names Bob's $id"
done

echo "account-isolation: Bob's rows are unchanged, and Ada's export names none of them"
