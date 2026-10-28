#!/usr/bin/env bash
#
# apply-test.sh — assert what `apply.sh` refuses, asks and applies, against a fabricated `tofu`.
#
# Fakes `tofu` and `direnv` on PATH and stands in for check-capacity.sh. The fake `tofu plan` writes
# a canned `show -json` into the plan file, `show` prints it back, and `apply` only records that it
# ran, with its arguments. `jq` is the real one, because reading the plan is what the script exists for.
#
# Usage: infra/apply-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/apply.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BIN="$WORK/bin"
STATE="$WORK/state"
mkdir -p "$BIN" "$STATE"
export FAKE_STATE="$STATE" PATH="$BIN:$PATH" EJ_CHECK_CAPACITY="$BIN/check-capacity"

cat >"$BIN/direnv" <<'EOF'
#!/usr/bin/env bash
shift 2
exec "$@"
EOF
cat >"$BIN/tofu" <<'EOF'
#!/usr/bin/env bash
shift
case "$1" in
    plan)
        for a in "$@"; do case "$a" in -out=*) out="${a#-out=}" ;; esac; done
        echo "$out" >"$FAKE_STATE/planfile"
        cp "$FAKE_STATE/show.json" "$out"
        ;;
    show) cat "${!#}" ;;
    apply) echo "$*" >"$FAKE_STATE/applied" ;;
esac
EOF
cat >"$BIN/check-capacity" <<'EOF'
#!/usr/bin/env bash
echo "$*" >"$FAKE_STATE/probed"
exit "${FAKE_CAPACITY_RC:-0}"
EOF
chmod +x "$BIN"/*

FAILURES=0
fail() {
    echo "FAIL $*" >&2
    FAILURES=$((FAILURES + 1))
}

# change <type> <address> <actions as JSON>
change() { printf '{"mode":"managed","type":"%s","address":"%s","change":{"actions":%s}}' "$1" "$2" "$3"; }
plan_of() {
    local IFS=,
    printf '{"resource_changes":[%s],"output_changes":{}}' "$*" >"$STATE/show.json"
}

# run <name> <expected exit> <stdin> <args…>: runs apply.sh, keeps its output in $OUT.
run() {
    local name="$1" want="$2" input="$3" got=0
    shift 3
    rm -f "$STATE/applied" "$STATE/probed" "$STATE/planfile"
    OUT="$(printf '%s' "$input" | "$SCRIPT" "$@" 2>&1)" || got=$?
    [ "$got" -eq "$want" ] || fail "$name: exit $got, expected $want. Output: $OUT"
    if [ -s "$STATE/planfile" ] && [ -e "$(cat "$STATE/planfile")" ]; then
        fail "$name: the plan file is still there"
    fi
}
expect_out() { grep -qF -- "$2" <<<"$OUT" || fail "$1: no '$2' in: $OUT"; }
applied() { [ -f "$STATE/applied" ]; }

SERVER="$(change hcloud_server module.environment.hcloud_server.k3s '["delete","create"]')"
ATTACH="$(change hcloud_volume_attachment module.environment.hcloud_volume_attachment.postgres '["delete","create"]')"
FIREWALL="$(change hcloud_firewall module.environment.hcloud_firewall.k3s '["update"]')"
UNCHANGED="$(change hcloud_network module.environment.hcloud_network.main '["no-op"]')"

# 1. Nothing to do: no prompt, no apply.
plan_of "$UNCHANGED"
run "no changes" 0 "" staging
expect_out "no changes" "No changes in staging"
applied && fail "no changes: applied"

# 2. An in-place change, answered yes: counted, applied, and the -var reaches the apply.
plan_of "$FIREWALL" "$UNCHANGED"
run "update" 0 "yes" staging -var "admin_cidrs=[]"
expect_out "update" "0 to add, 1 to change, 0 to destroy"
applied || fail "update: not applied"
grep -qF -- '-var admin_cidrs=[]' "$STATE/applied" 2>/dev/null || fail "update: the apply did not get -var"
grep -qF -- 'tfplan' "$STATE/applied" 2>/dev/null || fail "update: the apply did not get the plan file"

# 3. Any answer but yes stops it.
run "answer no" 1 "y" staging
expect_out "answer no" "not applied"
applied && fail "answer no: applied"

# 4. A server replacement without --allow-replace is refused before the prompt.
plan_of "$SERVER" "$ATTACH" "$FIREWALL"
run "refused" 1 "yes" production
expect_out "refused" "1 to change"
expect_out "refused" "replace  module.environment.hcloud_server.k3s"
expect_out "refused" "refused: the plan replaces or destroys hcloud_server"
applied && fail "refused: applied"

# 5. With --allow-replace, the capacity probe runs for that environment first.
run "allowed" 0 "yes" production --allow-replace -replace=module.environment.hcloud_server.k3s
expect_out "allowed" "2 to add, 1 to change, 2 to destroy"
grep -qF -- '--probe production' "$STATE/probed" 2>/dev/null || fail "allowed: no capacity probe"
applied || fail "allowed: not applied"

# 6. A failed probe stops it.
FAKE_CAPACITY_RC=1 run "no capacity" 1 "yes" production --allow-replace
expect_out "no capacity" "cannot be placed now"
applied && fail "no capacity: applied"

# 7. A replacement of an unguarded type is listed, and not refused.
plan_of "$ATTACH"
run "unguarded" 0 "yes" staging
expect_out "unguarded" "replace  module.environment.hcloud_volume_attachment.postgres"
applied || fail "unguarded: not applied"
[ -f "$STATE/probed" ] && fail "unguarded: probed capacity"

# 8. The plan file is the script's own.
run "out flag" 1 "yes" staging -out=mine.tfplan
expect_out "out flag" "the plan file is this script's own"

if [ "$FAILURES" -gt 0 ]; then
    echo "apply-test: $FAILURES failure(s)" >&2
    exit 1
fi
echo "apply-test: all cases pass"
