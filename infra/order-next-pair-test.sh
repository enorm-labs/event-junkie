#!/usr/bin/env bash
#
# order-next-pair-test.sh — assert when `order-next-pair.sh` orders, retries and stops, against a fabricated
# advertisement and apply.
#
# Fakes `direnv`, check-capacity.sh and apply.sh. Each fake reads its next exit code from a queue file,
# so one run walks through "not advertised", "advertised" and a failed apply in order.
#
# Usage: infra/order-next-pair-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/order-next-pair.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BIN="$WORK/bin"
mkdir -p "$BIN"
export FAKE="$WORK" PATH="$BIN:$PATH" EJ_CHECK_CAPACITY="$BIN/capacity" EJ_APPLY="$BIN/apply" EJ_NOTIFY="$BIN/notify"

cat >"$BIN/direnv" <<'EOF'
#!/usr/bin/env bash
shift 2
exec "$@"
EOF
# next <queue>: print and remove the first exit code of the queue.
cat >"$BIN/next" <<'EOF'
#!/usr/bin/env bash
code="$(head -n 1 "$FAKE/$1")"
tail -n +2 "$FAKE/$1" >"$FAKE/$1.rest" && mv "$FAKE/$1.rest" "$FAKE/$1"
exit "${code:-9}"
EOF
cat >"$BIN/capacity" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/capacity.log"
next capacity.q
EOF
cat >"$BIN/apply" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/apply.log"
next apply.q
EOF
cat >"$BIN/notify" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/notify.log"
EOF
chmod +x "$BIN"/*

FAILURES=0
fail() {
    echo "FAIL $*" >&2
    FAILURES=$((FAILURES + 1))
}

# run <name> <expected exit> <capacity codes> <apply codes> <args…>
run() {
    local name="$1" want="$2" got=0
    tr " " "\n" <<<"$3" >"$WORK/capacity.q"
    tr " " "\n" <<<"$4" >"$WORK/apply.q"
    rm -f "$WORK"/*.log
    shift 4
    OUT="$("$SCRIPT" "$@" --interval 0 2>&1)" || got=$?
    [ "$got" -eq "$want" ] || fail "$name: exit $got, expected $want. Output: $OUT"
}
count() { [ -f "$WORK/$1" ] && grep -c . "$WORK/$1" || echo 0; }

# 1. Not advertised twice, then advertised: one apply, with both types and the creates-only guard.
run "waits then orders" 0 "1 1 0" "0" production
[ "$(count capacity.log)" = 3 ] || fail "waits then orders: $(count capacity.log) checks, expected 3"
[ "$(count apply.log)" = 1 ] || fail "waits then orders: $(count apply.log) applies, expected 1"
grep -qF 'cx33@nbg1,cx23@nbg1' "$WORK/capacity.log" || fail "waits then orders: wrong spec: $(cat "$WORK/capacity.log")"
grep -qF -- '--yes-if-only-creates' "$WORK/apply.log" || fail "waits then orders: no creates-only guard"
grep -qF 'next_pair={k3s_server_type="cx33",postgres_server_type="cx23"}' "$WORK/apply.log" || fail "waits then orders: wrong next_pair: $(cat "$WORK/apply.log")"
grep -qF 'ordered' "$WORK/notify.log" || fail "waits then orders: no notification"

# 2. A failed apply, usually resource_unavailable, is tried again on the next advertisement.
run "retries a failed apply" 0 "0 0" "1 0" production
[ "$(count apply.log)" = 2 ] || fail "retries a failed apply: $(count apply.log) applies, expected 2"

# 3. A plan that needs a person stops at once and says so.
run "needs a person" 3 "0 0" "3 0" production
[ "$(count apply.log)" = 1 ] || fail "needs a person: applied again after exit 3"
grep -qF 'needs a person' "$WORK/notify.log" || fail "needs a person: no notification"

# 4. A failing check-capacity.sh (an API error) is waited out, not taken as a yes.
run "API error" 0 "2 0" "0" production
[ "$(count apply.log)" = 1 ] || fail "API error: $(count apply.log) applies, expected 1"

# 5. Staging asks for the k3s type only.
run "staging" 0 "0" "0" staging
grep -qx 'cx33@nbg1' "$WORK/capacity.log" || fail "staging: wrong spec: $(cat "$WORK/capacity.log")"
grep -qF 'next_pair={k3s_server_type="cx33"}' "$WORK/apply.log" || fail "staging: wrong next_pair"

if [ "$FAILURES" -gt 0 ]; then
    echo "order-next-pair-test: $FAILURES failure(s)" >&2
    exit 1
fi
echo "order-next-pair-test: all cases pass"
