#!/usr/bin/env bash
#
# ej.sh — the operator's session: the tunnel and the port-forwards, up in one command and down in one.
#
# Usage:
#   scripts/ej.sh up <staging|production>       # tunnel, handshake check, /etc/hosts check, then the forwards
#   scripts/ej.sh down [staging|production] [--keep-tunnel]
#                                               # every forward this script started, then the tunnel(s)
#   scripts/ej.sh status                        # tunnels, forwards, both clusters, anything not Ready
#   scripts/ej.sh versions                      # what each cluster runs, and what Flux would resolve next
#   scripts/ej.sh urls [staging|production]     # the local URLs the forwards serve
#
# Requires: wg-quick (with sudo), kubectl with both contexts, ssh, curl, python3, and pg_isready
# (or nc) for the database check; `versions` needs yq and helm. Nothing here writes to a cluster —
# `kubectl get`, `port-forward` and an SSH local forward only.
#
# Four forwards per environment, on ports that cannot collide with each other or with `dev-env.sh`'s
# 8080/8081; the `1` prefix is staging, `2` production (http/http-client.env.json's convention):
#
#   forward     staging   production   what is behind it
#   importer    18081     28081        admin API and Swagger UI — no Ingress names it (ADR-023)
#   bff         18080     28080        Swagger UI — /webjars/** is not under /api
#   openobserve  5080     25080        logs, metrics, dashboards — ClusterIP, unrouted
#   database    15432     15433        PostgreSQL, by SSH through the node — pg_hba refuses the tunnel
#                                      address (CLUSTER_ACCESS.md §7)
#
# The tunnel state is read from what wg-quick writes (`/var/run/wireguard/<env>.name` on macOS, the
# interface on Linux), not from a ping; the ping is the handshake check after `up`, because an
# interface appears whether or not UDP/51820 is open and a missing handshake is the only symptom.
# Everything started is recorded under build/ej/ (gitignored); `down` kills only pids it wrote.
# Each kubectl forward runs under a watchdog that starts a new one when a rollout replaces the pod
# (#2976); `status` counts the restarts.
# `up`, `status` and `versions` also write docs/ops/dashboard/status.js for the local operations page
# (#1185), which can fetch nothing from file://.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
STATE_DIR="$REPO_ROOT/build/ej"
STATUS_JS="$REPO_ROOT/docs/ops/dashboard/status.js"
WG_DIR="${EJ_WG_DIR:-$HOME/.wireguard}"
RELEASES="https://github.com/enorm-labs/event-junkie/releases/tag"
COMMITS="https://github.com/enorm-labs/event-junkie/commit"

ENVS=(staging production)

die() {
    printf 'ej.sh: %s\n' "$1" >&2
    exit 1
}

# --- per-environment facts ---------------------------------------------------------------------
# Functions rather than associative arrays: macOS ships bash 3.2.

node_of() { case "$1" in staging) echo 10.10.1.1 ;; production) echo 10.10.0.1 ;; esac }
context_of() { echo "event-junkie-$1"; }
host_of() { case "$1" in staging) echo staging.event-junkie.de ;; production) echo event-junkie.de ;; esac }
prefix_of() { case "$1" in staging) echo 1 ;; production) echo 2 ;; esac }

# The chart's own value, so a moved database node moves the tunnel too.
database_host_of() {
    awk '/^    database:/ { d = 1 } d && /^      host:/ { gsub(/"/, "", $2); print $2; exit }' \
        "$REPO_ROOT/deploy/clusters/$1/helm-release.yaml"
}

# forward_spec <env> <name> → "<local-port> <namespace> <service> <remote-port>", or for the
# database "<local-port> ssh <node> <host:port>"
forward_spec() {
    local p
    p="$(prefix_of "$1")"
    case "$2" in
        database)
            if [ "$1" = staging ]; then echo "15432 ssh $(node_of "$1") $(database_host_of "$1"):5432"; else echo "15433 ssh $(node_of "$1") $(database_host_of "$1"):5432"; fi
            ;;
        importer) echo "${p}8081 event-junkie event-junkie-importer 8081" ;;
        bff) echo "${p}8080 event-junkie event-junkie-bff 8080" ;;
        openobserve)
            if [ "$1" = staging ]; then
                echo "5080 observability openobserve-openobserve-standalone 5080"
            else echo "25080 observability openobserve-openobserve-standalone 5080"; fi
            ;;
    esac
}
FORWARDS=(importer bff openobserve database)

