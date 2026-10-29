#!/usr/bin/env bash
#
# cutover.sh — move a cluster onto the second pair that `rebuild-node.sh --next` built, in one window (#4128).
#
# Usage:
#   scripts/cutover.sh <staging|production> <phase> [--from <step>]
#
#   prepare    freeze switch                        the site serves; ends with `git pull`
#   cutover    check confirm stop-app stop-db power-off move-ip move-volume power-on access database resume site
#              follow-up rename                     the site is down from `stop-app` to `site`
#   finish     check backup state                   `tofu state mv`, then the apply to type, with its expected count
#   rollback   power-off move-back power-on access resume site       the old pair serves again; only before `finish`
#   --from     start at that step; every step checks what is done, so a rerun from the top also works
#
# The database address in deploy/clusters/<env> changes with the database, and both clusters read
# that path. So `prepare` freezes Flux on the old cluster before the switch PR merges, and the new
# cluster's app stays suspended until PostgreSQL runs on the new pair. Only the k3s IPv4 moves: no
# AAAA record exists, so each new node keeps its own IPv6.
#
# `finish` moves the old pair's state to `*_old` addresses and the new pair's to the running ones.
# The apply then deletes the `*_old` orphans and the temporary IPv4, and renames the new servers in
# place. `rename` gave the old names away first, so the renames cannot collide.
#
# The operator runs it, with the Hetzner token from infra/.envrc. It never runs `tofu apply`.
# Requires: curl, jq, ssh, kubectl, flux, sudo, wg-quick, direnv, tofu.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    echo "cutover: $*" >&2
    exit 1
}

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER_ACCESS="${EJ_CLUSTER_ACCESS:-$REPO/scripts/cluster-access.sh}"
REBUILD_NODE="${EJ_REBUILD_NODE:-$REPO/scripts/rebuild-node.sh}"
SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
WG_DIR="${EJ_WG_DIR:-$HOME/.wireguard}"
API="${EJ_HCLOUD_API:-https://api.hetzner.cloud/v1}"
TRIES="${EJ_TRIES:-60}"
SLEEP="${EJ_SLEEP:-5}"
SWITCH_TRIES="${EJ_SWITCH_TRIES:-120}"
SWITCH_SLEEP="${EJ_SWITCH_SLEEP:-30}"
APP_HR=event-junkie

ENV="${1:-}"
case "$ENV" in
    staging)
        TUNNEL_IP=10.10.1.1
        SITE=staging.event-junkie.de
        ;;
    production)
        TUNNEL_IP=10.10.0.1
        SITE=event-junkie.de
        ;;
    *) die "expected staging or production, got '${ENV}'" ;;
esac
HELM_RELEASE="${EJ_HELM_RELEASE:-$REPO/deploy/clusters/$ENV/helm-release.yaml}"
MAIN_TF="${EJ_MAIN_TF:-$REPO/infra/environments/$ENV/main.tf}"
PHASE="${2:-}"
case "$PHASE" in
    prepare) STEPS="freeze switch" ;;
    cutover) STEPS="check confirm stop-app stop-db power-off move-ip move-volume power-on access database resume site follow-up rename" ;;
    finish) STEPS="check backup state" ;;
    rollback) STEPS="power-off move-back power-on access resume site" ;;
    *) die "expected prepare, cutover, finish or rollback, got '${PHASE}'" ;;
esac
shift 2
FROM=""
while [ $# -gt 0 ]; do
    case "$1" in
        --from)
            [ $# -ge 2 ] || die "--from needs a step"
            FROM="$2"
            shift 2
            ;;
        *) die "unknown argument '$1'" ;;
    esac
done
if [ -n "$FROM" ] && [[ " $STEPS " != *" $FROM "* ]]; then
    die "no step '$FROM' in $PHASE; the steps are: $STEPS"
fi

unset KUBECONFIG
umask 077
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
# What outlives one invocation: the old server key for `rollback`, and when the site went down.
STATE_DIR="${EJ_CUTOVER_DIR:-$HOME/.event-junkie/cutover-$ENV}"
mkdir -p "$STATE_DIR"

CTX="event-junkie-$ENV"
NEXT_CTX="event-junkie-$ENV-next"
log() { echo "cutover: $CURRENT: $*"; }
k() { kubectl --context "$CTX" "$@"; }
kn() { kubectl --context "$NEXT_CTX" "$@"; }
tf() { direnv exec "$REPO/infra" tofu -chdir="$REPO/infra/environments/$ENV" "$@"; }

