#!/usr/bin/env bash
#
# ej-status-test.sh — assert what `ej.sh status` lists under `not ready:`, against a fabricated cluster.
#
# Sources ej.sh and replaces `kubectl`, the tunnel checks and the status.js writer with functions, so
# nothing reaches a cluster, a tunnel or the dashboard file. The case #3762 exists for: Flux reports
# every object Ready while a pod crash-loops, and the listing said "(nothing)".
#
# Usage: scripts/ej-status-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# shellcheck source=scripts/ej.sh
source "$REPO_ROOT/scripts/ej.sh"

# Pod lists in `kubectl get pods -o json` shape, with exit times relative to now.
python3 - "$WORK" <<'PY'
import json, sys
from datetime import datetime, timedelta, timezone

def ago(minutes):
    return (datetime.now(timezone.utc) - timedelta(minutes=minutes)).strftime("%Y-%m-%dT%H:%M:%SZ")

def pod(ns, name, phase, containers, reason=None, init=()):
    status = {"phase": phase, "containerStatuses": list(containers), "initContainerStatuses": list(init)}
    if reason:
        status["reason"] = reason
    return {"metadata": {"namespace": ns, "name": name}, "status": status}

def running(restarts=0, exited_minutes=None, exit_reason="Error"):
    c = {"ready": True, "restartCount": restarts, "state": {"running": {}}}
    if exited_minutes is not None:
        c["lastState"] = {"terminated": {"reason": exit_reason, "finishedAt": ago(exited_minutes)}}
    return c

crashing = {
    "ready": False, "restartCount": 47,
    "state": {"waiting": {"reason": "CrashLoopBackOff"}},
    "lastState": {"terminated": {"reason": "Error", "finishedAt": ago(2)}},
}
healthy = [
    pod("event-junkie", "event-junkie-bff-7d9f", "Running", [running()]),
    pod("event-junkie", "event-junkie-importer-5c4b", "Running", [running(restarts=3, exited_minutes=180)]),
    pod("cnpg-system", "backup-job-x2k", "Succeeded", [{"ready": False, "restartCount": 0, "state": {"terminated": {"reason": "Completed"}}}]),
]
sick = healthy + [
    pod("observability", "openobserve-collector-gateway-0", "Running", [crashing]),
    pod("event-junkie", "event-junkie-bff-8a1c", "Running", [running(restarts=1, exited_minutes=20, exit_reason="OOMKilled")]),
    pod("event-junkie", "event-junkie-importer-new", "Running", [{"ready": False, "restartCount": 0, "state": {"running": {}}}]),
    pod("event-junkie", "evicted-1", "Failed", [], reason="Evicted"),
    pod("event-junkie", "pending-1", "Pending", [], init=[{"ready": False, "restartCount": 2, "state": {"waiting": {"reason": "CrashLoopBackOff"}}}]),
]
for name, items in (("healthy", healthy), ("sick", sick)):
    with open(f"{sys.argv[1]}/{name}.json", "w") as f:
        json.dump({"items": items}, f)
PY

# Each context reads its pods from $WORK/<PODS_STAGING or PODS_PRODUCTION>.json; `unreachable` fails
# like a closed tunnel.
kubectl() {
    local ctx="" arg pods
    for arg in "$@"; do
        case "$arg" in --context) ctx=next ;; *) [ "$ctx" = next ] && ctx="${arg#event-junkie-}" ;; esac
    done
    if [ "$ctx" = staging ]; then pods="$PODS_STAGING"; else pods="$PODS_PRODUCTION"; fi
    [ "$pods" = unreachable ] && return 1
    case " $* " in
        *" nodes "*) echo "node-1 Ready" ;;
        *" pods "*) cat "$WORK/$pods.json" ;;
        *helmreleases*) echo "HelmRelease openobserve-collector <none> True" ;;
    esac
}
tunnel_up() { return 0; }
node_answers() { return 0; }
forward_pid() { return 1; }
forward_answers() { return 1; }
write_status_js() { touch "$WORK/status-written"; }

failures=0
expect() {
    if grep -qF -- "$2" <<<"$OUTPUT"; then
        echo "ok   $1"
    else
        echo "FAIL $1: no line '$2' in:" >&2
        printf '%s\n' "$OUTPUT" >&2
        failures=$((failures + 1))
    fi
}
refuse() {
    if grep -qF -- "$2" <<<"$OUTPUT"; then
        echo "FAIL $1: unexpected '$2' in:" >&2
        printf '%s\n' "$OUTPUT" >&2
        failures=$((failures + 1))
    else
        echo "ok   $1"
    fi
}
run() {
    PODS_STAGING="$1" PODS_PRODUCTION="$2"
    rm -f "$WORK/status-written"
    # A subshell, not $(…): it keeps `set -e`, so a failing kubectl stops it as it stops ej.sh.
    (cmd_status) >"$WORK/out" 2>&1 || true
    OUTPUT="$(cat "$WORK/out")"
}

run healthy healthy
expect "a healthy cluster lists nothing" "  (nothing)"
refuse "a restart three hours ago is not listed" "importer-5c4b"
refuse "a Succeeded job pod is not listed" "backup-job"
if [ -f "$WORK/status-written" ]; then
    echo "ok   status.js is still written"
else
    echo "FAIL status.js was not written" >&2
    failures=$((failures + 1))
fi

run sick healthy
expect "a crash-looping pod is listed, though Flux is Ready" \
    "  staging: pod observability/openobserve-collector-gateway-0 (CrashLoopBackOff, 47 restarts)"
expect "an exit in the last hour is listed with its reason" "  staging: pod event-junkie/event-junkie-bff-8a1c (exited OOMKilled, 1 restarts)"
expect "a running pod with a container not ready is listed" "  staging: pod event-junkie/event-junkie-importer-new (not ready, 0 restarts)"
expect "a Failed pod is listed with the pod's reason" "  staging: pod event-junkie/evicted-1 (Evicted, 0 restarts)"
expect "an init container's waiting reason is the pod's" "  staging: pod event-junkie/pending-1 (CrashLoopBackOff, 2 restarts)"
refuse "the healthy cluster adds nothing" "production: pod"
refuse "a listing with findings does not also say nothing" "(nothing)"

run sick unreachable
expect "an unreachable cluster does not stop the listing" "  staging: pod observability/openobserve-collector-gateway-0"
expect "an unreachable cluster is named as such" "cluster production  unreachable"

if [ "$failures" -gt 0 ]; then
    echo "$failures assertion(s) failed" >&2
    exit 1
fi
echo "all assertions passed"
