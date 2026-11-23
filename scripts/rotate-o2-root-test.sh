#!/usr/bin/env bash
#
# rotate-o2-root-test.sh — assert what `rotate-o2-root.sh` changes and refuses, against a fabricated
# Keychain, cluster and OpenObserve.
#
# The fake `curl` keeps the root password in a file and answers like OpenObserve: 200 or 401 from the
# Authorization header it is given, and the PUT changes the stored password. Fakes for `security` and
# `kubectl` keep a Keychain and the collector pods. `cluster-secrets.sh`, `o2-query.sh` and
# `deploy/alerts/apply.sh` are replaced by recorders. No password may reach the output or any fake's argument log.
#
# Usage: scripts/rotate-o2-root-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/rotate-o2-root.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BIN="$WORK/bin"
export FAKE="$WORK/state"
mkdir -p "$BIN" "$FAKE/kc"
export PATH="$BIN:$PATH" EJ_SETTLE_SECONDS=0 EJ_POLL_SECONDS=0
export EJ_CLUSTER_SECRETS="$BIN/cluster-secrets" EJ_O2_QUERY="$BIN/o2-query" EJ_ALERTS_APPLY="$BIN/alerts-apply"
ITEM=event-junkie-staging-o2-root-password
# Quotes and a dollar sign, so a password pasted into a config or a command line would break it.
# shellcheck disable=SC2016
OLD='zq9x-old "pass" $word'

cat >"$BIN/security" <<'EOF'
#!/usr/bin/env bash
cmd="$1"
shift
while [ $# -gt 0 ]; do
    case "$1" in
        -s) item="$2"; shift ;;
        -w) if [ "$cmd" = add-generic-password ]; then value="$2"; shift; else print=1; fi ;;
        -a) shift ;;
        -U) update=1 ;;
    esac
    shift
done
echo "$cmd $item" >>"$FAKE/security.log"
case "$cmd" in
    find-generic-password)
        [ -f "$FAKE/kc/$item" ] || exit 44
        [ -z "${print:-}" ] || { cat "$FAKE/kc/$item"; echo; }
        ;;
    add-generic-password)
        [ -n "${update:-}" ] || [ ! -f "$FAKE/kc/$item" ] || exit 45
        printf '%s' "$value" >"$FAKE/kc/$item"
        ;;
esac
EOF

# OpenObserve: 200 for a header carrying a valid password, 401 otherwise. FAKE_PUT=<code> answers the
# PUT with that code and changes nothing; FAKE_KEEP_OLD=1 lets the old password keep working.
cat >"$BIN/curl" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/curl.log"
fmt="" auth="" method=GET url="" data=""
while [ $# -gt 0 ]; do
    case "$1" in
        -w) fmt="$2"; shift ;;
        -H) case "$2" in @*) auth="$(sed -n 's/^Authorization: //p' "${2#@}")" ;; esac; shift ;;
        -X) method="$2"; shift ;;
        --data) data="$(cat)"; shift ;;
        -o | --max-time) shift ;;
        -s) ;;
        *) url="$1" ;;
    esac
    shift
done
valid() {
    local f
    for f in "$FAKE"/o2-password*; do
        [ "$1" = "Basic $(printf '%s:%s' hello@event-junkie.de "$(cat "$f")" | base64 | tr -d '\n')" ] && return 0
    done
    return 1
}
case "$url" in
    */healthz) code=200 ;;
    */api/default/streams) if valid "$auth"; then code=200; else code=401; fi ;;
    */api/default/users/*)
        if [ -n "${FAKE_PUT:-}" ]; then
            code="$FAKE_PUT"
        elif [ "$method" = PUT ] && valid "$auth" && [ "$(jq -r .change_password <<<"$data")" = true ]; then
            [ -z "${FAKE_KEEP_OLD:-}" ] || cp "$FAKE/o2-password" "$FAKE/o2-password-kept"
            jq -j .new_password <<<"$data" >"$FAKE/o2-password"
            code=200
        else
            code=401
        fi
        ;;
    *) code=404 ;;
esac
printf '%s' "${fmt//%\{http_code\}/$code}"
EOF

# Collector pods carry a uid that a delete replaces; the flux-system header follows the fake cluster-secrets.
cat >"$BIN/kubectl" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/kubectl.log"
args=" $* "
case "$args" in
    *" get pods "*"jsonpath="*"uid"*)
        ns="$(sed -E 's/.* -n ([^ ]+) .*/\1/' <<<"$args")"
        n="$(cat "$FAKE/gen-$ns" 2>/dev/null || echo 0)"
        if [[ "$args" == *ready* ]]; then printf '%s-%s true\n' "$ns" "$n"; else printf '%s-%s\n' "$ns" "$n"; fi
        ;;
    *" get pods "*"-o name"*) echo "pod/collector" ;;
    *" delete pod "*)
        ns="$(sed -E 's/.* -n ([^ ]+) .*/\1/' <<<"$args")"
        echo $(($(cat "$FAKE/gen-$ns" 2>/dev/null || echo 0) + 1)) >"$FAKE/gen-$ns"
        ;;
    *" get secret openobserve-credentials "*) base64 <"$FAKE/cluster-header" ;;
    *" logs "*) [ -z "${FAKE_REFUSED:-}" ] || echo "Exporting failed: 401 Unauthorized" ;;