# --- The Hetzner API ------------------------------------------------------------------------------

# The token goes to curl in a header file, never as an argument.
token="${HCLOUD_TOKEN:-$(direnv exec "$REPO/infra" printenv HCLOUD_TOKEN 2>/dev/null || true)}"
[ -n "$token" ] || die "no HCLOUD_TOKEN: infra/.envrc sets it through direnv"
printf 'Authorization: Bearer %s\n' "$token" >"$WORK/auth"
unset token

api() {
    local args=(-sS --fail-with-body -H "@$WORK/auth" -X "$1")
    [ -z "${3:-}" ] || args+=(-H 'Content-Type: application/json' -d "$3")
    curl "${args[@]}" "$API$2" || die "Hetzner API $1 $2 failed"
}
# act <path> [<json>]: one action, waited for.
act() {
    local out id status
    out="$(api POST "$1" "${2:-}")"
    id="$(jq -r '.action.id' <<<"$out")"
    for _ in $(seq 1 "$TRIES"); do
        out="$(api GET "/actions/$id")"
        status="$(jq -r '.action.status' <<<"$out")"
        case "$status" in
            success) return 0 ;;
            error) die "$1: $(jq -r '.action.error.message' <<<"$out")" ;;
        esac
        sleep "$SLEEP"
    done
    die "$1: action $id still running after $((TRIES * SLEEP))s"
}
# The first of the names that exists; the old pair is renamed `-old` in step rename.
find_id() {
    local kind="$1" out id n
    shift
    for n in "$@"; do
        out="$(api GET "/$kind?name=$n")"
        id="$(jq -r --arg k "$kind" '.[$k][0].id // empty' <<<"$out")"
        [ -z "$id" ] || {
            echo "$id"
            return 0
        }
    done
}
server() {
    local out
    out="$(api GET "/servers/$1")"
    jq -r ".server | $2" <<<"$out"
}
status() { server "$1" .status; }
name() { server "$1" .name; }
assignee() {
    local out
    out="$(api GET "/primary_ips/$1")"
    jq -r '.primary_ip.assignee_id // empty' <<<"$out"
}
volume_server() {
    local out
    out="$(api GET "/volumes/$VOLUME")"
    jq -r '.volume.server // empty' <<<"$out"
}

# The two pairs. The database host is the k3s node where there is no database node: staging, or the
# cx43 fallback. SOURCE holds the volume before the cutover, TARGET after it.
OLD_K3S="$(find_id servers "$ENV-k3s-old" "$ENV-k3s")"
NEW_K3S="$(find_id servers "$ENV-k3s-next")"
SOURCE="$(find_id servers "$ENV-postgres-old" "$ENV-postgres")"
TARGET="$(find_id servers "$ENV-postgres-next")"
REAL_V4="$(find_id primary_ips "$ENV-k3s-ipv4")"
TEMP_V4="$(find_id primary_ips "$ENV-k3s-next-ipv4")"
VOLUME="$(find_id volumes "$ENV-pgdata")"
[ -n "$OLD_K3S" ] && [ -n "$REAL_V4" ] && [ -n "$VOLUME" ] || die "no $ENV-k3s, $ENV-k3s-ipv4 or $ENV-pgdata in the Hetzner project"
[ -n "$NEW_K3S" ] && [ -n "$TEMP_V4" ] || die "no $ENV-k3s-next with its temporary IPv4: order it (infra/order-next-pair.sh $ENV)"
SOURCE="${SOURCE:-$OLD_K3S}"
TARGET="${TARGET:-$NEW_K3S}"
NEW_K3S_IP="$(server "$NEW_K3S" '.private_net[0].ip')"
SOURCE_IP="$(server "$SOURCE" '.private_net[0].ip')"
TARGET_IP="$(server "$TARGET" '.private_net[0].ip')"

# --- Nodes and tunnels ----------------------------------------------------------------------------

base_ssh=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=5)
# on <server id> <private ip> <command…>: the k3s node is the tunnel address, a database node is behind it.
on() {
    local id="$1" ip="$2"
    shift 2
    if [ "$id" = "$OLD_K3S" ] || [ "$id" = "$NEW_K3S" ]; then
        "${base_ssh[@]}" "ops@$TUNNEL_IP" "$@"
    else
        "${base_ssh[@]}" -J "ops@$TUNNEL_IP" "ops@$ip" "$@"
    fi
}

