#!/usr/bin/env bash
#
# node-reboot.sh — reboot the cluster nodes in order, and prove each one came back before the next.
#
# Usage:
#   scripts/node-reboot.sh check [staging|production|all]            # read-only: what waits on each node, and any running import
#   scripts/node-reboot.sh reboot [staging|production|all] [--yes]   # reboot the nodes that wait, in order; --force reboots all
#
# Needs the WireGuard tunnel and the importer and OpenObserve forwards (scripts/ej.sh up <environment>)
# and kubeconfig contexts named event-junkie-<environment>. `check` reads only. `reboot` runs
# `sudo systemctl reboot` on each node, so the operator runs it, not an agent.
#
# The order is the one in docs/ops/CLUSTER_BOOTSTRAP.md § Rebooting a node: the production database
# node, the production k3s node, then staging, so the applications restart with their database up.
# A node with nothing waiting is skipped. The first failed check stops the run before the next node.
#
# Per node: wait for no import in RUNNING (a reboot marks it FAILED), reboot, wait for a new boot ID
# (SSH answers before the node goes down), rewrite the patch-state metric, then wait for PostgreSQL,
# every pod ready, the production site, and on a k3s node a k8s_events record newer than the reboot.
# The gateway collector can lose its watches over a k3s restart and never retry (#3198), so a missing
# record restarts the collector pod once.
#
# Exit code: 0 when every node is done or had nothing to do, 1 when a check failed, 2 on bad arguments.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
# ServerAlive ends a session that hangs while the node goes down; ConnectTimeout alone does not.
SSH_OPTS=(-i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=5 -o ServerAliveInterval=5 -o ServerAliveCountMax=2 -o LogLevel=ERROR)
# Ubuntu's perl warns about the forwarded LC_CTYPE on every needrestart call.
REMOTE_ENV='export LC_ALL=C.UTF-8;'

VERB="${1:-}"
[ $# -gt 0 ] && shift
SCOPE=all
ASSUME_YES=0
FORCE=0
while [ $# -gt 0 ]; do
    case "$1" in
        staging | production | all) SCOPE="$1" ;;
        --yes) ASSUME_YES=1 ;;
        --force) FORCE=1 ;;
        *)
            echo "node-reboot: unknown argument '$1'" >&2
            exit 2
            ;;
    esac
    shift
done
case "$VERB" in
    check | reboot) ;;
    *)
        echo "node-reboot: the first argument is check or reboot; --help lists both" >&2
        exit 2
        ;;
esac

# name environment ssh-target roles (db: runs PostgreSQL, k3s: runs the cluster)
NODES=(
    "production-db production -J,ops@10.10.0.1,ops@10.0.1.21 db"
    "production-k3s production ops@10.10.0.1 k3s"
    "staging staging ops@10.10.1.1 k3s,db"
)

say() { printf '%s  %s\n' "$(date +%H:%M:%S)" "$*"; }
fail() {
    printf '%s  FAIL  %s\n' "$(date +%H:%M:%S)" "$*" >&2
    exit 1
}

in_scope() { [ "$SCOPE" = all ] || [ "$SCOPE" = "$1" ]; }

node_ssh() {
    local -a hops
    # The target is a comma list, so a jump host fits one field of NODES: -J,<jump>,<host>
    IFS=, read -r -a hops <<<"$1"
    # shellcheck disable=SC2029 # expanded here on purpose: the command is a string for the node
    ssh "${SSH_OPTS[@]}" "${hops[@]}" "$REMOTE_ENV $2"
}

importer_port() { [ "$1" = production ] && echo 28081 || echo 18081; }

# The slugs in RUNNING, one per line; fails when the forward does not answer.
running_imports() {
    local port page body
    port="$(importer_port "$1")"
    for page in 0 1 2; do
        body="$(curl -sf -m 15 "http://localhost:$port/api/admin/event-sources?size=100&page=$page")" || return 1
        jq -r '.content[] | select(.status == "RUNNING") | .slug' <<<"$body"
    done
}