url_of() {
    local port
    port="$(forward_spec "$1" "$2" | cut -d' ' -f1)"
    case "$2" in
        importer | bff) echo "http://localhost:${port}/webjars/swagger-ui/index.html" ;;
        openobserve) echo "http://localhost:${port}/" ;;
        database) echo "postgresql://events@127.0.0.1:${port}/events" ;;
    esac
}

check_env() {
    case "${1:-}" in
        staging | production) ;;
        *) die "expected staging or production, got '${1:-}'" ;;
    esac
}

# --- tunnels ------------------------------------------------------------------------------------

tunnel_up() {
    if [ "$(uname -s)" = Darwin ]; then
        [ -e "/var/run/wireguard/$1.name" ]
    else
        ip link show "$1" >/dev/null 2>&1
    fi
}

node_answers() { ping -c 1 -W 2 "$(node_of "$1")" >/dev/null 2>&1; }

tunnel_start() {
    local env="$1" conf="$WG_DIR/$1.conf"
    [ -f "$conf" ] || die "no $conf — CLUSTER_ACCESS.md §1 is how it is made"
    if tunnel_up "$env"; then
        echo "tunnel $env: already up"
    else
        sudo wg-quick up "$conf"
    fi
    for _ in $(seq 1 10); do
        if node_answers "$env"; then
            echo "tunnel $env: up ($(node_of "$env") answers)"
            return 0
        fi
        sleep 1
    done
    echo "tunnel $env: interface is up but $(node_of "$env") does not answer — check 'sudo wg show' for a handshake." >&2
    echo "            No handshake usually means outbound UDP/51820 is blocked, not a broken node." >&2
    return 1
}

tunnel_stop() {
    if tunnel_up "$1"; then
        sudo wg-quick down "$WG_DIR/$1.conf"
    else
        echo "tunnel $1: not up"
    fi
}

# --- /etc/hosts ---------------------------------------------------------------------------------
# Checked, never written: it is the operator's file. Production passes on public DNS too, so the day
# `publish_dns` flips this stops asking for a line that is no longer needed.

hosts_check() {
    local env="$1" host node
    host="$(host_of "$env")"
    node="$(node_of "$env")"
    if grep -qE "^[[:space:]]*${node}[[:space:]]+.*\b${host}\b" /etc/hosts; then
        echo "hosts  $env: $host -> $node"
    elif [ "$env" = production ] && [ -n "$(dig +short +time=2 "$host" A 2>/dev/null)" ]; then
        echo "hosts  $env: $host resolves publicly"
    else
        echo "hosts  $env: $host is not mapped — the browser needs this line, once:" >&2
        echo "         sudo sh -c 'echo \"$node  $host\" >> /etc/hosts'" >&2
    fi
}

# --- forwards -----------------------------------------------------------------------------------

pidfile() { echo "$STATE_DIR/$1-$2.pid"; }
logfile() { echo "$STATE_DIR/$1-$2.log"; }

forward_pid() {
    local f
    f="$(pidfile "$1" "$2")"
    [ -f "$f" ] || return 1
    local pid
    pid="$(cat "$f")"
    kill -0 "$pid" 2>/dev/null && echo "$pid"
}

port_answers() { curl -s -o /dev/null --max-time 2 "http://localhost:$1/" 2>/dev/null; }

# An SSH listener accepts before the far end does, so a bare port check passes on a dead forward;
# pg_isready asks PostgreSQL itself. nc is the fallback without a PostgreSQL client.
database_answers() {
    if command -v pg_isready >/dev/null; then
        pg_isready -q -h 127.0.0.1 -p "$1" -t 3
    else
        nc -z -w 2 127.0.0.1 "$1"
    fi
}

# forward_answers <name> <port>
forward_answers() {
    if [ "$1" = database ]; then database_answers "$2"; else port_answers "$2"; fi
}

# ExitOnForwardFailure makes a taken port an exit rather than a live ssh forwarding nothing, and
# BatchMode a missing key an error rather than a prompt nobody sees. The key is CLUSTER_ACCESS.md's.
ssh_forward() {
    local port="$1" node="$2" target="$3" key="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
    local -a id=()
    [ -f "$key" ] && id=(-i "$key")
    exec ssh -N ${id[@]+"${id[@]}"} -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=30 \
        -L "$port:$target" "ops@$node"
}

# A sleep a signal can cut short: bash runs a trap only after a foreground command returns, so a
# plain `sleep 60` would keep `down` waiting, and the forward listening, for up to a minute.
snooze() {
    sleep "$1" &
    wait $! || true
}

