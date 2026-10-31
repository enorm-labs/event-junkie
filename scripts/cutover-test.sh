#!/usr/bin/env bash
#
# cutover-test.sh — assert what `cutover.sh` moves, in which order, and what it refuses, against a fabricated
# Hetzner project, clusters and state.
#
# The fake API keeps servers, Primary IPs and the volume in one JSON file and refuses what Hetzner refuses:
# a Primary IP moves only while its servers are off, a server holds one IPv4, and a volume is detached
# only from a server that is off. Every fake appends to one event log, so order is a line number.
#
# Usage: scripts/cutover-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

SCRIPT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/cutover.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BIN="$WORK/bin"
mkdir -p "$BIN"
export FAKE="$WORK" HOME="$WORK/home" PATH="$BIN:$PATH" HCLOUD_TOKEN=zq9xtoken EJ_HCLOUD_API=https://api.test/v1 \
    EJ_WG_RUN_DIR="$WORK/run" EJ_WG_DIR="$WORK/home/.wireguard" EJ_CUTOVER_DIR="$WORK/cutover" EJ_TRIES=3 EJ_SLEEP=0 \
    EJ_SWITCH_TRIES=3 EJ_SWITCH_SLEEP=0 EJ_CLUSTER_ACCESS="$BIN/cluster-access" EJ_REBUILD_NODE="$BIN/rebuild-node" \
    EJ_HELM_RELEASE="$WORK/helm-release.yaml" EJ_MAIN_TF="$WORK/main.tf"

cat >"$BIN/record" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE/events.log"
EOF
# A Hetzner API that holds its state in api.json.
cat >"$BIN/curl" <<'EOF'
#!/usr/bin/env bash
method=GET body="" url=""
while [ $# -gt 0 ]; do
    case "$1" in
        -X) method="$2"; shift 2 ;;
        -d) body="$2"; shift 2 ;;
        -H | -o | --resolve) shift 2 ;;
        http*) url="$1"; shift ;;
        *) shift ;;
    esac
done
case "$url" in
    https://api.test/v1*) ;;
    *) record site "$url"; exit 0 ;;