# The pods not ready, one per line; fails when the API server does not answer. With a time, a pod
# also counts as not ready until its Ready condition changed after it: right after a k3s boot the API
# still reports the pods as they were before the reboot.
unready_pods() {
    local pods
    pods="$(kubectl --context "event-junkie-$1" --request-timeout=20s get pods -A -o json 2>/dev/null)" || return 1
    jq -r --arg since "${2:-}" '.items[] | select(.status.phase != "Succeeded")
        | ((.status.conditions // []) | map(select(.type == "Ready")) | first) as $ready
        | select($ready == null or $ready.status != "True" or ($since != "" and $ready.lastTransitionTime <= $since))
        | "\(.metadata.namespace)/\(.metadata.name)"' <<<"$pods"
}

# Waits until "$@" succeeds, every $2 seconds, for at most $1 seconds.
wait_for() {
    local deadline=$(($(date +%s) + $1)) interval="$2"
    shift 2
    until "$@"; do
        [ "$(date +%s)" -lt "$deadline" ] || return 1
        sleep "$interval"
    done
}

# What waits on a node, as lines: `kernel <n>`, `reboot-required`, `service <unit>`. needrestart's
# KSTA is 1 when the running kernel is the newest installed, 2 or 3 when a newer one waits.
pending() {
    node_ssh "$1" 'test -e /var/run/reboot-required && echo reboot-required;
        sudo -n needrestart -b -r l 2>/dev/null | sed -n "s/^NEEDRESTART-KSTA: /kernel /p; s/^NEEDRESTART-SVC: /service /p"'
}

needs_reboot() {
    grep -q -e '^reboot-required' -e '^service ' -e '^kernel [23]' <<<"$1"
}

check_node() {
    local name="$1" env="$2" target="$3" out
    out="$(pending "$target")" || {
        echo "$name: unreachable - is the tunnel up? scripts/ej.sh up $env"
        return 1
    }
    if needs_reboot "$out"; then
        echo "$name: reboot waits"
        sed -n 's/^service /  service waiting: /p; s/^reboot-required$/  \/var\/run\/reboot-required is set/p' <<<"$out"
        grep -q '^kernel [23]' <<<"$out" && echo '  a newer kernel is installed'
    else
        echo "$name: nothing waits"
    fi
    return 0
}

do_check() {
    local line name env target roles imports status=0 checked=" "
    for line in "${NODES[@]}"; do
        read -r name env target roles <<<"$line"
        in_scope "$env" || continue
        check_node "$name" "$env" "$target" || status=1
        case "$checked" in *" $env "*) continue ;; esac
        checked="$checked$env "
        if imports="$(running_imports "$env")"; then
            [ -z "$imports" ] || echo "  $env imports running: $(tr '\n' ' ' <<<"$imports")"
        else
            echo "  $env importer forward on $(importer_port "$env") does not answer: scripts/ej.sh up $env"
            status=1
        fi
    done
    return "$status"
}

no_imports() {
    local imports
    imports="$(running_imports "$1")" && [ -z "$imports" ]
}

boot_id() { node_ssh "$1" 'cat /proc/sys/kernel/random/boot_id'; }
boot_id_changed() {
    local now
    now="$(boot_id "$1" 2>/dev/null)" && [ -n "$now" ] && [ "$now" != "$2" ]
}

patch_state_zero() {
    local metrics
    # shellcheck disable=SC2016 # expanded on the node
    metrics="$(node_ssh "$1" 'sudo -n /usr/local/sbin/ej-patch-state &&
        curl -s "$(sudo -n sh -c ". /etc/event-junkie/bootstrap.env; echo \$PRIVATE_IPV4"):9100/metrics"')" || return 1
    grep -q '^node_reboot_required_age_seconds 0$' <<<"$metrics" &&
        grep -q '^node_service_restart_pending_age_seconds 0$' <<<"$metrics"
}

postgres_active() { node_ssh "$1" "systemctl list-units --state=active --no-legend 'postgresql@*' | grep -q ."; }
pods_ready() {
    local pods
    pods="$(unready_pods "$1" "${2:-}")" && [ -z "$pods" ]
}
site_up() {
    local url
    for url in https://event-junkie.de/ 'https://event-junkie.de/api/events?size=1'; do
        [ "$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$url")" = 200 ] || return 1
    done
}
k8s_events_since() {
    local latest
    latest="$("$REPO_ROOT/scripts/o2-query.sh" "$1" sql 'SELECT _timestamp FROM k8s_events ORDER BY _timestamp DESC' --hours 1 2>/dev/null |
        jq -r '.[0]._timestamp // empty' 2>/dev/null)"
    [ -n "$latest" ] && [[ "$latest" > "$2" ]]
}

