#!/usr/bin/env bash
#
# order-next-pair.sh — order each server of the new pair the moment Hetzner sells its type, until the pair is complete (#4128).
#
# Usage:
#   infra/order-next-pair.sh <staging|production> [--interval <seconds>] [--fallback-after <YYYY-MM-DD> | --no-fallback]
#
#   production   k3s cx33 and database cx23, in nbg1. When cx43 is sold and cx33 is not, a cx43
#                takes the cx33's place: beside a held cx23, or alone with PostgreSQL on it
#   staging      k3s cx33, PostgreSQL on it as today; the same cx43 fallback
#   --interval        seconds between rounds (120): 2 GET requests per type, far under the API limit
#   --fallback-after  the first day a cx43 may be ordered; before it, only cx33 counts
#   --no-fallback     never order a cx43
#   EJ_K3S_TYPE, EJ_POSTGRES_TYPE and EJ_FALLBACK_TYPE override the three types.
#
# Each round reads which *_next servers the state already holds, asks the free advertisement for
# each type still missing, and orders what is sold: `apply.sh <env> --yes-if-only-creates …
# -var next_pair=…` with the held and the new types. That apply needs no person while the plan
# only creates *_next servers and Primary IPs. Anything else stops this with exit 3.
#
# A failed apply, usually `resource_unavailable`, waits and tries again, and a server it did
# create counts as held. A held cx33 waits for its cx23 and is never swapped for a cx43. Every
# order and the end are announced with `say`. Run it in a terminal tab that stays open.
#
# Requires: direnv with infra/.envrc allowed, jq, tofu with the stack initialised.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    echo "order-next-pair: $*" >&2
    exit 1
}

INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CAPACITY="${EJ_CHECK_CAPACITY:-$INFRA/check-capacity.sh}"
APPLY="${EJ_APPLY:-$INFRA/apply.sh}"
NOTIFY="${EJ_NOTIFY:-say}"
# Every address the module gives the second pair (infra/modules/environment/next-pair.tf).
NEXT_RE='^module\.environment\.hcloud_(server|primary_ip)\.(k3s|postgres)_next(_ipv4|_ipv6)?\[0\]$'
LOCATION=nbg1

ENV="${1:-}"
case "$ENV" in
    production) POSTGRES_TYPE="${EJ_POSTGRES_TYPE:-cx23}" ;;
    staging) POSTGRES_TYPE="" ;;
    *) die "expected staging or production, got '${ENV}'" ;;
esac
shift
K3S_TYPE="${EJ_K3S_TYPE:-cx33}"
FALLBACK_TYPE="${EJ_FALLBACK_TYPE:-cx43}"
INTERVAL=120
FALLBACK_AFTER=""
FALLBACK=1
while [ $# -gt 0 ]; do
    case "$1" in
        --interval)
            [ $# -ge 2 ] || die "--interval needs seconds"
            INTERVAL="$2"
            shift 2
            ;;
        --fallback-after)
            [ $# -ge 2 ] && [[ "$2" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || die "--fallback-after needs a date, YYYY-MM-DD"
            FALLBACK_AFTER="$2"
            shift 2
            ;;
        --no-fallback)
            FALLBACK=0
            shift
            ;;
        *) die "unknown argument '$1'" ;;
    esac
done

log() { echo "order-next-pair: $(date +%H:%M) $*"; }
tell() {
    log "$1"
    "$NOTIFY" "$1" >/dev/null 2>&1 || true
}

# The state, not the outputs: a failed apply still records the servers it created.
state_json() {
    if [ -n "${EJ_SHOW_STATE:-}" ]; then
        "$EJ_SHOW_STATE"
    else
        direnv exec "$INFRA" tofu -chdir="$INFRA/environments/$ENV" show -json
    fi
}
held_type() {
    jq -r --arg a "module.environment.hcloud_server.$2_next[0]" \
        '[.values.root_module.child_modules[]?.resources[]? | select(.address == $a) | .values.server_type][0] // ""' <<<"$1"
}