esac
path="${url#https://api.test/v1}"
s="$FAKE/api.json"
[ "$method" = GET ] || record api "$method $path $body"
err() { echo "{\"error\":{\"message\":\"$1\"}}"; exit 22; }
put() { jq "$@" "$s" >"$s.new" && mv "$s.new" "$s"; }
id="$(cut -d/ -f3 <<<"$path")"
srv_status() { jq -r --argjson id "$1" '.servers[] | select(.id == $id) | .status' "$s"; }
ip_owner() { jq -r --argjson id "$id" '.ips[] | select(.id == $id) | .assignee_id // empty' "$s"; }
case "$method $path" in
    "GET /servers?name="* | "GET /primary_ips?name="* | "GET /volumes?name="*)
        kind="$(cut -d'?' -f1 <<<"${path#/}")"
        key="$kind"; [ "$kind" != primary_ips ] || key=ips
        jq --arg n "${path#*=}" --arg k "$kind" --arg key "$key" '{($k): [.[$key][] | select(.name == $n)]}' "$s" ;;
    "GET /primary_ips") jq '{primary_ips: .ips}' "$s" ;;
    "GET /servers/"*) jq --argjson id "$id" '{server: (.servers[] | select(.id == $id))}' "$s" ;;
    "GET /primary_ips/"*) jq --argjson id "$id" '{primary_ip: (.ips[] | select(.id == $id))}' "$s" ;;
    "GET /volumes/"*) jq --argjson id "$id" '{volume: (.volumes[] | select(.id == $id))}' "$s" ;;
    "GET /actions/"*) echo '{"action":{"status":"success"}}' ;;
    "POST /servers/"*/actions/shutdown | "POST /servers/"*/actions/poweroff)
        put --argjson id "$id" '(.servers[] | select(.id == $id) | .status) = "off"'; echo '{"action":{"id":1}}' ;;
    "POST /servers/"*/actions/poweron)
        put --argjson id "$id" '(.servers[] | select(.id == $id) | .status) = "running"'; echo '{"action":{"id":1}}' ;;
    "PUT /servers/"* | "PUT /primary_ips/"*)
        key=servers; [[ "$path" != /primary_ips/* ]] || key=ips
        put --argjson id "$id" --arg key "$key" --argjson b "$body" '(.[$key][] | select(.id == $id) | .name) = $b.name'; echo '{}' ;;
    "POST /primary_ips/"*/actions/unassign)
        owner="$(ip_owner)"
        [ -n "$owner" ] || err "not assigned"
        [ "$(srv_status "$owner")" = off ] || err "server $owner is running"
        put --argjson id "$id" '(.ips[] | select(.id == $id) | .assignee_id) = null'; echo '{"action":{"id":1}}' ;;
    "POST /primary_ips/"*/actions/assign)
        to="$(jq -r .assignee_id <<<"$body")"
        [ -z "$(ip_owner)" ] || err "already assigned"
        [ "$(srv_status "$to")" = off ] || err "server $to is running"
        [ "$(jq --argjson to "$to" '[.ips[] | select(.assignee_id == $to and .type == "ipv4")] | length' "$s")" = 0 ] || err "server $to has an IPv4"
        put --argjson id "$id" --argjson to "$to" '(.ips[] | select(.id == $id) | .assignee_id) = $to'; echo '{"action":{"id":1}}' ;;
    "POST /primary_ips/"*/actions/change_protection)
        put --argjson id "$id" '(.ips[] | select(.id == $id) | .protection.delete) = false'; echo '{"action":{"id":1}}' ;;
    "POST /volumes/"*/actions/detach)
        on="$(jq -r '.volumes[0].server // empty' "$s")"
        [ -n "$on" ] && [ "$(srv_status "$on")" = off ] || err "volume server running or none"
        put '.volumes[0].server = null'; echo '{"action":{"id":1}}' ;;
    "POST /volumes/"*/actions/attach)
        [ "$(jq -r '.volumes[0].server // empty' "$s")" = "" ] || err "already attached"
        put --argjson b "$body" '.volumes[0].server = $b.server'; echo '{"action":{"id":1}}' ;;
    *) err "unknown $method $path" ;;
esac
EOF
cat >"$BIN/kubectl" <<'EOF'
#!/usr/bin/env bash
record kubectl "$@"
ctx="$2"
shift 2
case "$*" in
    "get kustomization flux-system -n flux-system -o jsonpath={.spec.suspend}") [ ! -e "$FAKE/frozen-$ctx" ] || echo true ;;
    "get helmrelease event-junkie -n flux-system -o jsonpath={.spec.values.database.host}")
        n="$(cat "$FAKE/host-reads" 2>/dev/null || echo 0)"
        echo $((n + 1)) >"$FAKE/host-reads"
        if [ "$n" -ge "${FAKE_SWITCH_AFTER:-1}" ]; then echo "$FAKE_NEW_HOST"; else echo old; fi ;;
    "get helmrelease event-junkie -n flux-system -o jsonpath={.spec.suspend}") echo true ;;
    "get helmrelease openobserve -n flux-system -o jsonpath={.spec.values.config.ZO_S3_BUCKET_PREFIX}")
        env="${ctx#event-junkie-}"
        if [[ "$ctx" == *-next ]]; then echo "${FAKE_NEXT_PREFIX:-${env%-next}-2/}"; else echo "$env/"; fi ;;
esac
EOF
cat >"$BIN/flux" <<'EOF'
#!/usr/bin/env bash
record flux "$@"
[ "$2 $3 $4" != "$2 suspend kustomization" ] || touch "$FAKE/frozen-$2"
EOF
cat >"$BIN/ssh" <<'EOF'
#!/usr/bin/env bash
record ssh "$@"
case "$*" in
    *"SELECT 1"*) echo 1 ;;
    *step.sh*) [ -z "${FAKE_DB_FAIL:-}" ] ;;
