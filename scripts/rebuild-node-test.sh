#!/usr/bin/env bash
#
# rebuild-node-test.sh — assert the steps `rebuild-node.sh` runs, in which order, and what it keeps away from the
# running cluster, against fabricated nodes, clusters and GitHub.
#
# Fakes ssh, kubectl, flux, gh, security, sops, curl, sudo, wg-quick and the four scripts it calls. Every
# fake appends its arguments to one event log, so order is a line number. What a fake reads on stdin goes
# to a separate file: values travel there, and no value may reach an argument. Every Keychain value
# carries the marker zq9x.
#
# Usage: scripts/rebuild-node-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/rebuild-node.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BIN="$WORK/bin"
export FAKE="$WORK" HOME="$WORK/home"
mkdir -p "$BIN" "$HOME/.wireguard" "$WORK/run"
export PATH="$BIN:$PATH" EJ_WG_RUN_DIR="$WORK/run" EJ_WG_DIR="$HOME/.wireguard" EJ_BOOT_TRIES=2 EJ_BOOT_SLEEP=0 \
    EJ_CLUSTER_ACCESS="$BIN/cluster-access" EJ_CLUSTER_SECRETS="$BIN/cluster-secrets" \
    EJ_ALERTS_APPLY="$BIN/alerts-apply" EJ_DASHBOARDS_APPLY="$BIN/dashboards-apply" EJ_AGE_KEY="$WORK/age.txt"
printf '[Interface]\nPrivateKey = X=\n[Peer]\nPublicKey  = S=\nEndpoint   = 203.0.113.10:51820\n' >"$HOME/.wireguard/production.conf"
printf '[Interface]\nPrivateKey = X=\n[Peer]\nPublicKey  = S=\nEndpoint   = 203.0.113.30:51820\n' >"$HOME/.wireguard/staging.conf"

# record <tool> <args…>: one line in the event log; stdin, when there is any, into stdin.log.
cat >"$BIN/record" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/events.log"
EOF
cat >"$BIN/ssh" <<'EOF'
#!/usr/bin/env bash
record ssh "$@"
case "$*" in
    *"boot: every step succeeded"*) printf '%s\n' "${FAKE_BOOT:-boot: every step succeeded}" "harden: done" ;;
    *public.key*) echo "NEXTSERVERKEY=" ;;
    *"SELECT 1"*) cat >>"$FAKE/stdin.log"; [ -n "${FAKE_EVENTS_MISMATCH:-}" ] || echo 1 ;;
    *"walg check"*) [ -z "${FAKE_WALG_FAIL:-}" ] ;;
    *) [ -t 0 ] || cat >>"$FAKE/stdin.log" ;;
esac
EOF
cat >"$BIN/kubectl" <<'EOF'
#!/usr/bin/env bash
record kubectl "$@"
ctx="$2"
shift 2
case "$*" in
    "create namespace "*) echo "apiVersion: v1"; echo "kind: Namespace" ;;
    "apply -f -"*) cat >/dev/null ;;
    "apply --server-side --field-manager=rebuild-node -f -")
        doc="$(cat)"
        grep -q 'suspend: true' <<<"$doc" && record suspended-hr "$ctx" ;;
    "apply --server-side -k "*) record kustomization "$ctx" ;;
    "get secret flux-system -n flux-system") [ -e "$FAKE/flux-secret" ] ;;
    "get secret flux-system -n flux-system -o jsonpath="*) printf 'ecdsa-sha2-nistp384 AAAANEW flux' | base64 ;;
    "get secret event-junkie-tls -n event-junkie") [ "$ctx" != event-junkie-production-next ] || [ -e "$FAKE/tls-applied" ] ;;
    "get secret event-junkie-tls"*"-o json"*)
        echo '{"apiVersion":"v1","kind":"Secret","type":"kubernetes.io/tls","data":{"tls.key":"enE5eA=="},"metadata":{"name":"event-junkie-tls","namespace":"event-junkie","uid":"u1","annotations":{"cert-manager.io/issuer-name":"le","other":"x"}}}' ;;
    "get secret event-junkie-tls-redirect"*) ;;
    "get nodes"*) echo "production-k3s   Ready   control-plane" ;;
    "get helmreleases -n flux-system -o jsonpath="*) printf 'cert-manager\nevent-junkie\nopenobserve\n' ;;
    "get helmrelease event-junkie -n flux-system -o jsonpath={.spec.suspend}") echo "${FAKE_SUSPEND:-true}" ;;
    "get pods -A"*) ;;
    "get certificate -A --no-headers") echo "event-junkie event-junkie-tls True event-junkie-tls 1d" ;;
    "apply --server-side --field-manager=rebuild-node -f "*) touch "$FAKE/tls-applied"; record tls-applied "$ctx" ;;
    *) [ -t 0 ] || cat >/dev/null ;;