# A kubectl forward attaches to one pod, even through `svc/`. When a rollout replaces that pod the
# process stays alive and listening, and forwards nothing (#2976). This loop owns the kubectl: it
# probes the port every 10 s and starts a new one, which resolves the new pod, when the probe
# fails. Until a pod is Ready it backs off from 5 s to a minute. Killing the loop stops its kubectl.
forward_supervise() {
    local env="$1" name="$2" port="$3" ns="$4" svc="$5" remote="$6" log child=0 backoff=5 up
    log="$(logfile "$env" "$name")"
    trap '[ "$child" -gt 0 ] && kill "$child" 2>/dev/null; exit 0' INT TERM
    trap '[ "$child" -gt 0 ] && kill "$child" 2>/dev/null; true' EXIT
    while :; do
        kubectl --context "$(context_of "$env")" -n "$ns" port-forward "svc/$svc" "$port:$remote" >>"$log" 2>&1 &
        child=$!
        up=0
        for _ in $(seq 1 10); do
            if forward_answers "$name" "$port"; then
                up=1
                break
            fi
            kill -0 "$child" 2>/dev/null || break
            snooze 1
        done
        if [ "$up" -eq 1 ]; then
            backoff=5
            while snooze 10 && kill -0 "$child" 2>/dev/null && forward_answers "$name" "$port"; do :; done
        fi
        echo "restarting: $port did not answer, $(date -u +%FT%TZ)" >>"$log"
        kill "$child" 2>/dev/null || true
        wait "$child" 2>/dev/null || true
        child=0
        if [ "$up" -eq 0 ]; then
            snooze "$backoff"
            backoff=$((backoff * 2 > 60 ? 60 : backoff * 2))
        fi
    done
}

forward_restarts() { grep -c '^restarting:' "$(logfile "$1" "$2")" 2>/dev/null || true; }

forward_start() {
    local env="$1" name="$2" spec port ns svc remote pid
    spec="$(forward_spec "$env" "$name")"
    read -r port ns svc remote <<<"$spec"
    if pid="$(forward_pid "$env" "$name")"; then
        echo "forward $env/$name: already up on $port (pid $pid)"
        return 0
    fi
    # A k9s shell or a hand-typed `port-forward` may hold the port; binding would fail while the port
    # answers, which reads as success. Say whose it is and leave it alone.
    if forward_answers "$name" "$port"; then
        echo "forward $env/$name: $port is already served by something this script did not start — using it; 'down' will not stop it"
        echo "forward $env/$name: $(url_of "$env" "$name")"
        return 0
    fi
    mkdir -p "$STATE_DIR"
    if [ "$ns" = ssh ]; then
        (ssh_forward "$port" "$svc" "$remote") >"$(logfile "$env" "$name")" 2>&1 </dev/null &
    else
        : >"$(logfile "$env" "$name")"
        (forward_supervise "$env" "$name" "$port" "$ns" "$svc" "$remote") >/dev/null 2>&1 </dev/null &
    fi
    pid=$!
    echo "$pid" >"$(pidfile "$env" "$name")"
    for _ in $(seq 1 10); do
        if forward_answers "$name" "$port"; then
            echo "forward $env/$name: $(url_of "$env" "$name")"
            return 0
        fi
        kill -0 "$pid" 2>/dev/null || break
        sleep 1
    done
    echo "forward $env/$name: did not answer on $port — $(logfile "$env" "$name") says:" >&2
    tail -3 "$(logfile "$env" "$name")" >&2 || true
    [ "$ns" = ssh ] || echo "forward $env/$name: retrying in the background until a pod answers; 'down $env' stops it" >&2
    return 1
}

forward_stop() {
    local env="$1" name="$2" pid port ns listener
    if pid="$(forward_pid "$env" "$name")"; then
        kill "$pid" 2>/dev/null || true
        echo "forward $env/$name: stopped (pid $pid)"
    fi
    rm -f "$(pidfile "$env" "$name")"
    read -r port ns _ <<<"$(forward_spec "$env" "$name")"
    [ "$ns" = ssh ] && return 0
    # The loop's trap stops its kubectl. One that outlived a `kill -9` of the loop still holds the
    # port; stop it only when it is this env's port-forward, never a forward started by hand elsewhere.
    sleep 1
    for listener in $(lsof -nP -t -iTCP:"$port" -sTCP:LISTEN 2>/dev/null); do
        if ps -o args= -p "$listener" | grep -q -- "--context $(context_of "$env") .*port-forward .* $port:"; then
            kill "$listener" 2>/dev/null || true
        fi
    done
}