esac
EOF
cat >"$BIN/sudo" <<'EOF'
#!/usr/bin/env bash
record sudo "$@"
f="$EJ_WG_RUN_DIR/$(basename "$3" .conf).name"
if [ "$2" = up ]; then touch "$f"; else rm -f "$f"; fi
EOF
cat >"$BIN/direnv" <<'EOF'
#!/usr/bin/env bash
shift 2
exec "$@"
EOF
cat >"$BIN/tofu" <<'EOF'
#!/usr/bin/env bash
shift
record tofu "$@"
case "$1 $2" in
    "state list") cat "$FAKE/state.list" ;;
    "state pull") echo '{"version":4}' ;;
    "state mv") grep -vxF "$3" "$FAKE/state.list" >"$FAKE/state.new"; echo "$4" >>"$FAKE/state.new"; mv "$FAKE/state.new" "$FAKE/state.list" ;;
esac
EOF
for tool in cluster-access rebuild-node; do
    printf '#!/usr/bin/env bash\nrecord %s "$@"\n' "$tool" >"$BIN/$tool"
done
chmod +x "$BIN"/*

FAILURES=0
fail() {
    echo "FAIL $*" >&2
    FAILURES=$((FAILURES + 1))
}

# setup <env>: a fresh project with both pairs running, the old tunnel up, and the switch PR not merged.
setup() {
    rm -rf "$WORK/cutover" "$WORK/run" "$WORK"/*.log "$WORK"/frozen-* "$WORK/host-reads"
    mkdir -p "$WORK/run" "$EJ_WG_DIR"
    touch "$WORK/run/$1.name"
    printf '[Interface]\nPrivateKey = X=\n[Peer]\nPublicKey  = OLDKEY=\nEndpoint   = 203.0.113.10:51820\n' >"$EJ_WG_DIR/$1.conf"
    printf '[Interface]\nPrivateKey = X=\n[Peer]\nPublicKey  = NEXTKEY=\nEndpoint   = 203.0.113.99:51820\n' >"$EJ_WG_DIR/$1-next.conf"
    if [ "$1" = production ]; then
        export FAKE_NEW_HOST=10.0.1.21
        cat >"$WORK/api.json" <<'JSON'
{"servers": [
  {"id": 1, "name": "production-k3s", "status": "running", "private_net": [{"ip": "10.0.1.10"}], "server_type": {"name": "cpx32"}},
  {"id": 2, "name": "production-postgres", "status": "running", "private_net": [{"ip": "10.0.1.20"}], "server_type": {"name": "cpx22"}},
  {"id": 3, "name": "production-k3s-next", "status": "running", "private_net": [{"ip": "10.0.1.11"}], "server_type": {"name": "cx33"}},
  {"id": 4, "name": "production-postgres-next", "status": "running", "private_net": [{"ip": "10.0.1.21"}], "server_type": {"name": "cx23"}}],
 "ips": [
  {"id": 11, "name": "production-k3s-ipv4", "type": "ipv4", "assignee_id": 1, "protection": {"delete": true}},
  {"id": 12, "name": "production-k3s-ipv6", "type": "ipv6", "assignee_id": 1, "protection": {"delete": true}},
  {"id": 13, "name": "production-postgres-ipv4", "type": "ipv4", "assignee_id": 2, "protection": {"delete": true}},
  {"id": 14, "name": "production-postgres-ipv6", "type": "ipv6", "assignee_id": 2, "protection": {"delete": true}},
  {"id": 15, "name": "production-k3s-next-ipv4", "type": "ipv4", "assignee_id": 3, "protection": {"delete": false}},
  {"id": 16, "name": "production-k3s-next-ipv6", "type": "ipv6", "assignee_id": 3, "protection": {"delete": false}},
  {"id": 17, "name": "production-postgres-next-ipv4", "type": "ipv4", "assignee_id": 4, "protection": {"delete": false}},
  {"id": 18, "name": "production-postgres-next-ipv6", "type": "ipv6", "assignee_id": 4, "protection": {"delete": false}}],
 "volumes": [{"id": 21, "name": "production-pgdata", "server": 2}]}
JSON
    else
        export FAKE_NEW_HOST=10.1.1.11
        cat >"$WORK/api.json" <<'JSON'
{"servers": [
  {"id": 1, "name": "staging-k3s", "status": "running", "private_net": [{"ip": "10.1.1.10"}], "server_type": {"name": "cx33"}},
  {"id": 3, "name": "staging-k3s-next", "status": "running", "private_net": [{"ip": "10.1.1.11"}], "server_type": {"name": "cx33"}}],
 "ips": [
  {"id": 11, "name": "staging-k3s-ipv4", "type": "ipv4", "assignee_id": 1, "protection": {"delete": true}},
  {"id": 12, "name": "staging-k3s-ipv6", "type": "ipv6", "assignee_id": 1, "protection": {"delete": true}},
  {"id": 15, "name": "staging-k3s-next-ipv4", "type": "ipv4", "assignee_id": 3, "protection": {"delete": false}},
  {"id": 16, "name": "staging-k3s-next-ipv6", "type": "ipv6", "assignee_id": 3, "protection": {"delete": false}}],
 "volumes": [{"id": 21, "name": "staging-pgdata", "server": 1}]}
JSON
    fi
    echo '      host: "old"' >"$WORK/helm-release.yaml"
}
merge_switch() { echo "      host: \"$FAKE_NEW_HOST\"" >"$WORK/helm-release.yaml"; }
# run <name> <expected exit> <stdin> <args…>
run() {
    local name="$1" want="$2" input="$3" got=0
    shift 3
    OUT="$("$SCRIPT" "$@" <<<"$input" 2>&1)" || got=$?
    [ "$got" -eq "$want" ] || fail "$name: exit $got, expected $want. Output: $OUT"
}
q() { jq -r "$1" "$WORK/api.json"; }
line() { grep -nF -- "$1" "$WORK/events.log" | head -1 | cut -d: -f1; }
has() { grep -qF -- "$2" "$WORK/events.log" || fail "$1: no '$2' in the event log"; }
before() { [ -n "$(line "$2")" ] && [ -n "$(line "$3")" ] && [ "$(line "$2")" -lt "$(line "$3")" ] || fail "$1: '$2' is not before '$3'"; }

# 1. prepare freezes the old cluster's Flux, then waits until the new cluster reads the switch PR.
setup production
run "prepare" 0 "" production prepare
has "prepare" "flux --context event-junkie-production suspend kustomization flux-system -n flux-system"
[ -e "$WORK/frozen-event-junkie-production" ] || fail "prepare: Flux on the old cluster is not frozen"
[ ! -e "$WORK/frozen-event-junkie-production-next" ] || fail "prepare: froze the new cluster"
[ "$(cat "$WORK/cutover/old-server-key")" = OLDKEY= ] || fail "prepare: old server key not kept"
[ -e "$WORK/run/production.name" ] && [ ! -e "$WORK/run/production-next.name" ] || fail "prepare: the old tunnel is not the one left up"
grep -qF "git pull" <<<"$OUT" || fail "prepare: does not say git pull"
O2="-n observability"
before "prepare" "kubectl --context event-junkie-production-next delete statefulset openobserve-openobserve-standalone $O2 --cascade=orphan" \
    "kubectl --context event-junkie-production-next delete pvc data-openobserve-openobserve-standalone-0"
before "prepare" "kubectl --context event-junkie-production-next delete pvc data-openobserve-openobserve-standalone-0" \
    "flux --context event-junkie-production-next reconcile helmrelease openobserve -n flux-system --force"
! grep -qF "kubectl --context event-junkie-production delete" "$WORK/events.log" || fail "prepare: deleted on the old cluster"
: >"$WORK/events.log"
run "prepare again" 0 "" production prepare --from logs
! grep -qF "delete pvc" "$WORK/events.log" || fail "prepare again: wiped the new OpenObserve twice"
rm -f "$WORK/cutover/logs-production-2"
: >"$WORK/events.log"
FAKE_NEXT_PREFIX=production/ run "same prefix" 1 "" production prepare --from logs
grep -qF "#4302" <<<"$OUT" || fail "same prefix: does not name #4302"
! grep -qF "delete" "$WORK/events.log" || fail "same prefix: deleted something"
FAKE_SWITCH_AFTER=9 run "switch never merged" 1 "" production prepare --from switch

# 2. cutover refuses an unpulled checkout, and anything but yes, before it changes anything.
run "not pulled" 1 "yes" production cutover
grep -qF "git pull" <<<"$OUT" || fail "not pulled: does not say git pull"
merge_switch
run "not confirmed" 1 "no" production cutover
! grep -q '^api ' "$WORK/events.log" || fail "not confirmed: called $(grep '^api ' "$WORK/events.log" | head -1)"
! grep -qF "kubectl --context event-junkie-production scale" "$WORK/events.log" || fail "not confirmed: scaled the importer"

# 3. The production cutover, in order.
run "cutover" 0 "yes" production cutover
[ "$(q '.ips[] | select(.id == 11) | .assignee_id')" = 3 ] || fail "cutover: the IPv4 is not on the new k3s node"
[ "$(q '.ips[] | select(.id == 15) | .assignee_id')" = null ] || fail "cutover: the temporary IPv4 is still assigned"
[ "$(q '.ips[] | select(.id == 12) | .assignee_id')" = 1 ] || fail "cutover: the old IPv6 moved"
[ "$(q '.volumes[0].server')" = 4 ] || fail "cutover: the volume is not on the new database node"
[ "$(q '[.servers[] | select(.status == "running") | .id] | join(",")')" = 3,4 ] || fail "cutover: running servers $(q '[.servers[] | select(.status == "running") | .id]')"
[ "$(q '[.servers[] | .name] | join(",")')" = production-k3s-old,production-postgres-old,production-k3s-next,production-postgres-next ] ||
    fail "cutover: names $(q '[.servers[] | .name]')"
[ "$(q '[.ips[] | select(.name | endswith("-old")) | select(.protection.delete == false) | .id] | join(",")')" = 12,13,14 ] ||
    fail "cutover: old IPs renamed and unprotected: $(q '[.ips[] | select(.name | endswith("-old")) | .id]')"
[ "$(q '.ips[] | select(.id == 11) | .protection.delete')" = true ] || fail "cutover: unprotected the real IPv4"
before "cutover" "kubectl --context event-junkie-production scale deployment -n event-junkie -l app.kubernetes.io/component=importer --replicas=0" "systemctl stop postgresql"
before "cutover" "-J ops@10.10.0.1 ops@10.0.1.20 sudo -u postgres psql -qc CHECKPOINT" "api POST /servers/2/actions/shutdown"
before "cutover" "api POST /servers/3/actions/shutdown" "api POST /primary_ips/11/actions/assign"
before "cutover" "api POST /primary_ips/11/actions/assign" "api POST /servers/3/actions/poweron"
before "cutover" "cluster-access production --server-key NEXTKEY=" "-J ops@10.10.0.1 ops@10.0.1.21 sudo /opt/event-junkie/step.sh postgres"
before "cutover" "step.sh postgres" "flux --context event-junkie-production resume helmrelease event-junkie"
before "cutover" "site https://event-junkie.de/api/events?size=1" "rebuild-node production --from secrets"
grep -qF zq9xtoken "$WORK/events.log" && fail "cutover: the Hetzner token reached an argument"
grep -qF "after the confirm" <<<"$OUT" || fail "cutover: no downtime figure"

# 4. A rerun moves nothing and powers nothing.
: >"$WORK/events.log"
run "rerun" 0 "yes" production cutover --from power-off
grep -E '^api POST .*/(assign|unassign|attach|detach|shutdown|poweroff|poweron)' "$WORK/events.log" && fail "rerun: changed the project again"

