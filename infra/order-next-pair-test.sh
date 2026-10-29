#!/usr/bin/env bash
#
# order-next-pair-test.sh — assert what `order-next-pair.sh` orders, keeps and stops on, against a fabricated
# advertisement, state and apply.
#
# Fakes `direnv`, check-capacity.sh, apply.sh and the state read. Each round reads the state once, and that
# read moves the advertisement to the next line of a queue: "cx23" means only cx23 is sold that round. The
# fake apply records the types it created into the state, so later rounds see them as held.
# EJ_MAX_ROUNDS ends a run that would wait forever, with exit 4.
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
export FAKE="$WORK" PATH="$BIN:$PATH" EJ_CHECK_CAPACITY="$BIN/capacity" EJ_APPLY="$BIN/apply" \
    EJ_NOTIFY="$BIN/notify" EJ_SHOW_STATE="$BIN/state" EJ_MAX_ROUNDS=8

cat >"$BIN/direnv" <<'EOF'
#!/usr/bin/env bash
shift 2
exec "$@"
EOF
cat >"$BIN/state" <<'EOF'
#!/usr/bin/env bash
head -n 1 "$FAKE/adv.q" >"$FAKE/adv.now"
tail -n +2 "$FAKE/adv.q" >"$FAKE/adv.rest" && mv "$FAKE/adv.rest" "$FAKE/adv.q"
res=""
for role in k3s postgres; do
    [ -s "$FAKE/held-$role" ] || continue
    res="$res${res:+,}{\"address\":\"module.environment.hcloud_server.${role}_next[0]\",\"values\":{\"server_type\":\"$(cat "$FAKE/held-$role")\"}}"
done
echo "{\"values\":{\"root_module\":{\"child_modules\":[{\"resources\":[$res]}]}}}"
EOF
cat >"$BIN/capacity" <<'EOF'
#!/usr/bin/env bash
grep -qw "${1%@*}" "$FAKE/adv.now"
EOF
# The next code from apply.q: 0 creates every requested server, p creates only the k3s one and fails,
# 1 fails, 3 needs a person.
cat >"$BIN/apply" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/apply.log"
code="$(head -n 1 "$FAKE/apply.q")"
tail -n +2 "$FAKE/apply.q" >"$FAKE/apply.rest" && mv "$FAKE/apply.rest" "$FAKE/apply.q"
k3s="$(grep -o 'k3s_server_type="[a-z0-9]*"' <<<"$*" | cut -d'"' -f2 || true)"
db="$(grep -o 'postgres_server_type="[a-z0-9]*"' <<<"$*" | cut -d'"' -f2 || true)"
case "${code:-0}" in
    0) [ -z "$k3s" ] || echo "$k3s" >"$FAKE/held-k3s"; [ -z "$db" ] || echo "$db" >"$FAKE/held-postgres" ;;
    p) [ -z "$k3s" ] || echo "$k3s" >"$FAKE/held-k3s"; exit 1 ;;
    *) exit "$code" ;;
esac
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