# --- versions ------------------------------------------------------------------------------------
# Two numbers per cluster, and their disagreement is the finding: `running` is history[0] on the
# HelmRelease (needs the tunnel), `resolvable` is what Flux would select from the registry right now
# (scripts/deployed-versions.sh, no cluster). Different means a publish will move it on the next
# reconcile, or the range is holding it back on purpose.

running_version() {
    local env="$1"
    tunnel_up "$env" || {
        echo "(tunnel down)"
        return 0
    }
    kubectl --context "$(context_of "$env")" --request-timeout=10s -n flux-system \
        get helmrelease event-junkie -o jsonpath='{.status.history[0].chartVersion}' 2>/dev/null |
        sed 's/+.*//' | grep . || echo "(unreachable)"
}

resolvable_versions() {
    "$REPO_ROOT/scripts/deployed-versions.sh" 2>/dev/null || true
}

# A release is a tag; a snapshot is a commit. `0.4.1-snapshot.20260906083627.gcd8eba5` carries the sha.
link_for() {
    case "$1" in
        *-snapshot.*) echo "$COMMITS/${1##*.g}" ;;
        [0-9]*) echo "$RELEASES/v$1" ;;
        *) echo "" ;;
    esac
}

# --- status.js ----------------------------------------------------------------------------------
# One JSON document assembled by python3 from environment variables, so nothing here escapes
# anything. The page computes nothing beyond "is this port answering right now".

write_status_js() {
    local env name spec port up managed resolvable
    resolvable="$(resolvable_versions)"
    local -a entries=()
    for env in "${ENVS[@]}"; do
        local tunnel=false node=false
        tunnel_up "$env" && tunnel=true
        node_answers "$env" && node=true
        local forwards=""
        for name in "${FORWARDS[@]}"; do
            spec="$(forward_spec "$env" "$name")"
            port="${spec%% *}"
            up=false
            managed=false
            forward_answers "$name" "$port" && up=true
            forward_pid "$env" "$name" >/dev/null && managed=true
            forwards+="$name $port $up $managed $(url_of "$env" "$name")"$'\n'
        done
        local running
        running="$(running_version "$env")"
        local would
        would="$(printf '%s\n' "$resolvable" | awk -v c="$env" '$1 == c { print $2 }')"
        entries+=("$env"$'\t'"$tunnel"$'\t'"$node"$'\t'"$running"$'\t'"$(link_for "$running")"$'\t'"$would"$'\t'"$(link_for "$would")"$'\t'"$forwards")
    done
    mkdir -p "$(dirname "$STATUS_JS")"
    printf '%s\x1e' "${entries[@]}" | EJ_GENERATED="$(date -u +%Y-%m-%dT%H:%M:%SZ)" python3 -c '
import json, os, sys
envs = {}
for rec in sys.stdin.read().split("\x1e"):
    if not rec.strip():
        continue
    env, tunnel, node, running, running_link, would, would_link, fwds = rec.split("\t")
    forwards = {}
    for line in fwds.strip().splitlines():
        name, port, up, managed, url = line.split(" ")
        forwards[name] = {"port": int(port), "up": up == "true", "managed": managed == "true", "url": url}
    envs[env] = {
        "tunnel": tunnel == "true", "nodeAnswers": node == "true",
        "running": {"version": running, "link": running_link},
        "resolvable": {"version": would, "link": would_link},
        "forwards": forwards,
    }
doc = {"generatedAt": os.environ["EJ_GENERATED"], "environments": envs}
print("window.EJ_STATUS = " + json.dumps(doc, indent=2) + ";")
' >"$STATUS_JS"
}

# --- verbs --------------------------------------------------------------------------------------

cmd_up() {
    local env="${1:-}"
    check_env "$env"
    tunnel_start "$env"
    hosts_check "$env" || true
    local name failed=0
    for name in "${FORWARDS[@]}"; do
        forward_start "$env" "$name" || failed=1
    done
    write_status_js
    [ "$failed" -eq 0 ] || return 1
    echo
    echo "the page:       open docs/ops/dashboard/index.html"
    echo "down again with: scripts/ej.sh down $env"
}

cmd_down() {
    local keep=false env envs=()
    local arg
    for arg in "$@"; do
        case "$arg" in
            --keep-tunnel) keep=true ;;
            staging | production) envs+=("$arg") ;;
            *) die "unknown argument '$arg'" ;;
        esac
    done
    [ "${#envs[@]}" -gt 0 ] || envs=("${ENVS[@]}")
    local name
    for env in "${envs[@]}"; do
        for name in "${FORWARDS[@]}"; do
            forward_stop "$env" "$name"
        done
        $keep || tunnel_stop "$env"
    done
    write_status_js
}