# 5. Rollback puts the IPv4, the temporary IPv4 and the volume back, with the old server key.
run "rollback" 0 "" production rollback
[ "$(q '.ips[] | select(.id == 11) | .assignee_id')" = 1 ] || fail "rollback: the IPv4 is not back on the old k3s node"
[ "$(q '.ips[] | select(.id == 15) | .assignee_id')" = 3 ] || fail "rollback: the temporary IPv4 is not back"
[ "$(q '.volumes[0].server')" = 2 ] || fail "rollback: the volume is not back on the old database node"
[ "$(q '[.servers[] | select(.status == "running")] | length')" = 4 ] || fail "rollback: not every server runs"
has "rollback" "cluster-access production --server-key OLDKEY="
has "rollback" "kubectl --context event-junkie-production scale deployment -n event-junkie -l app.kubernetes.io/component=importer --replicas=1"
grep -qF "revert the switch PR" <<<"$OUT" || fail "rollback: does not say to revert the switch PR"

# 6. Staging has one node: the volume moves with the k3s node, and PostgreSQL is reached over the tunnel alone.
setup staging
run "staging prepare" 0 "" staging prepare
has "staging prepare" "kubectl --context event-junkie-staging-next delete pvc data-openobserve-openobserve-standalone-0"
merge_switch
run "staging cutover" 0 "yes" staging cutover
[ "$(q '.volumes[0].server')" = 3 ] || fail "staging: the volume is not on the new k3s node"
[ "$(q '.ips[] | select(.id == 11) | .assignee_id')" = 3 ] || fail "staging: the IPv4 is not on the new k3s node"
has "staging" "ssh -i $HOME/.ssh/id_ed25519_hetzner -o BatchMode=yes -o ConnectTimeout=5 ops@10.10.1.1 sudo /opt/event-junkie/step.sh postgres"
has "staging" "site https://staging.event-junkie.de/"
! grep -qF -- "-J ops@10.10.1.1" "$WORK/events.log" || fail "staging: jumped to a database node"

