#!/usr/bin/env bash
#
# cluster-access-test.sh — assert what `cluster-access.sh` replaces after a rebuild, and that a second
# run changes nothing, against a fabricated node.
#
# Fakes `ssh`, `ssh-keyscan`, `sudo`, `wg-quick` and `ping` on PATH, and wraps `kubectl` so that
# `get nodes` never reaches a cluster. `ssh-keygen` and `kubectl config` are the real ones, because
# known_hosts and kubeconfig handling are what the script exists for. HOME is a temp dir.
#
# Usage: scripts/cluster-access-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/cluster-access.sh"
REAL_KUBECTL="$(command -v kubectl)" || {
    echo "cluster-access-test: needs kubectl on PATH" >&2
    exit 1
}
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
STATE="$WORK/state"
BIN="$WORK/bin"
mkdir -p "$STATE" "$BIN" "$WORK/run"
export HOME="$WORK/home"
mkdir -p "$HOME/.wireguard" "$HOME/.ssh" "$HOME/.kube"
export EJ_WG_RUN_DIR="$WORK/run" EJ_PING_TRIES=1 FAKE_STATE="$STATE"

FAILURES=0
fail() {
    echo "FAIL $*" >&2
    FAILURES=$((FAILURES + 1))
}
pass() { echo "ok   $*"; }

hostkey() {
    ssh-keygen -q -t ed25519 -N '' -C '' -f "$WORK/k$1" >/dev/null
    awk '{ print $2 }' "$WORK/k$1.pub"
}

# --- the fabricated node --------------------------------------------------------------------------

# The tunnel answers only when it is up with the key the node really has.
cat >"$BIN/ping" <<'EOF'
#!/usr/bin/env bash
{ [ -e "$EJ_WG_RUN_DIR/production.name" ] || [ -e "$EJ_WG_RUN_DIR/production-next.name" ]; } &&
    cmp -s "$FAKE_STATE/tunnel-key" "$FAKE_STATE/server-key"
EOF
cat >"$BIN/sudo" <<'EOF'
#!/usr/bin/env bash
exec "$@"
EOF
cat >"$BIN/wg-quick" <<'EOF'
#!/usr/bin/env bash
env="$(basename "$2" .conf)"
echo "$1" >>"$FAKE_STATE/wg-quick.log"
case "$1" in
    up)
        awk '/^\[Peer\]/ { p = 1 } p && /PublicKey/ { sub(/^[^=]*=[[:space:]]*/, ""); print; exit }' "$2" >"$FAKE_STATE/tunnel-key"
        touch "$EJ_WG_RUN_DIR/$env.name"
        ;;
    down) rm -f "$EJ_WG_RUN_DIR/$env.name" ;;
esac
EOF
cat >"$BIN/reachable" <<'EOF'
#!/usr/bin/env bash
case "$1" in
    10.10.0.1) ping ;;
    203.0.113.10) [ -e "$FAKE_STATE/public-ssh-open" ] ;;
    *) exit 1 ;;
esac
EOF
cat >"$BIN/ssh-keyscan" <<'EOF'
#!/usr/bin/env bash
host="${!#}"
reachable "$host" || exit 1
echo "$host ssh-ed25519 $(cat "$FAKE_STATE/hostkey-$host")"
EOF
cat >"$BIN/ssh" <<'EOF'
#!/usr/bin/env bash
while [ $# -gt 0 ]; do
    case "$1" in
        -i | -o) shift 2 ;;
        ops@*) host="${1#ops@}"; shift; break ;;
        *) shift ;;
    esac
done
reachable "$host" || exit 255
[ ! -e "$FAKE_STATE/ssh-refused" ] || exit 255
case "$*" in
    "sudo cat /etc/wireguard/public.key") cat "$FAKE_STATE/server-key" ;;
    "sudo cat /etc/rancher/k3s/k3s.yaml") cat "$FAKE_STATE/k3s.yaml" ;;
    "ssh-keyscan -T 5 -t ed25519 10.0.1.21") echo "10.0.1.21 ssh-ed25519 $(cat "$FAKE_STATE/hostkey-10.0.1.21")" ;;
    *) echo "fake ssh: unexpected command: $*" >&2; exit 2 ;;
