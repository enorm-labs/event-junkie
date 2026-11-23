#!/usr/bin/env bash
#
# rotate-o2-root.sh — rotate the OpenObserve root password of one cluster, end to end (#5325).
#
# Usage:
#   scripts/rotate-o2-root.sh <staging|production>             change it, copy it everywhere, restart, check
#   scripts/rotate-o2-root.sh <staging|production> --dry-run   only the checks that change nothing
#
# The password lives in OpenObserve's own database. The Secret creates the root user at first boot
# only, so the change starts at the API: PUT the new password, then require the new one to answer 200
# and the old one 401. Only then the Keychain, `cluster-secrets.sh` for the four Secret copies and the
# header, and a restart of OpenObserve and both collectors, which read the values at start. Last,
# `deploy/alerts/apply.sh` for this cluster: the `record-only` alert destination stores the root
# header itself, so no restart reaches it (#5425).
# SECRETS.md § Rotating the OpenObserve root password has the same steps by hand.
#
# The Keychain password must answer 200 before anything changes. That also proves the port reaches
# this cluster: the other cluster's password answers 401. Without a forward on 5080 (staging) or
# 25080 (production), the script starts one and stops it on exit.
#
# No password reaches an argument list but one: `security add-generic-password -w`, as in
# cluster-secrets.sh. curl reads an Authorization header from a file in a 0700 temp dir, and jq reads
# the passwords from its environment. A run that stops after the PUT says where, and what finishes it.
#
# EJ_KUBE_CONTEXT names another context. EJ_CLUSTER_SECRETS, EJ_O2_QUERY and EJ_ALERTS_APPLY replace the
# three scripts it calls, and EJ_SETTLE_SECONDS (60) and EJ_POLL_SECONDS (3) the waits, for
# rotate-o2-root-test.sh.
#
# Requires: the WireGuard tunnel, kubectl, jq, curl, openssl, base64, security (macOS), and ssh and
# python3 for apply.sh.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    echo "rotate-o2-root: $*" >&2
    exit 1
}

ENV="${1:-}"
case "$ENV" in
    staging) PORT=5080 NODE=ops@10.10.1.1 ;;
    production) PORT=25080 NODE=ops@10.10.0.1 ;;
    *) die "expected staging or production, got '${ENV}'" ;;
esac
DRY_RUN=""
case "${2:-}" in
    "") ;;
    --dry-run) DRY_RUN=1 ;;
    *) die "unknown argument '$2'" ;;
esac
[ $# -le 2 ] || die "too many arguments"

for tool in kubectl jq curl openssl base64 security ssh python3; do
    command -v "$tool" >/dev/null || die "needs $tool"
done

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER_SECRETS="${EJ_CLUSTER_SECRETS:-$REPO_ROOT/scripts/cluster-secrets.sh}"
O2_QUERY="${EJ_O2_QUERY:-$REPO_ROOT/scripts/o2-query.sh}"
ALERTS_APPLY="${EJ_ALERTS_APPLY:-$REPO_ROOT/deploy/alerts/apply.sh}"
SETTLE="${EJ_SETTLE_SECONDS:-60}"
POLL="${EJ_POLL_SECONDS:-3}"
CTX="${EJ_KUBE_CONTEXT:-event-junkie-$ENV}"
EMAIL=hello@event-junkie.de
ITEM="event-junkie-$ENV-o2-root-password"
BASE="http://localhost:$PORT"
COLLECTORS="app.kubernetes.io/managed-by=opentelemetry-operator"

k() { kubectl --context "$CTX" --request-timeout=20s "$@"; }

umask 077
WORK="$(mktemp -d)"
FORWARD=""
# What a stop leaves behind, set as the run passes each point of no return.
STAGE=""
cleanup() {
    local code=$?
    [ -z "$FORWARD" ] || kill "$FORWARD" 2>/dev/null || true
    rm -rf "$WORK"
    [ "$code" -ne 0 ] || return 0
    case "$STAGE" in
        keychain)
            echo "rotate-o2-root: OpenObserve and the Keychain hold the new password; the cluster may not. Finish with:" >&2
            echo "  scripts/cluster-secrets.sh $ENV && scripts/cluster-secrets.sh $ENV --check" >&2
            echo "  kubectl --context $CTX -n observability rollout restart statefulset/openobserve-openobserve-standalone" >&2
            echo "  kubectl --context $CTX -n observability delete pod -l $COLLECTORS" >&2
            echo "  kubectl --context $CTX -n observability-agent delete pod -l $COLLECTORS" >&2
            echo "  EJ_NODE=$NODE deploy/alerts/apply.sh && EJ_NODE=$NODE deploy/alerts/apply.sh --diff" >&2
            ;;
        restarted)
            echo "rotate-o2-root: the rotation is through; a check after it failed. SECRETS.md § Rotating has the checks." >&2
            echo "rotate-o2-root: the record-only alert destination can still hold the old password. Finish with:" >&2
            echo "  EJ_NODE=$NODE deploy/alerts/apply.sh && EJ_NODE=$NODE deploy/alerts/apply.sh --diff" >&2
            ;;
    esac
}
trap cleanup EXIT