# 7. finish refuses a config that does not name the new pair, then moves the state in order.
setup production
run "prepare for finish" 0 "" production prepare
merge_switch
run "cutover for finish" 0 "yes" production cutover
printf 'module.environment.%s\n' hcloud_server.k3s hcloud_server.postgres[0] hcloud_primary_ip.k3s_ipv4 hcloud_primary_ip.k3s_ipv6 \
    'hcloud_primary_ip.postgres_ipv4[0]' 'hcloud_primary_ip.postgres_ipv6[0]' 'hcloud_server.k3s_next[0]' 'hcloud_server.postgres_next[0]' \
    'hcloud_primary_ip.k3s_next_ipv4[0]' 'hcloud_primary_ip.k3s_next_ipv6[0]' 'hcloud_primary_ip.postgres_next_ipv4[0]' \
    'hcloud_primary_ip.postgres_next_ipv6[0]' hcloud_volume.postgres >"$WORK/state.list"
printf '  k3s_server_type = "cpx32"\n' >"$WORK/main.tf"
run "finish unmerged" 1 "" production finish
grep -q '^tofu state mv' "$WORK/events.log" && fail "finish unmerged: moved state"
printf '  k3s_server_type = "cx33"\n  k3s_private_ip  = "10.0.1.11"\n' >"$WORK/main.tf"
run "finish" 0 "" production finish
before "finish" "tofu state pull" "tofu state mv"
before "finish" "tofu state mv module.environment.hcloud_server.k3s module.environment.hcloud_server.k3s_old" \
    "tofu state mv module.environment.hcloud_server.k3s_next[0] module.environment.hcloud_server.k3s"