esac
EOF
cat >"$BIN/kubectl" <<EOF
#!/usr/bin/env bash
for a in "\$@"; do [ "\$a" = get ] && { echo "NAME             STATUS   ROLES"; echo "production-k3s   Ready    control-plane"; exit 0; }; done
exec "$REAL_KUBECTL" "\$@"
EOF
chmod +x "$BIN"/*
export PATH="$BIN:$PATH"

k3s_yaml() {
    cat >"$STATE/k3s.yaml" <<EOF
apiVersion: v1
kind: Config
clusters:
- cluster:
    certificate-authority-data: $(printf 'CA-%s' "$1" | base64)
    server: https://127.0.0.1:6443
  name: default
contexts:
- context:
    cluster: default
    user: default
  name: default
current-context: default
users:
- name: default
  user:
    client-certificate-data: $(printf 'CERT-%s' "$1" | base64)
    client-key-data: $(printf 'KEY-%s' "$1" | base64)
EOF
}

# A Mac set up before the rebuild: the old server key, the old host keys, the old CA in both kubeconfigs.
for h in 10.10.0.1 203.0.113.10 10.0.1.21; do
    old="$(hostkey "old-$h")"
    echo "$h ssh-ed25519 $old" >>"$HOME/.ssh/known_hosts"
    hostkey "new-$h" >"$STATE/hostkey-$h"
done
echo "OLDSERVERKEY=" >"$STATE/old-server-key"
echo "NEWSERVERKEY=" >"$STATE/server-key"
cat >"$HOME/.wireguard/production.conf" <<EOF
[Interface]
PrivateKey = CLIENTPRIVATEKEY=
Address    = 10.10.0.2/32
[Peer]
PublicKey  = OLDSERVERKEY=
Endpoint   = 203.0.113.10:51820
AllowedIPs = 10.10.0.0/24
PersistentKeepalive = 25
EOF
k3s_yaml old
sed -e 's|127.0.0.1|10.10.0.1|' -e 's|: default$|: event-junkie-production|' "$STATE/k3s.yaml" >"$HOME/.kube/event-junkie-production"
cat >"$HOME/.kube/other" <<'EOF'
apiVersion: v1
kind: Config
clusters:
- cluster:
    server: https://other.example:6443
  name: other
contexts:
- context:
    cluster: other
    user: other
  name: other
current-context: other
users:
- name: other
  user:
    token: other-token
EOF
KUBECONFIG="$HOME/.kube/other:$HOME/.kube/event-junkie-production" kubectl config view --flatten >"$HOME/.kube/config"
k3s_yaml new
cp "$HOME/.kube/other" "$WORK/stray"
cp "$WORK/stray" "$WORK/stray.before"

run() { "$SCRIPT" production "$@" >"$WORK/out" 2>&1; }

# --- 1. A missing client config stops with the pointer --------------------------------------------

mv "$HOME/.wireguard/production.conf" "$WORK/conf.saved"
if run; then fail "1: ran without a client config"; elif grep -q 'Adding a Mac to the tunnel' "$WORK/out"; then
    pass "1: no client config, points to the runbook"
else fail "1: no pointer in: $(cat "$WORK/out")"; fi
mv "$WORK/conf.saved" "$HOME/.wireguard/production.conf"

# --- 2. No way to read the key: the tunnel has the old one and SSH from here is closed ------------

if run; then fail "2: ran without a server key"; elif grep -q -- '--server-key' "$WORK/out"; then
    pass "2: no server key anywhere, names --server-key"
else fail "2: no hint in: $(cat "$WORK/out")"; fi
if [ ! -e "$WORK/run/production.name" ]; then pass "2: the tunnel it brought up is down again"; else fail "2: left the tunnel up"; fi

# --- 3. After a rebuild, with admin_cidrs open and a stray KUBECONFIG ------------------------------

touch "$STATE/public-ssh-open"
if KUBECONFIG="$WORK/stray" run; then pass "3: the rebuild run succeeds"; else fail "3: exit $?: $(cat "$WORK/out")"; fi
if grep -q '^PublicKey  = NEWSERVERKEY=$' "$HOME/.wireguard/production.conf"; then pass "3: server key replaced"; else fail "3: server key not replaced"; fi
if grep -q '^PrivateKey = CLIENTPRIVATEKEY=$' "$HOME/.wireguard/production.conf"; then pass "3: private key kept"; else fail "3: private key lost"; fi
if compgen -G "$HOME/.wireguard/production.conf.bak.*" >/dev/null; then pass "3: client config backed up"; else fail "3: no client config backup"; fi
for h in 10.10.0.1 203.0.113.10 10.0.1.21; do
    if ssh-keygen -F "$h" -f "$HOME/.ssh/known_hosts" | grep -q "$(cat "$STATE/hostkey-$h")"; then pass "3: host key $h replaced"; else fail "3: host key $h not replaced"; fi
done
if [ "$(grep -c . "$HOME/.ssh/known_hosts")" = 3 ]; then pass "3: known_hosts holds three keys"; else fail "3: known_hosts: $(cat "$HOME/.ssh/known_hosts")"; fi
ca() { kubectl --kubeconfig "$1" config view --raw -o 'jsonpath={.clusters[?(@.name=="event-junkie-production")].cluster.certificate-authority-data}' | base64 -d; }
if [ "$(ca "$HOME/.kube/event-junkie-production")" = CA-new ]; then pass "3: kubeconfig file has the new CA"; else fail "3: kubeconfig file CA $(ca "$HOME/.kube/event-junkie-production")"; fi
if [ "$(ca "$HOME/.kube/config")" = CA-new ]; then pass "3: ~/.kube/config has the new CA"; else fail "3: ~/.kube/config CA $(ca "$HOME/.kube/config")"; fi
if kubectl --kubeconfig "$HOME/.kube/config" config get-contexts -o name | grep -qx other; then pass "3: other context kept"; else fail "3: other context lost"; fi
if [ "$(kubectl --kubeconfig "$HOME/.kube/config" config current-context)" = other ]; then pass "3: current context kept"; else fail "3: current context changed"; fi
if cmp -s "$WORK/stray" "$WORK/stray.before"; then pass "3: stray KUBECONFIG untouched"; else fail "3: stray KUBECONFIG was written"; fi
if grep -q 'production-k3s   Ready' "$WORK/out"; then pass "3: ends with get nodes"; else fail "3: no get nodes"; fi

# --- 4. A second run changes nothing ----------------------------------------------------------------

snapshot() { find "$HOME" -type f -exec cksum {} + | sort; }
snapshot >"$WORK/before"
ups_before="$(grep -c . "$STATE/wg-quick.log")"
if run; then pass "4: the second run succeeds"; else fail "4: exit $?: $(cat "$WORK/out")"; fi
snapshot >"$WORK/after"
if cmp -s "$WORK/before" "$WORK/after"; then pass "4: no file changed"; else fail "4: files changed: $(diff "$WORK/before" "$WORK/after")"; fi
if [ "$(grep -c . "$STATE/wg-quick.log")" = "$ups_before" ]; then pass "4: tunnel not restarted"; else fail "4: wg-quick ran again"; fi
if [ "$(grep -c 'unchanged' "$WORK/out")" = 7 ]; then pass "4: seven 'unchanged' lines"; else fail "4: output: $(cat "$WORK/out")"; fi

# --- 5. --merge adds a context that is not there yet ------------------------------------------------

kubectl --kubeconfig "$HOME/.kube/config" config delete-context event-junkie-production >/dev/null
kubectl --kubeconfig "$HOME/.kube/config" config delete-cluster event-junkie-production >/dev/null
kubectl --kubeconfig "$HOME/.kube/config" config delete-user event-junkie-production >/dev/null
if run && grep -q 'left as it is (--merge adds it)' "$WORK/out"; then pass "5: no entry, no --merge: left alone"; else fail "5: $(cat "$WORK/out")"; fi
if run --merge && [ "$(ca "$HOME/.kube/config")" = CA-new ]; then pass "5: --merge adds it"; else fail "5: --merge: $(cat "$WORK/out")"; fi

# --- 6. --server-key when nothing else can read it ---------------------------------------------------

rm -f "$STATE/public-ssh-open"
echo "THIRDKEY=" >"$STATE/server-key"
if run --server-key THIRDKEY=; then pass "6: --server-key repairs a stale tunnel"; else fail "6: exit $?: $(cat "$WORK/out")"; fi
if grep -q '^PublicKey  = THIRDKEY=$' "$HOME/.wireguard/production.conf"; then pass "6: key from the flag"; else fail "6: key not written"; fi

# --- 7. A tunnel that is only down is brought up to read the key -----------------------------------

wg-quick down "$HOME/.wireguard/production.conf"
if run; then pass "7: a down tunnel needs no --server-key"; else fail "7: exit $?: $(cat "$WORK/out")"; fi
if grep -q 'server key: read over the tunnel' "$WORK/out"; then pass "7: key read over the tunnel"; else fail "7: $(cat "$WORK/out")"; fi

# --- 8. The tunnel answers but SSH fails: the hint is the agent, not --server-key -------------------

wg-quick down "$HOME/.wireguard/production.conf"
touch "$STATE/ssh-refused"
if run; then fail "8: ran without SSH"; elif grep -q 'ssh-add' "$WORK/out"; then
    pass "8: SSH refused over the tunnel, names ssh-add"
else fail "8: no hint in: $(cat "$WORK/out")"; fi
if [ ! -e "$WORK/run/production.name" ]; then pass "8: the tunnel it brought up is down again"; else fail "8: left the tunnel up"; fi
rm -f "$STATE/ssh-refused"

# --- 9. --next: the first run needs the server key and the endpoint -------------------------------------

if run --next --endpoint 203.0.113.20; then fail "9: --next ran without --server-key"; elif grep -q -- '--server-key and --endpoint' "$WORK/out"; then
    pass "9: first --next run names --server-key"
else fail "9: no hint in: $(cat "$WORK/out")"; fi

# --- 10. --next beside a running tunnel: its own config, known_hosts and context ----------------------------

wg-quick up "$HOME/.wireguard/production.conf"
cp "$HOME/.ssh/known_hosts" "$WORK/known_hosts.before"
echo "NEXTKEY=" >"$STATE/server-key"
hostkey next-10.10.0.1 >"$STATE/hostkey-10.10.0.1"
k3s_yaml next
if run --next --endpoint 203.0.113.20 --server-key NEXTKEY= --merge; then pass "10: --next succeeds"; else fail "10: exit $?: $(cat "$WORK/out")"; fi
NEXT_CONF="$HOME/.wireguard/production-next.conf"
if grep -q 'taking it down first' "$WORK/out" && [ ! -e "$WORK/run/production.name" ]; then pass "10: the running tunnel went down first"; else fail "10: running tunnel: $(cat "$WORK/out")"; fi
if grep -q '^PublicKey  = NEXTKEY=$' "$NEXT_CONF" && grep -q '^Endpoint   = 203.0.113.20:51820$' "$NEXT_CONF" &&
    grep -q '^PrivateKey = CLIENTPRIVATEKEY=$' "$NEXT_CONF"; then pass "10: next config written from the running one"; else fail "10: next config: $(cat "$NEXT_CONF")"; fi
if grep -q "THIRDKEY=" "$HOME/.wireguard/production.conf"; then pass "10: running config untouched"; else fail "10: running config changed"; fi
if cmp -s "$HOME/.ssh/known_hosts" "$WORK/known_hosts.before"; then pass "10: known_hosts untouched"; else fail "10: known_hosts changed"; fi
if ssh-keygen -F 10.10.0.1 -f "$HOME/.ssh/known_hosts.production-next" | grep -q "$(cat "$STATE/hostkey-10.10.0.1")"; then pass "10: next host key in its own file"; else fail "10: no next host key"; fi
next_ca() { kubectl --kubeconfig "$1" config view --raw -o 'jsonpath={.clusters[?(@.name=="event-junkie-production-next")].cluster.certificate-authority-data}' | base64 -d; }
if [ "$(next_ca "$HOME/.kube/event-junkie-production-next")" = CA-next ]; then pass "10: next kubeconfig file"; else fail "10: next kubeconfig: $(next_ca "$HOME/.kube/event-junkie-production-next")"; fi
if [ "$(next_ca "$HOME/.kube/config")" = CA-next ] && [ "$(ca "$HOME/.kube/config")" = CA-new ]; then pass "10: both contexts in ~/.kube/config"; else fail "10: ~/.kube/config contexts"; fi

if [ "$FAILURES" -gt 0 ]; then
    echo "$FAILURES assertion(s) failed" >&2
    exit 1
fi
echo "all assertions passed"