esac
EOF
cat >"$BIN/flux" <<'EOF'
#!/usr/bin/env bash
record flux "$@"
case "$*" in *"create secret git"*) touch "$FAKE/flux-secret" ;; esac
EOF
cat >"$BIN/gh" <<'EOF'
#!/usr/bin/env bash
record gh "$@"
case "$*" in
    "api repos/enorm-labs/event-junkie/keys")
        echo '[{"id":11,"title":"flux-system-main-flux-system-./deploy/clusters/production","key":"ecdsa-sha2-nistp384 AAAAOLD"},
               {"id":12,"title":"flux-system-main-flux-system-./deploy/clusters/staging","key":"ecdsa-sha2-nistp384 AAAASTAGING"}]' ;;
esac
EOF
cat >"$BIN/security" <<'EOF'
#!/usr/bin/env bash
while [ $# -gt 0 ]; do [ "$1" = -s ] && item="$2"; shift; done
printf 'zq9x-%s\n' "$item"
EOF
cat >"$BIN/sops" <<'EOF'
#!/usr/bin/env bash
printf 'data:\n  password: %s\n' "$(printf 'zq9x-events' | base64)"
EOF
for tool in curl wg-quick cluster-access alerts-apply dashboards-apply; do
    printf '#!/usr/bin/env bash\nrecord %s "$@"\n' "$tool" >"$BIN/$tool"
done
cat >"$BIN/cluster-secrets" <<'EOF'
#!/usr/bin/env bash
record cluster-secrets "$@" "ctx=$EJ_KUBE_CONTEXT" "skip=$EJ_SKIP_SECRETS"
EOF
cat >"$BIN/sudo" <<'EOF'
#!/usr/bin/env bash
exec "$@"
EOF
chmod +x "$BIN"/*

FAILURES=0
fail() {
    echo "FAIL $*" >&2
    FAILURES=$((FAILURES + 1))
}

NEXT_JSON='{"k3s_temporary_ipv4":"203.0.113.20","k3s_private_ip":"10.0.1.11","postgres_private_ip":"10.0.1.21"}'

# run <name> <expected exit> <args…>: a fresh log and cluster for each run.
run() {
    local name="$1" want="$2" got=0
    shift 2
    rm -f "$WORK"/*.log "$WORK/flux-secret" "$WORK/tls-applied"
    OUT="$("$SCRIPT" "$@" 2>&1 </dev/null)" || got=$?
    [ "$got" -eq "$want" ] || fail "$name: exit $got, expected $want. Output: $OUT"
    touch "$WORK/events.log" "$WORK/stdin.log"
    if grep -q zq9x <<<"$OUT"; then fail "$name: a value reached the output"; fi
    if grep -q zq9x "$WORK/events.log"; then fail "$name: a value reached an argument: $(grep zq9x "$WORK/events.log" | head -1)"; fi
}
has() { grep -qF -- "$2" "$WORK/events.log" || fail "$1: no '$2' in the event log"; }
lacks() { if grep -qF -- "$2" "$WORK/events.log"; then fail "$1: '$2' should not happen: $(grep -F -- "$2" "$WORK/events.log" | head -1)"; fi; }
line_of() { grep -nF -- "$1" "$WORK/events.log" | head -1 | cut -d: -f1; }

# 1. --next on production: the new pair through the running node, the cutover's work left out.
EJ_NEXT_PAIR_JSON="$NEXT_JSON" run "next" 0 production --next
has next "-J ops@10.10.0.1 ops@10.0.1.11"
has next "-J ops@10.10.0.1 ops@10.0.1.21"
has next "cluster-access production --next --endpoint 203.0.113.20 --merge --db-node 10.0.1.21 --server-key NEXTSERVERKEY="
has next "cluster-secrets production ctx=event-junkie-production-next skip=flux-system/github-dispatch"
has next "cluster-secrets production --check ctx=event-junkie-production-next skip=flux-system/github-dispatch"
has next "kubectl --context event-junkie-production get secret event-junkie-tls -n event-junkie -o json --ignore-not-found"
has next "tls-applied event-junkie-production-next"
has next "--title flux-system-main-flux-system-./deploy/clusters/production (next)"
lacks next "ALTER ROLE"
lacks next "walg"
lacks next "alerts-apply"
lacks next "dashboards-apply"
lacks next "DELETE"
[ -n "$(line_of suspended-hr)" ] && [ "$(line_of suspended-hr)" -lt "$(line_of kustomization)" ] ||
    fail "next: the suspended HelmRelease is not applied before the Kustomization"
[ "$(line_of 'wg-quick down')" -lt "$(line_of 'wg-quick up')" ] 2>/dev/null || fail "next: the running tunnel is not brought back last"
grep -q "every step passed" <<<"$OUT" || fail "next: no final line"

# 2. Flux took suspend over: stop, and suspend it again.
EJ_NEXT_PAIR_JSON="$NEXT_JSON" FAKE_SUSPEND=false run "suspend lost" 1 production --next
has "suspend lost" "flux --context event-junkie-production-next suspend helmrelease event-junkie"
grep -q "Flux took over spec.suspend" <<<"$OUT" || fail "suspend lost: no explanation"

# 3. No next pair in the state: order it first.
EJ_NEXT_PAIR_JSON=null run "no next pair" 1 production --next
grep -q "order-next-pair.sh production" <<<"$OUT" || fail "no next pair: no hint: $OUT"

# 4. In place on production: roles and wal-g over stdin, the old deploy key deleted, the site checked.
run "in place" 0 production
has "in place" "-o StrictHostKeyChecking=accept-new ops@203.0.113.10"
has "in place" "-J ops@203.0.113.10 ops@10.0.1.20"
has "in place" "cluster-access production --merge"
has "in place" "cluster-secrets production ctx=event-junkie-production skip="
has "in place" "gh api -X DELETE repos/enorm-labs/event-junkie/keys/11"
lacks "in place" "keys/12"
has "in place" "alerts-apply"
has "in place" "dashboards-apply"
has "in place" "curl -fsS -o /dev/null https://event-junkie.de/"
lacks "in place" "suspended-hr"
grep -q "ALTER ROLE metrics PASSWORD 'zq9x-event-junkie-production-metrics-password'" "$WORK/stdin.log" || fail "in place: metrics password not sent on stdin"
grep -q "AWS_ACCESS_KEY_ID=zq9x-event-junkie-s3-access-key" "$WORK/stdin.log" || fail "in place: wal-g credential not sent on stdin"
lacks "in place" "systemctl start walg-basebackup"

# 5. A role that does not match events-db gets its password; a failing walg check takes a base backup.
FAKE_EVENTS_MISMATCH=1 run "events mismatch" 0 production --from database
grep -q "ALTER ROLE events PASSWORD 'zq9x-events'" "$WORK/stdin.log" || fail "events mismatch: events password not set"
FAKE_WALG_FAIL=1 run "base backup" 1 production --from backups
has "base backup" "systemctl start walg-basebackup"

# 6. --from starts there; an unknown step is refused.
run "from" 0 production --from secrets
lacks from "cluster-access"
lacks from "boot:"
has from "cluster-secrets"
run "unknown step" 1 production --from tls
grep -q "no step 'tls' here" <<<"$OUT" || fail "unknown step: $OUT"

# 7. A failed boot names itself.
FAKE_BOOT="boot: failed: harden" run "boot failed" 1 production
grep -q "boot: failed: harden" <<<"$OUT" || fail "boot failed: $OUT"

# 8. Staging in place: one node, the database on it, the site through the tunnel.
run "staging" 0 staging
has staging "--resolve staging.event-junkie.de:443:10.10.1.1"
lacks staging "-J ops@10.10.1.1"

if [ "$FAILURES" -gt 0 ]; then
    echo "rebuild-node-test: $FAILURES failure(s)" >&2
    exit 1
fi
echo "rebuild-node-test: all cases pass"