iface_up() { [ -e "${EJ_WG_RUN_DIR:-/var/run/wireguard}/$1.name" ]; }
# tunnel_to <env | env-next>: both nodes are the same tunnel address, so one tunnel at a time.
tunnel_to() {
    local other="$ENV-next"
    [ "$1" = "$ENV" ] || other="$ENV"
    if iface_up "$other"; then sudo wg-quick down "$WG_DIR/$other.conf" >/dev/null; fi
    if ! iface_up "$1"; then sudo wg-quick up "$WG_DIR/$1.conf" >/dev/null; fi
}
peer_key() { awk '/^\[Peer\]/ { p = 1 } p && /^[[:space:]]*PublicKey/ { sub(/^[^=]*=[[:space:]]*/, ""); print; exit }' "$1"; }

power_off() {
    local id="$1" n
    n="$(name "$id")"
    if [ "$(status "$id")" = off ]; then
        log "$n: already off"
        return 0
    fi
    act "/servers/$id/actions/shutdown"
    for _ in $(seq 1 "$TRIES"); do
        if [ "$(status "$id")" = off ]; then
            log "$n: shut down"
            return 0
        fi
        sleep "$SLEEP"
    done
    act "/servers/$id/actions/poweroff"
    log "$n: did not shut down in $((TRIES * SLEEP))s, powered off"
}
power_on() {
    local id="$1" n
    n="$(name "$id")"
    if [ "$(status "$id")" = running ]; then
        log "$n: already running"
        return 0
    fi
    act "/servers/$id/actions/poweron"
    log "$n: powered on"
}
# The distinct servers among the arguments, in order.
distinct() { printf '%s\n' "$@" | awk 'NF && !seen[$0]++'; }

# assign_ipv4 <primary ip> <server>: Hetzner moves a Primary IP only while both servers are off.
assign_ipv4() {
    local now
    now="$(assignee "$1")"
    [ "$now" != "$2" ] || return 0
    if [ -n "$now" ]; then act "/primary_ips/$1/actions/unassign"; fi
    act "/primary_ips/$1/actions/assign" "{\"assignee_id\":$2,\"assignee_type\":\"server\"}"
}
attach_volume() {
    local now
    now="$(volume_server)"
    [ "$now" != "$1" ] || return 0
    [ -z "$now" ] || [ "$(status "$now")" = off ] || die "the volume is on $(name "$now"), which is running"
    if [ -n "$now" ]; then act "/volumes/$VOLUME/actions/detach"; fi
    act "/volumes/$VOLUME/actions/attach" "{\"server\":$1,\"automount\":false}"
}

# access <server key>: the k3s node needs a minute after power-on before the tunnel answers.
access() {
    for _ in $(seq 1 "$TRIES"); do
        if "$CLUSTER_ACCESS" "$ENV" --server-key "$1"; then
            k get nodes >/dev/null || die "$CTX does not answer"
            log "$CTX answers"
            return 0
        fi
        sleep "$SLEEP"
    done
    die "the tunnel to $TUNNEL_IP does not come up with server key $1"
}

# --- prepare --------------------------------------------------------------------------------------

step_freeze() {
    tunnel_to "$ENV"
    if [ ! -s "$STATE_DIR/old-server-key" ]; then
        peer_key "$WG_DIR/$ENV.conf" >"$STATE_DIR/old-server-key"
        log "old server key kept in $STATE_DIR for rollback"
    fi
    if [ "$(k get kustomization flux-system -n flux-system -o jsonpath='{.spec.suspend}')" = true ]; then
        log "Flux on $CTX: already frozen"
    else
        flux --context "$CTX" suspend kustomization flux-system -n flux-system >/dev/null
        log "Flux on $CTX: frozen. The old cluster applies no change from Git until the cutover"
    fi
}