esac
exit 0
EOF

cat >"$BIN/cluster-secrets" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/cluster-secrets.log"
[ -z "${FAKE_CS_FAIL:-}" ] || exit 1
pw="$(cat "$FAKE/kc/event-junkie-$1-o2-root-password")"
printf 'Basic %s' "$(printf '%s:%s' hello@event-junkie.de "$pw" | base64 | tr -d '\n')" >"$FAKE/cluster-header"
[ "${2:-}" != --check ] || echo "cluster-secrets: every key matches"
EOF

cat >"$BIN/o2-query" <<'EOF'
#!/usr/bin/env bash
if [ -n "${FAKE_ROWS:-}" ]; then echo "$FAKE_ROWS"; else echo '[{"n":7}]'; fi
EOF
# FAKE_DRIFT=1 leaves record-only on the old password, as before #5425. Another rule's drift must not matter.
cat >"$BIN/alerts-apply" <<'EOF'
#!/usr/bin/env bash
echo "$EJ_NODE${*:+ $*}" >>"$FAKE/alerts.log"
[ "${1:-}" = --diff ] || exit 0
if [ -n "${FAKE_DRIFT:-}" ]; then
    printf '%-30s DIFFERS     1 field\n    headers.Authorization\n' "record-only (destination)"
else
    printf '%-30s in sync\n' "record-only (destination)"