want="$(printf 'module.environment.%s\n' hcloud_primary_ip.k3s_ipv4 'hcloud_primary_ip.k3s_next_ipv4[0]' hcloud_volume.postgres \
    hcloud_server.k3s_old hcloud_primary_ip.k3s_ipv6_old hcloud_server.postgres_old hcloud_primary_ip.postgres_ipv4_old \
    hcloud_primary_ip.postgres_ipv6_old hcloud_server.k3s hcloud_primary_ip.k3s_ipv6 'hcloud_server.postgres[0]' \
    'hcloud_primary_ip.postgres_ipv4[0]' 'hcloud_primary_ip.postgres_ipv6[0]' | sort)"
[ "$(sort "$WORK/state.list")" = "$want" ] || fail "finish: state is $(sort "$WORK/state.list" | tr '\n' ' ')"
grep -qF "0 to add, 5 to change, 6 to destroy" <<<"$OUT" || fail "finish: expected count: $OUT"
: >"$WORK/events.log"
run "finish rerun" 0 "" production finish
grep -q '^tofu state mv' "$WORK/events.log" && fail "finish rerun: moved state again"

if [ "$FAILURES" -gt 0 ]; then
    echo "cutover-test: $FAILURES failure(s)" >&2
    exit 1
fi
echo "cutover-test: all cases pass"