step_switch() {
    local host=""
    tunnel_to "$ENV-next"
    log "merge the switch PR for $ENV now: the database host becomes $TARGET_IP. Waiting for $NEXT_CTX to read it"
    for _ in $(seq 1 "$SWITCH_TRIES"); do
        flux --context "$NEXT_CTX" reconcile kustomization flux-system -n flux-system --with-source >/dev/null 2>&1 || true
        host="$(kn get helmrelease "$APP_HR" -n flux-system -o jsonpath='{.spec.values.database.host}')"
        [ "$host" != "$TARGET_IP" ] || break
        sleep "$SWITCH_SLEEP"
    done
    [ "$host" = "$TARGET_IP" ] || die "$NEXT_CTX still names database host '$host', not $TARGET_IP"
    [ "$(kn get helmrelease "$APP_HR" -n flux-system -o jsonpath='{.spec.suspend}')" = true ] ||
        die "$APP_HR runs on $NEXT_CTX: suspend it at once (flux --context $NEXT_CTX suspend helmrelease $APP_HR)"
    log "$NEXT_CTX names $TARGET_IP, and $APP_HR is still suspended there"
    tunnel_to "$ENV"
    log "next: git pull, then scripts/cutover.sh $ENV cutover"
}

# --- cutover --------------------------------------------------------------------------------------

step_check() {
    if [ "$PHASE" = finish ]; then
        if ! grep -Eq "k3s_private_ip[[:space:]]*=[[:space:]]*\"$NEW_K3S_IP\"" "$MAIN_TF" ||
            ! grep -Eq "k3s_server_type[[:space:]]*=[[:space:]]*\"$(server "$NEW_K3S" .server_type.name)\"" "$MAIN_TF"; then
            die "$MAIN_TF does not name $ENV-k3s-next's type and address: merge and pull the switch PR first"
        fi
        [ "$(name "$OLD_K3S")" = "$ENV-k3s-old" ] || die "the old pair still has its names: run 'scripts/cutover.sh $ENV cutover --from rename'"
        log "main.tf names the new pair, and the old pair is renamed"
        return 0
    fi
    grep -qF "host: \"$TARGET_IP\"" "$HELM_RELEASE" ||
        die "this checkout does not name database host $TARGET_IP: merge the switch PR, then git pull"
    [ -s "$STATE_DIR/old-server-key" ] || die "run 'scripts/cutover.sh $ENV prepare' first"
    [ -f "$WG_DIR/$ENV-next.conf" ] || die "no $WG_DIR/$ENV-next.conf: run 'scripts/rebuild-node.sh $ENV --next' first"
    if [ "$(status "$OLD_K3S")" = running ]; then
        tunnel_to "$ENV"
        [ "$(k get kustomization flux-system -n flux-system -o jsonpath='{.spec.suspend}')" = true ] ||
            die "Flux on $CTX is not frozen: run 'scripts/cutover.sh $ENV prepare' first"
    fi
    log "the switch PR is pulled, Flux on the old cluster is frozen, and the next pair exists"
}

step_confirm() {
    local reply
    printf 'cutover: type yes to take https://%s down for about 8 minutes: ' "$SITE"
    read -r reply || true
    [ "$reply" = yes ] || die "not confirmed, nothing changed"
    date +%s >"$STATE_DIR/down-since"
}

step_stop_app() {
    if [ "$(status "$OLD_K3S")" != running ]; then
        log "$(name "$OLD_K3S") is off"
        return 0
    fi
    flux --context "$CTX" suspend helmrelease "$APP_HR" -n flux-system >/dev/null
    k scale deployment -n event-junkie -l app.kubernetes.io/component=importer --replicas=0 >/dev/null
    k wait --for=delete pod -n event-junkie -l app.kubernetes.io/component=importer --timeout=120s >/dev/null ||
        die "the importer pod does not stop: kubectl --context $CTX get pods -n event-junkie"
    log "importer stopped on the old cluster"
}

step_stop_db() {
    if [ "$(status "$SOURCE")" != running ]; then
        log "$(name "$SOURCE") is off"
        return 0
    fi
    # The checkpoint and the WAL switch put everything written so far into the archive before the stop.
    on "$SOURCE" "$SOURCE_IP" "sudo -u postgres psql -qc CHECKPOINT -c 'SELECT pg_switch_wal()' >/dev/null && sudo systemctl stop postgresql" ||
        die "cannot stop PostgreSQL on $(name "$SOURCE")"
    log "PostgreSQL stopped on $(name "$SOURCE")"
}

