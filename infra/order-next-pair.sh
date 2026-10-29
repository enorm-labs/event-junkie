#!/usr/bin/env bash
#
# order-next-pair.sh — wait until Hetzner sells the new pair's server types, then create the pair at once (#4128).
#
# Usage:
#   infra/order-next-pair.sh <staging|production> [--interval <seconds>]
#
#   production   cx33 + cx23 in nbg1     staging   cx33 in nbg1
#   EJ_K3S_TYPE and EJ_POSTGRES_TYPE override the types, --interval the wait between checks (900).
#
# Each round asks the free advertisement first. Only when it says yes does it run
# `apply.sh <env> --yes-if-only-creates … -var next_pair=…`, which is the order itself: no probe
# first, because capacity has gone between a probe and the apply before. That apply needs no
# person while the plan only creates the *_next servers and Primary IPs. Anything else, such as
# a drift, stops this with exit 3 and applies nothing.
#
# A failed apply, usually `resource_unavailable`, waits and tries again. A partial pair is fine:
# the next plan creates the rest. Ends with `say` and a notification, success or not.
#
# Requires: direnv with infra/.envrc allowed, jq, tofu. Run it in a terminal tab that stays open.

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

ENV="${1:-}"
LOCATION=nbg1
case "$ENV" in
    production)
        K3S_TYPE="${EJ_K3S_TYPE:-cx33}"
        POSTGRES_TYPE="${EJ_POSTGRES_TYPE:-cx23}"
        ;;
    staging)
        K3S_TYPE="${EJ_K3S_TYPE:-cx33}"
        POSTGRES_TYPE=""
        ;;
    *) die "expected staging or production, got '${ENV}'" ;;
esac
shift
INTERVAL=900
while [ $# -gt 0 ]; do
    case "$1" in
        --interval)
            [ $# -ge 2 ] || die "--interval needs seconds"
            INTERVAL="$2"
            shift 2
            ;;
        *) die "unknown argument '$1'" ;;
    esac
done

SPEC="$K3S_TYPE@$LOCATION"
NEXT_PAIR="{k3s_server_type=\"$K3S_TYPE\"}"
if [ -n "$POSTGRES_TYPE" ]; then
    SPEC="$SPEC,$POSTGRES_TYPE@$LOCATION"
    NEXT_PAIR="{k3s_server_type=\"$K3S_TYPE\",postgres_server_type=\"$POSTGRES_TYPE\"}"
fi

tell() {
    echo "order-next-pair: $1"
    "$NOTIFY" "$1" >/dev/null 2>&1 || true
}

echo "order-next-pair: waiting for $SPEC, checking every ${INTERVAL}s. Ctrl-C stops it."
while :; do
    rc=0
    direnv exec "$INFRA" "$CAPACITY" "$SPEC" >/dev/null 2>&1 || rc=$?
    case "$rc" in
        0)
            echo "order-next-pair: $(date +%H:%M) $SPEC advertised, ordering"
            rc=0
            "$APPLY" "$ENV" --yes-if-only-creates "$NEXT_RE" -var "next_pair=$NEXT_PAIR" </dev/null || rc=$?
            case "$rc" in
                0)
                    tell "the $ENV next pair is ordered"
                    exit 0
                    ;;
                3)
                    tell "the $ENV plan needs a person, nothing was ordered"
                    exit 3
                    ;;
                *) echo "order-next-pair: $(date +%H:%M) the apply failed (exit $rc), trying again" ;;
            esac
            ;;
        1) echo "order-next-pair: $(date +%H:%M) not advertised" ;;
        *) echo "order-next-pair: $(date +%H:%M) check-capacity.sh failed (exit $rc), trying again" ;;
    esac
    sleep "$INTERVAL"
done