cmd_urls() {
    local env name envs=("${ENVS[@]}")
    [ -n "${1:-}" ] && {
        check_env "$1"
        envs=("$1")
    }
    for env in "${envs[@]}"; do
        for name in "${FORWARDS[@]}"; do
            printf '%-11s %-12s %s\n' "$env" "$name" "$(url_of "$env" "$name")"
        done
    done
}

# Reports "not ready" rather than "all good": a listing empty when healthy cannot be mistaken for a
# stale success. The Flux objects are parsed, not `flux get all`'s table. Unknown is reported beside
# False — `--status-selector ready=false` omits it, and a stuck install reports it. Suspended
# resources and those with no Ready condition yet are skipped.

cmd_status() {
    local env name spec port ns pid restarts note
    for env in "${ENVS[@]}"; do
        if tunnel_up "$env"; then
            if node_answers "$env"; then echo "tunnel  $env: up"; else echo "tunnel  $env: interface up, $(node_of "$env") does not answer"; fi
        else
            echo "tunnel  $env: DOWN"
        fi
    done
    for env in "${ENVS[@]}"; do
        for name in "${FORWARDS[@]}"; do
            spec="$(forward_spec "$env" "$name")"
            read -r port ns _ <<<"$spec"
            if pid="$(forward_pid "$env" "$name")"; then
                restarts="$(forward_restarts "$env" "$name")"
                note=""
                [ "${restarts:-0}" -gt 0 ] && note=" (restarted ${restarts}×)"
                if forward_answers "$name" "$port"; then
                    echo "forward $env/$name: up on $port$note"
                else
                    [ "$ns" = ssh ] || note=" (restarting)"
                    echo "forward $env/$name: pid $pid alive, $port does not answer$note"
                fi
            elif forward_answers "$name" "$port"; then
                echo "forward $env/$name: $port answers, not started by this script"
            fi
        done
    done
    for env in "${ENVS[@]}"; do
        printf 'cluster %-11s ' "$env"
        kubectl --context "$(context_of "$env")" --request-timeout=10s get nodes --no-headers 2>/dev/null |
            awk '{print $1, $2}' | grep . || echo "unreachable"
    done
    echo "not ready:"
    local out any=0
    for env in "${ENVS[@]}"; do
        out=$(kubectl --context "$(context_of "$env")" --request-timeout=15s get \
            gitrepositories,ocirepositories,helmrepositories,helmcharts,helmreleases,kustomizations,alerts,providers \
            -A --no-headers \
            -o 'custom-columns=KIND:.kind,NAME:.metadata.name,SUSPEND:.spec.suspend,READY:.status.conditions[?(@.type=="Ready")].status' \
            2>/dev/null | awk -v ctx="$env" '$3 != "true" && ($4 == "False" || $4 == "Unknown") { printf "  %s: %s/%s (%s)\n", ctx, $1, $2, $4 }')
        if [ -n "$out" ]; then
            any=1
            printf '%s\n' "$out"
        fi
    done
    [ "$any" -eq 0 ] && echo "  (nothing)"
    write_status_js
}

cmd_versions() {
    local env running would resolvable
    resolvable="$(resolvable_versions)"
    printf '%-11s %-42s %-42s\n' cluster running resolvable
    for env in "${ENVS[@]}"; do
        running="$(running_version "$env")"
        would="$(printf '%s\n' "$resolvable" | awk -v c="$env" '$1 == c { print $2 }')"
        printf '%-11s %-42s %-42s\n' "$env" "$running" "${would:-(none)}"
        [ -n "$(link_for "$running")" ] && printf '%-11s   %s\n' "" "$(link_for "$running")"
        [ -n "$would" ] && [ "$would" != "$running" ] && [ -n "$(link_for "$would")" ] && printf '%-11s   %s\n' "" "$(link_for "$would")"
    done
    write_status_js
}

case "${1:-}" in
    up)
        shift
        cmd_up "$@"
        ;;
    down)
        shift
        cmd_down "$@"
        ;;
    status)
        shift
        cmd_status "$@"
        ;;
    versions)
        shift
        cmd_versions "$@"
        ;;
    urls)
        shift
        cmd_urls "$@"
        ;;
    "") awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0" ;;
    *) die "unknown command '$1' — run 'scripts/ej.sh --help'" ;;
esac