# header <file> <password>: an Authorization header for root, written where no argument list sees it.
header() {
    printf 'Authorization: Basic %s\n' "$(printf '%s:%s' "$EMAIL" "$2" | base64 | tr -d '\n')" >"$WORK/$1"
}
# status <header file>: the HTTP code OpenObserve answers that header with.
status() {
    curl -s -o /dev/null -w '%{http_code}' --max-time 10 -H "@$WORK/$1" "$BASE/api/default/streams" || true
}

if ! curl -s -o /dev/null --max-time 3 "$BASE/healthz"; then
    k -n observability port-forward svc/openobserve-openobserve-standalone "$PORT:5080" >/dev/null 2>&1 &
    FORWARD=$!
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        curl -s -o /dev/null --max-time 2 "$BASE/healthz" && break
        sleep 1
    done
fi

OLD="$(security find-generic-password -s "$ITEM" -w 2>/dev/null | tr -d '\n')" || die "no Keychain item $ITEM"
[ -n "$OLD" ] || die "the Keychain item $ITEM is empty"
header old "$OLD"
code="$(status old)"
[ "$code" = 200 ] || die "the Keychain password answers $code on $BASE, not 200. Either the forward on $PORT reaches the other cluster, or $ITEM is stale; nothing has changed"
echo "rotate-o2-root: the Keychain password logs in on $CTX"
if [ -n "$DRY_RUN" ]; then
    echo "rotate-o2-root: dry run, nothing changed"
    exit 0
fi

# The suffix gives the four character classes OpenObserve requires.
NEW="$(openssl rand -base64 24 | tr -d '\n/+=')Aa1!"
header new "$NEW"

put="$(OLD_PW="$OLD" NEW_PW="$NEW" jq -n '{change_password: true, old_password: env.OLD_PW, new_password: env.NEW_PW}' |
    curl -s -o /dev/null -w '%{http_code}' --max-time 20 -X PUT -H "@$WORK/old" -H 'Content-Type: application/json' \
        --data @- "$BASE/api/default/users/$EMAIL")" || true
new_code="$(status new)"
old_code="$(status old)"
case "$new_code/$old_code" in
    200/401) ;;
    401/200) die "OpenObserve answered $put to the change, and the old password is still the valid one. Nothing has changed" ;;
    *)
        security add-generic-password -U -a "${USER:-$(id -un)}" -s "$ITEM-new" -w "$NEW"
        die "after the change ($put) the new password answers $new_code and the old one $old_code. The Keychain item $ITEM is unchanged; the new password is in $ITEM-new. Log in with each to see which one holds"
        ;;
esac
echo "rotate-o2-root: OpenObserve took the new password; the old one answers 401"

STAGE=keychain
security add-generic-password -U -a "${USER:-$(id -un)}" -s "$ITEM" -w "$NEW"
[ "$(security find-generic-password -s "$ITEM" -w | tr -d '\n')" = "$NEW" ] || die "the Keychain item $ITEM does not read back as written"
echo "rotate-o2-root: the Keychain holds it"