fi
printf '%-30s DIFFERS     1 field\n' ej-site-down
exit 1
EOF
chmod +x "$BIN"/*

FAILURES=0
fail() {
    echo "FAIL $*" >&2
    FAILURES=$((FAILURES + 1))
}

# reset: OpenObserve and the Keychain agree on $OLD; no logs, no pods replaced.
reset() {
    rm -rf "${FAKE:?}"/*
    mkdir -p "$FAKE/kc"
    printf '%s' "$OLD" >"$FAKE/kc/$ITEM"
    printf '%s' "$OLD" >"$FAKE/o2-password"
}
kc() { cat "$FAKE/kc/$1"; }
log_has() { [ -f "$FAKE/$1.log" ] && grep -q -- "$2" "$FAKE/$1.log"; }

# run <name> <expected exit> <args…>: keeps the output in $OUT, and fails on a password in any log.
run() {
    local name="$1" want="$2" got=0 pw f
    shift 2
    OUT="$("$SCRIPT" "$@" 2>&1)" || got=$?
    [ "$got" -eq "$want" ] || fail "$name: exit $got, expected $want. Output: $OUT"
    for pw in "$OLD" "$(cat "$FAKE/o2-password")"; do
        grep -qF -- "$pw" <<<"$OUT" && fail "$name: a password reached the output"
        for f in "$FAKE"/curl.log "$FAKE"/kubectl.log "$FAKE"/security.log; do
            [ -f "$f" ] && grep -qF -- "$pw" "$f" && fail "$name: a password reached an argument in $(basename "$f")"
        done
    done
    return 0
}
expect_out() { grep -qF -- "$2" <<<"$OUT" || fail "$1: no '$2' in: $OUT"; }

# 1. A Keychain password OpenObserve refuses: the other cluster on the port, or a stale item. Nothing changes.
reset
printf 'zq9x-other-cluster' >"$FAKE/o2-password"
run "refused" 1 staging
expect_out "refused" "answers 401"
log_has curl "-X PUT" && fail "refused: sent the PUT"
[ "$(kc $ITEM)" = "$OLD" ] || fail "refused: the Keychain changed"

# 2. --dry-run: the checks, and no write anywhere.
reset
run "dry run" 0 staging --dry-run
expect_out "dry run" "dry run, nothing changed"
log_has curl "-X PUT" && fail "dry run: sent the PUT"
log_has security add-generic-password && fail "dry run: wrote the Keychain"
[ -f "$FAKE/kubectl.log" ] && fail "dry run: called kubectl: $(cat "$FAKE/kubectl.log")"

# 3. The change is refused and nothing changes: stop, Keychain untouched, old password named valid.
reset
FAKE_PUT=400 run "put refused" 1 staging
expect_out "put refused" "the old password is still the valid one"
[ "$(kc $ITEM)" = "$OLD" ] || fail "put refused: the Keychain changed"
log_has cluster-secrets staging && fail "put refused: ran cluster-secrets"

# 4. Both passwords work after the change: stop, Keychain untouched, the new one kept aside.
reset
FAKE_KEEP_OLD=1 run "both valid" 1 staging
expect_out "both valid" "the new password is in $ITEM-new"
[ "$(kc $ITEM)" = "$OLD" ] || fail "both valid: the Keychain item changed"
[ "$(kc "$ITEM-new")" = "$(cat "$FAKE/o2-password")" ] || fail "both valid: $ITEM-new does not hold the new password"

# 5. A full rotation.
reset
run "rotate" 0 staging
expect_out "rotate" "rotated on event-junkie-staging"
new="$(kc $ITEM)"
[ "$new" != "$OLD" ] || fail "rotate: the Keychain still holds the old password"
[ "$new" = "$(cat "$FAKE/o2-password")" ] || fail "rotate: the Keychain and OpenObserve disagree"
[[ "$new" =~ [a-z] && "$new" =~ [A-Z] && "$new" =~ [0-9] && "$new" =~ [^A-Za-z0-9] ]] ||
    fail "rotate: the password lacks one of the four character classes"
[ "$(cat "$FAKE/cluster-secrets.log")" = "staging
staging --check" ] || fail "rotate: cluster-secrets ran as: $(cat "$FAKE/cluster-secrets.log")"
[ "$(grep -c 'rollout restart statefulset/openobserve-openobserve-standalone' "$FAKE/kubectl.log")" = 1 ] ||
    fail "rotate: not one OpenObserve restart"
[ "$(grep -c 'delete pod' "$FAKE/kubectl.log")" = 2 ] || fail "rotate: not two collector deletes"
grep -q -- '--context event-junkie-staging ' "$FAKE/kubectl.log" || fail "rotate: wrong context"
expect_out "rotate" "7 row(s) since the restart"
[ "$(cat "$FAKE/alerts.log")" = "ops@10.10.1.1
ops@10.10.1.1 --diff" ] || fail "rotate: apply.sh ran as: $(cat "$FAKE/alerts.log")"
expect_out "rotate" "record-only is in sync"

# 6. A stop after the Keychain write names the commands that finish it.
reset
FAKE_CS_FAIL=1 run "cluster-secrets fails" 1 staging
expect_out "cluster-secrets fails" "OpenObserve and the Keychain hold the new password"
expect_out "cluster-secrets fails" "scripts/cluster-secrets.sh staging && scripts/cluster-secrets.sh staging --check"
expect_out "cluster-secrets fails" "EJ_NODE=ops@10.10.1.1 deploy/alerts/apply.sh && EJ_NODE=ops@10.10.1.1 deploy/alerts/apply.sh --diff"
[ "$(kc $ITEM)" = "$(cat "$FAKE/o2-password")" ] || fail "cluster-secrets fails: the Keychain lost the new password"

# 7. The checks after the restart: Unauthorized lines, and no rows.
reset
FAKE_REFUSED=1 run "refused after" 1 staging
expect_out "refused after" "Unauthorized line(s)"
reset
FAKE_ROWS='[{"n":0}]' run "no rows" 1 staging
expect_out "no rows" "no rows in"
reset
FAKE_ROWS='refused' run "query refused" 1 staging
expect_out "query refused" "gave no row count"
expect_out "query refused" "EJ_NODE=ops@10.10.1.1 deploy/alerts/apply.sh --diff"

# 8. apply.sh leaves the alert destination on the old password: stop, and name the commands.
reset
FAKE_DRIFT=1 run "destination drifted" 1 staging
expect_out "destination drifted" "does not report record-only in sync on ops@10.10.1.1"
expect_out "destination drifted" "EJ_NODE=ops@10.10.1.1 deploy/alerts/apply.sh --diff"

# 9. Arguments.
run "no env" 1
run "bad flag" 1 staging --force

if [ "$FAILURES" -gt 0 ]; then
    echo "rotate-o2-root-test: $FAILURES failure(s)" >&2
    exit 1
fi
echo "rotate-o2-root-test: all cases pass"