reboot_node() {
    local name="$1" env="$2" target="$3" roles="$4" old since imports
    imports="$(running_imports "$env")" || fail "$name: the importer forward on $(importer_port "$env") does not answer"
    if [ -n "$imports" ]; then
        say "$name: imports running: $(tr '\n' ' ' <<<"$imports"); waiting up to 30 minutes"
        wait_for 1800 30 no_imports "$env" || fail "$name: imports still running after 30 minutes"
    fi

    old="$(boot_id "$target")" || fail "$name: unreachable before the reboot"
    since="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    say "$name: rebooting"
    node_ssh "$target" 'sudo -n systemctl reboot' || true

    wait_for 600 10 boot_id_changed "$target" "$old" || fail "$name: no new boot ID after 10 minutes"
    say "$name: back"

    wait_for 120 10 patch_state_zero "$target" || fail "$name: a reboot or service restart still waits: $(pending "$target" | tr '\n' ' ')"
    say "$name: nothing waits any more"
    case ",$roles," in *,db,*)
        wait_for 120 5 postgres_active "$target" || fail "$name: PostgreSQL is not active"
        say "$name: PostgreSQL active"
        ;;
    esac
    # A k3s reboot restarts every pod; a database reboot only breaks their connections.
    local restarted=
    case ",$roles," in *,k3s,*) restarted="$since" ;; esac
    wait_for 600 10 pods_ready "$env" "$restarted" ||
        fail "$name: pods not ready after 10 minutes: $(unready_pods "$env" "$restarted" | tr '\n' ' ')"
    say "$name: every $env pod ready"
    if [ "$env" = production ]; then
        wait_for 300 10 site_up || fail "$name: the site or /api/events does not answer 200"
        say "$name: site and /api/events answer 200"
    fi
    case ",$roles," in *,k3s,*)
        if ! wait_for 300 20 k8s_events_since "$env" "$since"; then
            say "$name: no Kubernetes event since the reboot; restarting the gateway collector (#3198)"
            kubectl --context "event-junkie-$env" -n observability delete pod o2c-openobserve-collector-gateway-collector-0
            wait_for 300 20 k8s_events_since "$env" "$since" || fail "$name: still no Kubernetes event in OpenObserve"
        fi
        say "$name: Kubernetes events arrive"
        ;;
    esac
}

do_reboot() {
    local line name env target roles out plan=()
    for line in "${NODES[@]}"; do
        read -r name env target roles <<<"$line"
        in_scope "$env" || continue
        out="$(pending "$target")" || fail "$name: unreachable - is the tunnel up? scripts/ej.sh up $env"
        if [ "$FORCE" = 1 ] || needs_reboot "$out"; then
            plan+=("$line")
        else
            say "$name: nothing waits, skipped"
        fi
    done
    if [ "${#plan[@]}" = 0 ]; then
        say "nothing to reboot"
        return 0
    fi

    echo "Reboots, in this order:"
    for line in "${plan[@]}"; do echo "  ${line%% *}"; done
    [ "${#plan[@]}" -gt 0 ] && grep -q ' production ' <<<"${plan[*]}" && echo "The production site is down for about 2 minutes."
    if [ "$ASSUME_YES" != 1 ]; then
        read -r -p "Go? [y/N] " answer
        [ "$answer" = y ] || [ "$answer" = Y ] || {
            echo "Stopped. Nothing was rebooted."
            exit 1
        }
    fi

    for line in "${plan[@]}"; do
        read -r name env target roles <<<"$line"
        reboot_node "$name" "$env" "$target" "$roles"
    done
    say "done: ${#plan[@]} node(s) rebooted and checked"
}

case "$VERB" in
    check) do_check ;;
    reboot) do_reboot ;;
esac