"$CLUSTER_SECRETS" "$ENV" >/dev/null
"$CLUSTER_SECRETS" "$ENV" --check | tail -1

# Pod uids before the restart, so the wait below sees the replacements and not the pods it deleted.
uids() { k -n "$1" get pods -l "$COLLECTORS" -o jsonpath='{range .items[*]}{.metadata.uid}{"\n"}{end}'; }
before="$(
    uids observability
    uids observability-agent
)"

k -n observability rollout restart statefulset/openobserve-openobserve-standalone >/dev/null
k -n observability rollout status statefulset/openobserve-openobserve-standalone --timeout=5m >/dev/null
echo "rotate-o2-root: OpenObserve restarted"
for ns in observability observability-agent; do
    k -n "$ns" delete pod -l "$COLLECTORS" --wait=false >/dev/null
done

# Ready means every collector pod is new, and every container in it reports ready.
collectors_ready() {
    local ns line
    for ns in observability observability-agent; do
        line="$(k -n "$ns" get pods -l "$COLLECTORS" -o jsonpath='{range .items[*]}{.metadata.uid}{" "}{.status.containerStatuses[*].ready}{"\n"}{end}')"
        [ -n "$line" ] || return 1
        while read -r uid ready; do
            if grep -qxF "$uid" <<<"$before" || [ -z "$ready" ] || [[ "$ready" == *false* ]]; then
                return 1
            fi
        done <<<"$line"
    done
}
for _ in $(seq 1 60); do
    collectors_ready && break
    sleep "$POLL"
done
collectors_ready || die "the collectors are not all new and Ready after the restart"
STAGE=restarted
echo "rotate-o2-root: both collectors restarted and Ready"

# The header copy the deploy/*/apply.sh scripts read.
printf 'Authorization: %s\n' "$(k -n flux-system get secret openobserve-credentials -o 'jsonpath={.data.O2_BASIC_AUTH_HEADER}' | base64 -d)" >"$WORK/cluster"
code="$(status cluster)"
[ "$code" = 200 ] || die "the flux-system header answers $code, not 200"
echo "rotate-o2-root: the flux-system header logs in"

since=$(($(date +%s) * 1000000))
sleep "$SETTLE"
refused=0
for ns in observability observability-agent; do
    for pod in $(k -n "$ns" get pods -l "$COLLECTORS" -o name); do
        n="$(k -n "$ns" logs "$pod" 2>/dev/null | grep -c -i unauthorized || true)"
        refused=$((refused + n))
    done
done
[ "$refused" -eq 0 ] || die "$refused Unauthorized line(s) in the collector logs since the restart"
rows="$("$O2_QUERY" "$ENV" sql "SELECT count(*) AS n FROM \"default\" WHERE _timestamp > $since" --hours 1 | jq -r '.[0].n // 0')" || rows=""
[[ "$rows" =~ ^[0-9]+$ ]] || die "o2-query.sh gave no row count for the ${SETTLE}s after the restart"
[ "$rows" -gt 0 ] || die "no rows in \"default\" in the ${SETTLE}s after the restart"
echo "rotate-o2-root: no Unauthorized in the collector logs, $rows row(s) since the restart"

# --diff fails on any drift, so only the record-only line decides. It prints header values as hashes.
EJ_NODE="$NODE" "$ALERTS_APPLY" >/dev/null
drift="$(EJ_NODE="$NODE" "$ALERTS_APPLY" --diff 2>&1)" || true
if ! grep -qE '^record-only \(destination\) +in sync$' <<<"$drift"; then
    printf '%s\n' "$drift" >&2
    die "deploy/alerts/apply.sh --diff does not report record-only in sync on $NODE"
fi
STAGE=""
echo "rotate-o2-root: the alerts are re-applied, and record-only is in sync"
echo "rotate-o2-root: rotated on $CTX. Copy it to the password manager with:"
printf '  security find-generic-password -s %s -w | tr -d %s | pbcopy\n' "$ITEM" "'\\n'"
