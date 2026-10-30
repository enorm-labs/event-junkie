#!/usr/bin/env bash
#
# ej-pid-test.sh — assert that ej.sh trusts a pid file only while the start time beside the pid matches.
#
# Sources ej.sh, points its state dir at a temp dir, and replaces `ps`, `kill` and the cluster calls
# with functions. A pid whose number macOS gave to another process read as a live forward, and
# `down` would have killed it (#4151).
#
# Usage: scripts/ej-pid-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORK="$(mktemp -d)"
STARTED_PID=""
cleanup() {
    [ -n "$STARTED_PID" ] && command kill "$STARTED_PID" 2>/dev/null
    rm -rf "$WORK"
}
trap cleanup EXIT

# shellcheck source=scripts/ej.sh
source "$REPO_ROOT/scripts/ej.sh"

# Never the operator's real state under .git/ej: its pid files name the forwards in use.
STATE_DIR="$WORK/ej"
LEGACY_STATE_DIRS=("$WORK/legacy")
mkdir -p "$STATE_DIR" "$WORK/legacy" "$WORK/ps"

# The process table: $WORK/ps/<pid> holds the start time `ps -o lstart=` prints for it, and
# $WORK/ps/any the start time of every other pid, once a test writes it.
ps() {
    local pid="" prev=""
    for arg in "$@"; do
        [ "$prev" = -p ] && pid="$arg"
        prev="$arg"
    done
    if [ -f "$WORK/ps/$pid" ]; then
        cat "$WORK/ps/$pid"
    elif [ -f "$WORK/ps/any" ]; then
        cat "$WORK/ps/any"
    else
        return 1
    fi
}
kill() {
    [ "$1" = -0 ] && return 0
    echo "$*" >>"$WORK/killed"
}
kubectl() { return 1; }
lsof() { :; }
sleep() { command sleep 0.1; }
tunnel_up() { return 1; }
node_answers() { return 1; }
write_status_js() { :; }
ANSWERS=1
forward_answers() { return "$ANSWERS"; }

PIDFILE="$STATE_DIR/staging-importer.pid"
NOW_8513="Wed Oct 28 09:04:12 2026"

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
absent() { [ ! -f "$1" ]; }
check() {
    local what="$1"
    shift
    if "$@"; then
        echo "ok   $what"
    else
        echo "FAIL $what" >&2
        failures=$((failures + 1))
    fi
}
# run <cmd…> — OUTPUT is what it printed, and what it killed is appended
run() {
    rm -f "$WORK/killed"
    # `|| true` would switch `set -e` off inside the subshell too, and hide a failing `ps` in ej.sh.
    set +e
    (
        set -e
        "$@"
    ) >"$WORK/out" 2>&1
    set -e
    OUTPUT="$(cat "$WORK/out")"
    [ -f "$WORK/killed" ] && OUTPUT+=$'\n'"killed: $(tr '\n' ' ' <"$WORK/killed")"
    return 0
}

# A matching pid is this script's forward.
echo "$NOW_8513" >"$WORK/ps/8513"
echo "8513 $NOW_8513" >"$PIDFILE"
ANSWERS=0
run cmd_status
expect "a matching pid is up" "forward staging/importer: up on 18081"
refuse "a matching pid is not stale" "stale pid file"
ANSWERS=1
run cmd_status
expect "a matching pid with a silent port is alive" "forward staging/importer: pid 8513 alive, 18081 does not answer"
run cmd_down staging
expect "down stops a matching pid" "forward staging/importer: stopped (pid 8513)"
expect "down kills a matching pid" "killed: 8513"
check "down removes a matching pid file" absent "$PIDFILE"

# The case the issue reports: the watchdog died, and its pid went to an IntelliJ process.
echo "8513 Tue Oct 27 22:10:03 2026" >"$PIDFILE"
run cmd_status
expect "status names a reused pid" "forward staging/importer: stale pid file (pid 8513 now runs something else), removed"
refuse "a reused pid is not alive" "pid 8513 alive"
check "status removes a reused pid file" absent "$PIDFILE"
echo "8513 Tue Oct 27 22:10:03 2026" >"$PIDFILE"
run cmd_down staging
refuse "down kills nothing for a reused pid" "killed:"
expect "down says it left a reused pid alone" "pid 8513 not stopped"
check "down removes a reused pid file" absent "$PIDFILE"
echo "8513 Tue Oct 27 22:10:03 2026" >"$PIDFILE"
ANSWERS=0
run forward_start staging importer
refuse "up does not call a reused pid already up" "already up"
ANSWERS=1

# A pid file in a legacy state dir is read the same way.
echo "8513 Tue Oct 27 22:10:03 2026" >"$WORK/legacy/staging-importer.pid"
run cmd_down staging
refuse "down kills nothing for a reused pid in a legacy dir" "killed:"
check "down removes a reused pid file in a legacy dir" absent "$WORK/legacy/staging-importer.pid"

# A pid that no process has any more.
echo "4242 Tue Oct 27 22:10:03 2026" >"$PIDFILE"
run cmd_status
expect "status names a gone pid" "forward staging/importer: stale pid file (pid 4242 is gone), removed"
check "status removes a gone pid file" absent "$PIDFILE"

# A pid file an older ej.sh wrote, with no start time: not trusted, but kept so its pid stays visible.
echo "8513" >"$PIDFILE"
run cmd_status
expect "status names a pid file without a start time" "pid file without a start time (pid 8513, from an older ej.sh), not trusted"
refuse "a pid without a start time is not alive" "pid 8513 alive"
check "status keeps a pid file without a start time" test -f "$PIDFILE"
run cmd_down staging
refuse "down kills nothing for a pid without a start time" "killed:"
check "down removes a pid file without a start time" absent "$PIDFILE"

# up writes the start time beside the pid. The watchdog is a real sleep this test started and stops.
forward_supervise() { exec sleep 30; }
echo "Fri Oct 30 10:00:00 2026" >"$WORK/ps/any"
forward_start staging importer >"$WORK/out" 2>&1 || true
STARTED_PID="$(pid_of "$PIDFILE")"
OUTPUT="$(cat "$PIDFILE")"
expect "up writes the start time beside the pid" "$STARTED_PID Fri Oct 30 10:00:00 2026"
check "up writes the pid of the watchdog it started" command kill -0 "${STARTED_PID:-none}"

if [ "$failures" -gt 0 ]; then
    echo "$failures assertion(s) failed" >&2
    exit 1
fi
echo "all assertions passed"