# A rerun after the moves powers nothing off: the new k3s node holds the IPv4 by then, and serves.
step_power_off() {
    local id
    if [ "$PHASE" = rollback ]; then
        if [ "$(assignee "$REAL_V4")" = "$OLD_K3S" ] && [ "$(volume_server)" = "$SOURCE" ]; then
            log "the IPv4 and the volume are on the old pair already"
            return 0
        fi
        for id in $(distinct "$NEW_K3S" "$TARGET"); do power_off "$id"; done
    elif [ "$(assignee "$REAL_V4")" = "$NEW_K3S" ]; then
        for id in $(distinct "$OLD_K3S" "$SOURCE"); do power_off "$id"; done
    else
        for id in $(distinct "$OLD_K3S" "$SOURCE" "$NEW_K3S"); do power_off "$id"; done
    fi
}

step_move_ip() {
    if [ "$(assignee "$REAL_V4")" = "$NEW_K3S" ]; then
        log "$ENV-k3s-ipv4 is on $ENV-k3s-next already"
        return 0
    fi
    [ "$(status "$OLD_K3S")" = off ] && [ "$(status "$NEW_K3S")" = off ] || die "both k3s nodes must be off: step power-off"
    if [ "$(assignee "$TEMP_V4")" = "$NEW_K3S" ]; then act "/primary_ips/$TEMP_V4/actions/unassign"; fi
    assign_ipv4 "$REAL_V4" "$NEW_K3S"
    log "$ENV-k3s-ipv4 is on $ENV-k3s-next"
}

step_move_volume() {
    attach_volume "$TARGET"
    log "$ENV-pgdata is on $(name "$TARGET")"
}

step_power_on() {
    local id
    if [ "$PHASE" = rollback ]; then
        for id in $(distinct "$OLD_K3S" "$SOURCE" "$NEW_K3S" "$TARGET"); do power_on "$id"; done
    else
        for id in $(distinct "$NEW_K3S" "$TARGET"); do power_on "$id"; done
    fi
}

step_access() {
    if [ "$PHASE" = rollback ]; then
        access "$(cat "$STATE_DIR/old-server-key")"
    else
        access "$(peer_key "$WG_DIR/$ENV-next.conf")"
    fi
}

step_database() {
    on "$TARGET" "$TARGET_IP" 'sudo /opt/event-junkie/step.sh postgres && sudo /opt/event-junkie/step.sh backups && sudo /opt/event-junkie/step.sh --result' ||
        die "postgres.sh or backups.sh failed on $(name "$TARGET"): /var/log/event-junkie/<name>.log there"
    [ "$(on "$TARGET" "$TARGET_IP" "sudo -u postgres psql -tAc 'SELECT 1'")" = 1 ] || die "PostgreSQL does not answer on $(name "$TARGET")"
    log "PostgreSQL runs on $(name "$TARGET") from the volume"
}

step_resume() {
    flux --context "$CTX" resume helmrelease "$APP_HR" -n flux-system >/dev/null || true
    # Staging's drift detection only warns, so a resume alone does not bring the importer back.
    [ "$PHASE" != rollback ] || k scale deployment -n event-junkie -l app.kubernetes.io/component=importer --replicas=1 >/dev/null
    k wait --for=condition=Ready "helmrelease/$APP_HR" -n flux-system --timeout=10m >/dev/null ||
        die "$APP_HR is not ready: flux --context $CTX get helmreleases"
    log "$APP_HR runs on $CTX"
}

step_site() {
    local resolve=() since
    [ "$ENV" = production ] || resolve=(--resolve "$SITE:443:$TUNNEL_IP")
    for _ in $(seq 1 "$TRIES"); do
        if curl -fsS -o /dev/null ${resolve[@]+"${resolve[@]}"} "https://$SITE/" &&
            curl -fsS -o /dev/null ${resolve[@]+"${resolve[@]}"} "https://$SITE/api/events?size=1"; then
            since="$(cat "$STATE_DIR/down-since" 2>/dev/null || true)"
            log "https://$SITE/ and /api/events answer${since:+, $(($(date +%s) - since))s after the confirm}"
            return 0
        fi
        sleep "$SLEEP"
    done
    die "https://$SITE/ does not answer 2xx"
}

# The in-place rebuild's last steps: github-dispatch, the wal-g credential and `walg check`, the old
# deploy key deleted, every HelmRelease ready, alerts and dashboards, and its own verify.
step_follow_up() { "$REBUILD_NODE" "$ENV" --from secrets; }