# run <name> <expected exit> <rounds, ';'-separated> <apply codes> <args…>
run() {
    local name="$1" want="$2" got=0
    tr ';' '\n' <<<"$3" >"$WORK/adv.q"
    tr ' ' '\n' <<<"$4" >"$WORK/apply.q"
    rm -f "$WORK"/*.log "$WORK"/held-*
    shift 4
    OUT="$("$SCRIPT" "$@" --interval 0 2>&1)" || got=$?
    [ "$got" -eq "$want" ] || fail "$name: exit $got, expected $want. Output: $OUT"
}
applies() { if [ -f "$WORK/apply.log" ]; then grep -c . "$WORK/apply.log"; else echo 0; fi; }
apply_n() { sed -n "${1}p" "$WORK/apply.log"; }
held() { cat "$WORK/held-$1" 2>/dev/null || true; }

# 1. cx23 is sold first, cx33 two rounds later: each is ordered alone, the second with the first held.
run "one at a time" 0 ";cx23;;cx33" "0 0" production
[ "$(applies)" = 2 ] || fail "one at a time: $(applies) applies, expected 2"
grep -qF 'next_pair={postgres_server_type="cx23"}' <<<"$(apply_n 1)" || fail "one at a time: first apply: $(apply_n 1)"
grep -qF 'next_pair={k3s_server_type="cx33",postgres_server_type="cx23"}' <<<"$(apply_n 2)" || fail "one at a time: second apply: $(apply_n 2)"
grep -qF -- '--yes-if-only-creates' <<<"$(apply_n 1)" || fail "one at a time: no creates-only guard"
grep -qF 'complete' "$WORK/notify.log" || fail "one at a time: no 'complete' notification"

# 2. Both sold in one round: one apply orders both.
run "both at once" 0 "cx33 cx23" "0" production
[ "$(applies)" = 1 ] || fail "both at once: $(applies) applies, expected 1"

# 3. A held cx33 waits for its cx23, even when cx43 is sold.
run "cx33 waits for cx23" 4 "cx33;cx43;cx43" "0" production
[ "$(applies)" = 1 ] || fail "cx33 waits for cx23: $(applies) applies, expected 1"
[ "$(held k3s)" = cx33 ] || fail "cx33 waits for cx23: k3s is $(held k3s)"

# 4. Before the fallback date, or with --no-fallback, a sold cx43 is not ordered.
run "fallback closed" 4 "cx43;cx43" "" production --fallback-after 2999-01-01
[ "$(applies)" = 0 ] || fail "fallback closed: ordered $(apply_n 1)"
run "no fallback" 4 "cx43;cx43" "" production --no-fallback
[ "$(applies)" = 0 ] || fail "no fallback: ordered $(apply_n 1)"
run "fallback date reached" 0 "cx43" "0" production --fallback-after 2000-01-01
[ "$(applies)" = 1 ] || fail "fallback date reached: $(applies) applies, expected 1"

# 5. The fallback alone carries PostgreSQL, and is complete on its own.
run "fallback alone" 0 "cx43" "0" production
grep -qF 'next_pair={k3s_server_type="cx43",colocated=true}' <<<"$(apply_n 1)" || fail "fallback alone: $(apply_n 1)"
grep -qF 'complete: k3s cx43' "$WORK/notify.log" || fail "fallback alone: not complete"

# 6. Beside a held cx23, the fallback is a k3s node only.
run "fallback beside cx23" 0 "cx23;cx43" "0 0" production
grep -qF 'next_pair={k3s_server_type="cx43",postgres_server_type="cx23"}' <<<"$(apply_n 2)" || fail "fallback beside cx23: $(apply_n 2)"

# 7. cx33 is preferred over the fallback in the same round.
run "cx33 over cx43" 0 "cx33 cx43 cx23" "0" production
grep -qF 'k3s_server_type="cx33"' <<<"$(apply_n 1)" || fail "cx33 over cx43: $(apply_n 1)"

# 8. A partly failed apply keeps what it created, and the next round orders only the rest.
run "partial" 0 "cx33 cx23;cx23" "p 0" production
[ "$(applies)" = 2 ] || fail "partial: $(applies) applies, expected 2"
[ "$(held postgres)" = cx23 ] && [ "$(held k3s)" = cx33 ] || fail "partial: held k3s $(held k3s), database $(held postgres)"

# 9. A plan that needs a person stops at once and says so.
run "needs a person" 3 "cx23;cx23" "3" production
[ "$(applies)" = 1 ] || fail "needs a person: applied again after exit 3"
grep -qF 'needs a person' "$WORK/notify.log" || fail "needs a person: no notification"

# 10. Staging asks for the k3s type only, and keeps PostgreSQL on it.
run "staging" 0 "cx33 cx23" "0" staging
grep -qF 'next_pair={k3s_server_type="cx33"}' <<<"$(apply_n 1)" || fail "staging: $(apply_n 1)"

if [ "$FAILURES" -gt 0 ]; then
    echo "order-next-pair-test: $FAILURES failure(s)" >&2
    exit 1
fi
echo "order-next-pair-test: all cases pass"