# Exit 2 from check-capacity.sh is an API error: logged, and taken as not sold this round.
sold() {
    local rc=0
    direnv exec "$INFRA" "$CAPACITY" "$1@$LOCATION" >/dev/null 2>&1 || rc=$?
    [ "$rc" -le 1 ] || log "check-capacity.sh $1@$LOCATION failed (exit $rc)"
    return "$rc"
}

fallback_open() { [ "$FALLBACK" = 1 ] && [[ ! "$(date +%F)" < "$FALLBACK_AFTER" ]]; }

fallback_note="always"
[ -z "$FALLBACK_AFTER" ] || fallback_note="from $FALLBACK_AFTER"
[ "$FALLBACK" = 1 ] || fallback_note="never"
echo "order-next-pair: $ENV wants k3s $K3S_TYPE${POSTGRES_TYPE:+ and database $POSTGRES_TYPE}, fallback $FALLBACK_TYPE $fallback_note, every ${INTERVAL}s. Ctrl-C stops it."
rounds=0
while :; do
    rounds=$((rounds + 1))
    if [ -n "${EJ_MAX_ROUNDS:-}" ] && [ "$rounds" -gt "$EJ_MAX_ROUNDS" ]; then
        exit 4
    fi
    if ! state="$(state_json 2>/dev/null)"; then
        log "cannot read the $ENV state, trying again"
        sleep "$INTERVAL"
        continue
    fi
    held_k3s="$(held_type "$state" k3s)"
    held_db="$(held_type "$state" postgres)"

    if [ -n "$held_k3s" ] && { [ -n "$held_db" ] || [ -z "$POSTGRES_TYPE" ] || [ "$held_k3s" = "$FALLBACK_TYPE" ]; }; then
        tell "the $ENV next pair is complete: k3s $held_k3s${held_db:+, database $held_db}"
        exit 0
    fi

    k3s="$held_k3s"
    db="$held_db"
    if [ -z "$db" ] && [ -n "$POSTGRES_TYPE" ] && sold "$POSTGRES_TYPE"; then
        db="$POSTGRES_TYPE"
    fi
    if [ -z "$k3s" ]; then
        if sold "$K3S_TYPE"; then
            k3s="$K3S_TYPE"
        elif fallback_open && sold "$FALLBACK_TYPE"; then
            k3s="$FALLBACK_TYPE"
        fi
    fi
    if [ "$k3s" = "$held_k3s" ] && [ "$db" = "$held_db" ]; then
        log "nothing new for sale (held: k3s ${held_k3s:-none}${POSTGRES_TYPE:+, database ${held_db:-none}})"
        sleep "$INTERVAL"
        continue
    fi

    fields=()
    [ -z "$k3s" ] || fields+=("k3s_server_type=\"$k3s\"")
    [ -z "$db" ] || fields+=("postgres_server_type=\"$db\"")
    # A fallback node with no database node beside it carries PostgreSQL itself.
    if [ "$k3s" = "$FALLBACK_TYPE" ] && [ -z "$db" ] && [ -n "$POSTGRES_TYPE" ]; then
        fields+=("colocated=true")
    fi
    next_pair="{$(
        IFS=,
        echo "${fields[*]}"
    )}"
    log "ordering next_pair=$next_pair"
    rc=0
    "$APPLY" "$ENV" --yes-if-only-creates "$NEXT_RE" -var "next_pair=$next_pair" </dev/null || rc=$?
    case "$rc" in
        0)
            tell "ordered for $ENV: k3s ${k3s:-none}${POSTGRES_TYPE:+, database ${db:-none}}"
            continue
            ;;
        3)
            tell "the $ENV plan needs a person, nothing was ordered"
            exit 3
            ;;
        *) log "the apply failed (exit $rc), trying again" ;;
    esac
    sleep "$INTERVAL"
done