step_rename() {
    local id n out ip
    for id in $(distinct "$OLD_K3S" "$SOURCE"); do
        n="$(name "$id")"
        [[ "$n" == *-old ]] || api PUT "/servers/$id" "{\"name\":\"$n-old\"}" >/dev/null
    done
    # The old pair's own Primary IPs: deleted by the apply after `finish`, so protection comes off.
    out="$(api GET /primary_ips)"
    jq -r --argjson a "$OLD_K3S" --argjson b "$SOURCE" \
        '.primary_ips[] | select(.assignee_id == $a or .assignee_id == $b) | "\(.id) \(.name) \(.protection.delete)"' <<<"$out" >"$WORK/old-ips"
    while read -r ip n protected; do
        [[ "$n" == *-old ]] || api PUT "/primary_ips/$ip" "{\"name\":\"$n-old\"}" >/dev/null
        [ "$protected" != true ] || act "/primary_ips/$ip/actions/change_protection" '{"delete":false}'
    done <"$WORK/old-ips"
    log "the old servers and their own Primary IPs are named -old and unprotected. Next: the switch PR's infra part, then 'scripts/cutover.sh $ENV finish'"
}

# --- rollback -------------------------------------------------------------------------------------

step_move_back() {
    if [ "$(assignee "$REAL_V4")" = "$OLD_K3S" ] && [ "$(volume_server)" = "$SOURCE" ]; then
        log "the IPv4 and the volume are on the old pair already"
        return 0
    fi
    [ "$(status "$OLD_K3S")" = off ] && [ "$(status "$NEW_K3S")" = off ] || die "both k3s nodes must be off: step power-off"
    assign_ipv4 "$REAL_V4" "$OLD_K3S"
    assign_ipv4 "$TEMP_V4" "$NEW_K3S"
    attach_volume "$SOURCE"
    log "the IPv4 and the volume are back on the old pair. Flux there stays frozen: revert the switch PR before 'flux resume kustomization flux-system'"
}

# --- finish ---------------------------------------------------------------------------------------

step_backup() {
    local file
    file="$STATE_DIR/state-before-finish.$(date +%Y%m%d-%H%M%S).json"
    tf state pull >"$file"
    [ -s "$file" ] || die "the state pull wrote nothing"
    log "state copied to $file"
}

step_state() {
    local m=module.environment list from to moved_old=0 moved_next=0
    local -a pairs=(
        "hcloud_server.k3s hcloud_server.k3s_old"
        "hcloud_primary_ip.k3s_ipv6 hcloud_primary_ip.k3s_ipv6_old"
        "hcloud_server.postgres[0] hcloud_server.postgres_old"
        "hcloud_primary_ip.postgres_ipv4[0] hcloud_primary_ip.postgres_ipv4_old"
        "hcloud_primary_ip.postgres_ipv6[0] hcloud_primary_ip.postgres_ipv6_old"
        "hcloud_server.k3s_next[0] hcloud_server.k3s"
        "hcloud_primary_ip.k3s_next_ipv6[0] hcloud_primary_ip.k3s_ipv6"
        "hcloud_server.postgres_next[0] hcloud_server.postgres[0]"
        "hcloud_primary_ip.postgres_next_ipv4[0] hcloud_primary_ip.postgres_ipv4[0]"
        "hcloud_primary_ip.postgres_next_ipv6[0] hcloud_primary_ip.postgres_ipv6[0]"
    )
    list="$(tf state list)"
    for pair in "${pairs[@]}"; do
        read -r from to <<<"$pair"
        if grep -qxF "$m.$from" <<<"$list" && ! grep -qxF "$m.$to" <<<"$list"; then
            tf state mv "$m.$from" "$m.$to" >/dev/null
            list="$(tf state list)"
            log "$from -> $to"
        fi
        if [[ "$to" == *_old ]]; then
            grep -qxF "$m.$to" <<<"$list" && moved_old=$((moved_old + 1))
        elif [[ "$from" == *_next* ]] && grep -qxF "$m.$to" <<<"$list"; then
            moved_next=$((moved_next + 1))
        fi
    done
    log "next: infra/apply.sh $ENV --allow-replace, with no -var next_pair."
    log "expected: 0 to add, $moved_next to change, $((moved_old + 1)) to destroy: the renames, the *_old orphans and $ENV-k3s-next-ipv4."
    log "Any add or replace means the config does not match the new pair: answer no"
}

started=0
[ -n "$FROM" ] || started=1
for CURRENT in $STEPS; do
    [ "$CURRENT" = "$FROM" ] && started=1
    [ "$started" = 1 ] || continue
    "step_${CURRENT//-/_}"
done
CURRENT="done"
log "$ENV $PHASE: every step passed"
