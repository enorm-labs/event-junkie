#!/usr/bin/env bash
#
# apply-test.sh — assert what `apply.sh` refuses, asks and applies, against a fabricated `tofu`.
#
# Fakes `tofu` and `direnv` on PATH and stands in for check-capacity.sh. The fake `tofu plan` writes
# a canned `show -json` into the plan file, `show` prints it back, and `apply` only records that it
# ran, with its arguments. `jq` is the real one, because reading the plan is what the script exists for.
# EJ_STACK_DIR points the script at a fake stack, whose lock file and installed providers the cases set.
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
STACK_DIR="$WORK/stack"
PROVIDERS="$STACK_DIR/.terraform/providers/registry.opentofu.org"
mkdir -p "$BIN" "$STATE" "$STACK_DIR"
export FAKE_STATE="$STATE" PATH="$BIN:$PATH" EJ_CHECK_CAPACITY="$BIN/check-capacity" EJ_STACK_DIR="$STACK_DIR"
unset TF_DATA_DIR

cat >"$STACK_DIR/.terraform.lock.hcl" <<'EOF'
# This file is maintained automatically by "tofu init".

provider "registry.opentofu.org/aminueza/minio" {
  version     = "3.3.0"
  constraints = "~> 3.3"
  hashes = [
    "h1:abc=",
  ]
}

provider "registry.opentofu.org/hetznercloud/hcloud" {
  version     = "1.70.2"
  constraints = "~> 1.68"
}
EOF
install_providers() {
    rm -rf "$STACK_DIR/.terraform" "$STACK_DIR/data"
    mkdir -p "$PROVIDERS/aminueza/minio/3.3.0/test_os" "$PROVIDERS/hetznercloud/hcloud/1.70.2/test_os"
}
install_providers

cat >"$BIN/direnv" <<'EOF'
#!/usr/bin/env bash
shift 2
exec "$@"
EOF
cat >"$BIN/tofu" <<'EOF'
#!/usr/bin/env bash
case "$1" in -chdir=*) shift ;; esac
case "$1" in
    version) echo '{"platform":"test_os"}' ;;
    plan)
        touch "$FAKE_STATE/planned"
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
    rm -f "$STATE/applied" "$STATE/probed" "$STATE/planfile" "$STATE/planned"
    OUT="$(printf '%s' "$input" | "$SCRIPT" "$@" 2>&1)" || got=$?
    [ "$got" -eq "$want" ] || fail "$name: exit $got, expected $want. Output: $OUT"
    if [ -s "$STATE/planfile" ] && [ -e "$(cat "$STATE/planfile")" ]; then
        fail "$name: the plan file is still there"
    fi
}
expect_out() { grep -qF -- "$2" <<<"$OUT" || fail "$1: no '$2' in: $OUT"; }
applied() { [ -f "$STATE/applied" ]; }
planned() { [ -f "$STATE/planned" ]; }

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

# 7. A destroyed server needs --allow-replace, and places nothing, so no probe runs.
plan_of "$(change hcloud_server module.environment.hcloud_server.k3s_old '["delete"]')"
run "destroy refused" 1 "yes" production
expect_out "destroy refused" "destroy  module.environment.hcloud_server.k3s_old"
expect_out "destroy refused" "refused: the plan replaces or destroys hcloud_server"
applied && fail "destroy refused: applied"
FAKE_CAPACITY_RC=1 run "destroy allowed" 0 "yes" production --allow-replace
[ -f "$STATE/probed" ] && fail "destroy allowed: probed capacity"
applied || fail "destroy allowed: not applied"

# 8. A replacement of an unguarded type is listed, and not refused.
plan_of "$ATTACH"
run "unguarded" 0 "yes" staging
expect_out "unguarded" "replace  module.environment.hcloud_volume_attachment.postgres"
applied || fail "unguarded: not applied"
[ -f "$STATE/probed" ] && fail "unguarded: probed capacity"

# 9. The plan file is the script's own.
run "out flag" 1 "yes" staging -out=mine.tfplan
expect_out "out flag" "the plan file is this script's own"

# 10. Creates that all match --yes-if-only-creates apply with no answer on stdin.
NEXT_RE='_next(_ipv[46])?\[0\]$'
NEXT_SERVER="$(change hcloud_server 'module.environment.hcloud_server.k3s_next[0]' '["create"]')"
NEXT_IP="$(change hcloud_primary_ip 'module.environment.hcloud_primary_ip.k3s_next_ipv4[0]' '["create"]')"
plan_of "$NEXT_SERVER" "$NEXT_IP" "$UNCHANGED"
run "creates only" 0 "" production --yes-if-only-creates "$NEXT_RE"
expect_out "creates only" "so no prompt"
applied || fail "creates only: not applied"

# 11. An update beside those creates needs a person: exit 3, nothing applied.
plan_of "$NEXT_SERVER" "$FIREWALL"
run "creates and an update" 3 "yes" production --yes-if-only-creates "$NEXT_RE"
expect_out "creates and an update" "module.environment.hcloud_firewall.k3s"
applied && fail "creates and an update: applied"

# 12. A create the regex does not match needs a person too.
plan_of "$NEXT_SERVER" "$(change hcloud_server module.environment.hcloud_server.k3s '["create"]')"
run "unmatched create" 3 "yes" production --yes-if-only-creates "$NEXT_RE"
applied && fail "unmatched create: applied"

# 13. A pinned version that is not installed stops it before the plan and names `tofu init`, even
#     with an older version beside it: the shape of the hcloud 1.70.0 bump (#4166).
plan_of "$FIREWALL"
rm -rf "$PROVIDERS/hetznercloud/hcloud/1.70.2"
mkdir -p "$PROVIDERS/hetznercloud/hcloud/1.68.0/test_os"
run "provider missing" 1 "yes" staging
expect_out "provider missing" "staging has no hetznercloud/hcloud 1.70.2 installed for test_os"
expect_out "provider missing" "(cd infra/environments/staging && tofu init)"
planned && fail "provider missing: planned"
applied && fail "provider missing: applied"

# 14. Installed for another platform only is not installed.
install_providers
mv "$PROVIDERS/aminueza/minio/3.3.0/test_os" "$PROVIDERS/aminueza/minio/3.3.0/other_os"
run "other platform" 1 "yes" production
expect_out "other platform" "production has no aminueza/minio 3.3.0 installed for test_os"
planned && fail "other platform: planned"

# 15. No .terraform at all, on bootstrap: the hint names bootstrap's directory.
rm -rf "$STACK_DIR/.terraform"
run "never initialised" 1 "yes" bootstrap
expect_out "never initialised" "(cd infra/bootstrap && tofu init)"
planned && fail "never initialised: planned"

# 16. TF_DATA_DIR moves where the providers are looked for, relative to the stack.
install_providers
mv "$STACK_DIR/.terraform" "$STACK_DIR/data"
TF_DATA_DIR=data run "data dir" 0 "yes" staging
planned || fail "data dir: not planned"
applied || fail "data dir: not applied"

if [ "$FAILURES" -gt 0 ]; then
    echo "apply-test: $FAILURES failure(s)" >&2
    exit 1
fi
echo "apply-test: all cases pass"
