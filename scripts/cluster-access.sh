#!/usr/bin/env bash
#
# cluster-access.sh — bring this Mac's tunnel, host keys and kubeconfig up to date with a cluster (#4146).
#
# Usage:
#   scripts/cluster-access.sh <staging|production> [--server-key <key>] [--merge]
#   scripts/cluster-access.sh <staging|production> --next --endpoint <ip> [--db-node <ip>] [--server-key <key>] [--merge]
#
#   --server-key <key>   the node's WireGuard public key, when neither the tunnel nor the public
#                        IPv4 can fetch it (KeePass #18 keeps it beside the client config)
#   --merge              also add the context to ~/.kube/config when it is not there yet
#   --next               the second pair of #4128, built beside the running one: its own client config
#                        `<env>-next.conf`, known_hosts file and kube context `event-junkie-<env>-next`
#   --endpoint <ip>      the new node's temporary IPv4; writes it into `<env>-next.conf`
#   --db-node <ip>       the new database node's private address, when there is one
#
# After a rebuild the node has a new WireGuard server key, new SSH host keys and a new cluster CA, and
# every Mac holds the old ones. This replaces each one that changed and leaves the rest: a second run
# changes nothing. It writes only on this Mac, never on the node or in the cluster.
#
# It needs a client config that already exists, `~/.wireguard/<env>.conf`, because a Mac without one
# has no peer on the node. Adding a peer is a hand step: CLUSTER_BOOTSTRAP.md § Adding a Mac to the
# tunnel.
#
# Both nodes are the same address in the tunnel, so only one of `<env>.conf` and `<env>-next.conf`
# is up at a time: this takes the other one down first, and says so. The first `--next` run copies
# the interface of `<env>.conf` and needs `--server-key`, which `rebuild-node.sh` reads through the
# running node.
#
# `KUBECONFIG` is unset and every `kubectl config` call names its file: during the production
# rebuild an exported `KUBECONFIG` sent three deletes meant for ~/.kube/config to the new file.
#
# Requires: wg-quick (with sudo), ssh, ssh-keyscan, ssh-keygen, kubectl, ping.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    echo "cluster-access: $*" >&2
    exit 1
}

ENV="${1:-}"
case "$ENV" in
    staging) TUNNEL_IP=10.10.1.1 ;;
    production) TUNNEL_IP=10.10.0.1 ;;
    *) die "expected staging or production, got '${ENV}'" ;;
esac
shift
SERVER_KEY=""
MERGE=0
NEXT=0
ENDPOINT=""
DB_NODE_ARG=""
while [ $# -gt 0 ]; do
    case "$1" in
        --next)
            NEXT=1
            shift
            ;;
        --endpoint | --db-node)
            [ $# -ge 2 ] || die "$1 needs an address"
            if [ "$1" = --endpoint ]; then ENDPOINT="$2"; else DB_NODE_ARG="$2"; fi
            shift 2
            ;;
        --server-key)
            [ $# -ge 2 ] || die "--server-key needs a value"
            SERVER_KEY="$2"
            shift 2
            ;;
        --merge)
            MERGE=1
            shift
            ;;
        *) die "unknown argument '$1'" ;;
    esac
done

unset KUBECONFIG
umask 077

[ "$NEXT" = 1 ] || [ -z "$ENDPOINT$DB_NODE_ARG" ] || die "--endpoint and --db-node go with --next"
IFACE="$ENV"
OTHER="$ENV-next"
KNOWN_HOSTS="$HOME/.ssh/known_hosts"
if [ "$NEXT" = 1 ]; then
    IFACE="$ENV-next"
    OTHER="$ENV"
    KNOWN_HOSTS="$HOME/.ssh/known_hosts.$IFACE"
fi
NAME="event-junkie-$IFACE"
WG_DIR="${EJ_WG_DIR:-$HOME/.wireguard}"
CONF="$WG_DIR/$IFACE.conf"
SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
KUBE_FILE="$HOME/.kube/$NAME"
KUBE_CONFIG="$HOME/.kube/config"
STAMP="$(date +%Y%m%d-%H%M%S)"
# Production's database node has no tunnel address and no public one; it is reached through the k3s node.
DB_NODE=""
[ "$ENV" = production ] && DB_NODE=10.0.1.20
[ "$NEXT" = 0 ] || DB_NODE="$DB_NODE_ARG"

if [ "$NEXT" = 1 ] && [ ! -f "$CONF" ]; then
    BASE="$WG_DIR/$ENV.conf"
    [ -f "$BASE" ] || die "no $BASE to copy the interface from: CLUSTER_BOOTSTRAP.md § Adding a Mac to the tunnel"
    [ -n "$SERVER_KEY" ] && [ -n "$ENDPOINT" ] || die "the first --next run needs --server-key and --endpoint"
    port="$(awk '/^[[:space:]]*Endpoint/ { sub(/.*:/, ""); print; exit }' "$BASE")"
    awk -v key="$SERVER_KEY" -v ep="$ENDPOINT:${port:-51820}" '
        /^\[/ { peer = ($0 ~ /^\[Peer\]/) }
        peer && /^[[:space:]]*PublicKey/ { print "PublicKey  = " key; next }
        peer && /^[[:space:]]*Endpoint/ { print "Endpoint   = " ep; next }
        { print }
    ' "$BASE" >"$CONF"
    echo "client config: $CONF written from $BASE, endpoint $ENDPOINT"
fi
[ -f "$CONF" ] || die "no $CONF. This Mac has no peer on the $ENV node yet: CLUSTER_BOOTSTRAP.md § Adding a Mac to the tunnel"

if [ -n "$ENDPOINT" ] && ! grep -Eq "^[[:space:]]*Endpoint[[:space:]]*=[[:space:]]*$ENDPOINT:" "$CONF"; then
    cp -p "$CONF" "$CONF.bak.$STAMP"
    awk -v ip="$ENDPOINT" '/^[[:space:]]*Endpoint/ { n = split($0, a, ":"); print "Endpoint   = " ip ":" a[n]; next } { print }' "$CONF" >"$CONF.new"
    mv "$CONF.new" "$CONF"
    echo "client config: endpoint set to $ENDPOINT, old file kept as $CONF.bak.$STAMP"
fi

PUBLIC_IP="$(awk '/^[[:space:]]*Endpoint/ { sub(/^[^=]*=[[:space:]]*/, ""); sub(/:[0-9]+[[:space:]]*$/, ""); print; exit }' "$CONF")"
[ -n "$PUBLIC_IP" ] || die "$CONF has no Endpoint line"

node_ssh() {
    local host="$1"
    shift
    ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=5 -o UserKnownHostsFile="$KNOWN_HOSTS" "ops@$host" "$@"
}

# $1 is the interface, `<env>` or `<env>-next`; wg-quick names it after the config file.
iface_up() {
    if [ -n "${EJ_WG_RUN_DIR:-}" ] || [ "$(uname -s)" = Darwin ]; then
        [ -e "${EJ_WG_RUN_DIR:-/var/run/wireguard}/$1.name" ]
    else
        ip link show "$1" >/dev/null 2>&1
    fi
}
tunnel_up() { iface_up "$IFACE"; }

node_answers() {
    local _
    for _ in $(seq 1 "${EJ_PING_TRIES:-10}"); do
        ping -c 1 -W 2 "$TUNNEL_IP" >/dev/null 2>&1 && return 0
        sleep 1
    done
    return 1
}

# The key alone, without the host field: a hashed known_hosts line names the host differently.
stored_host_key() { { ssh-keygen -F "$1" -f "$KNOWN_HOSTS" 2>/dev/null || true; } | awk '!/^#/ && $2 == "ssh-ed25519" { print $3; exit }'; }

# $1 is the address, $2 the `host ssh-ed25519 <key>` line the node offers now, empty when it did not answer.
refresh_host_key() {
    local host="$1" offered="$2" stored
    if [ -z "$offered" ]; then
        echo "host key $host: no answer, left as it is"
        return 0
    fi
    stored="$(stored_host_key "$host")"
    if [ "$stored" = "$(awk '{ print $3 }' <<<"$offered")" ]; then
        echo "host key $host: unchanged"
        return 0
    fi
    install -d -m 0700 "$HOME/.ssh"
    if [ -n "$stored" ]; then
        ssh-keygen -R "$host" -f "$KNOWN_HOSTS" >/dev/null 2>&1
        echo "host key $host: replaced"
    else
        echo "host key $host: added"
    fi
    echo "$offered" >>"$KNOWN_HOSTS"
}

scan() { { ssh-keyscan -T 5 -t ed25519 "$1" 2>/dev/null || true; } | awk '!/^#/ { print; exit }'; }

# The other tunnel answers on the same address, so a key or a host key read through it would be the wrong node's.
if iface_up "$OTHER"; then
    echo "tunnel $OTHER: up on the same address, taking it down first"
    sudo wg-quick down "$WG_DIR/$OTHER.conf"
fi

# --- 1. The server key ----------------------------------------------------------------------------

BROUGHT_UP=0
TUNNEL_ANSWERS=0
if [ -z "$SERVER_KEY" ] && ! tunnel_up; then
    echo "tunnel $ENV: down, bringing it up with the current config to read the server key"
    sudo wg-quick up "$CONF"
    BROUGHT_UP=1
fi
if [ -z "$SERVER_KEY" ] && node_answers; then
    TUNNEL_ANSWERS=1
    refresh_host_key "$TUNNEL_IP" "$(scan "$TUNNEL_IP")"
    SERVER_KEY="$(node_ssh "$TUNNEL_IP" sudo cat /etc/wireguard/public.key 2>/dev/null || true)"
    [ -n "$SERVER_KEY" ] && echo "server key: read over the tunnel"
fi
if [ -z "$SERVER_KEY" ]; then
    # Open to this Mac only while `admin_cidrs` admits its address.
    offered="$(scan "$PUBLIC_IP")"
    if [ -n "$offered" ]; then
        refresh_host_key "$PUBLIC_IP" "$offered"
        SERVER_KEY="$(node_ssh "$PUBLIC_IP" sudo cat /etc/wireguard/public.key 2>/dev/null || true)"
        [ -n "$SERVER_KEY" ] && echo "server key: read from $PUBLIC_IP"
    fi
fi
if [ -z "$SERVER_KEY" ]; then
    # Leave the tunnel as it was found.
    [ "$BROUGHT_UP" = 0 ] || sudo wg-quick down "$CONF"
    # BatchMode cannot ask for a passphrase, so a key outside the agent fails like a closed port.
    [ "$TUNNEL_ANSWERS" = 0 ] || die "cannot read the server key: $TUNNEL_IP answers over the tunnel, but SSH as ops fails.
Load the key into the agent with 'ssh-add $SSH_KEY', then run this again."
    die "cannot read the server key: the tunnel does not answer, and $PUBLIC_IP refuses SSH from here.
Pass --server-key <key> from KeePass #18, or read /etc/wireguard/public.key from a Mac whose tunnel works."
fi
SERVER_KEY="$(tr -d '[:space:]' <<<"$SERVER_KEY")"

# --- 2. The client config and the tunnel ---------------------------------------------------------

current="$(awk '/^\[Peer\]/ { p = 1 } p && /^[[:space:]]*PublicKey/ { sub(/^[^=]*=[[:space:]]*/, ""); print; exit }' "$CONF")"
if [ "$current" = "$SERVER_KEY" ]; then
    echo "client config: server key unchanged"
    if ! tunnel_up; then
        sudo wg-quick up "$CONF"
    fi
else
    cp -p "$CONF" "$CONF.bak.$STAMP"
    awk -v key="$SERVER_KEY" '
        /^\[/ { peer = ($0 ~ /^\[Peer\]/) }
        peer && /^[[:space:]]*PublicKey/ && !done { print "PublicKey  = " key; done = 1; next }
        { print }
    ' "$CONF" >"$CONF.new"
    mv "$CONF.new" "$CONF"
    echo "client config: server key replaced, old file kept as $CONF.bak.$STAMP"
    if tunnel_up; then
        echo "tunnel $ENV: restarting with the new key"
        sudo wg-quick down "$CONF"
    fi
    sudo wg-quick up "$CONF"
fi
node_answers || die "tunnel $ENV is up but $TUNNEL_IP does not answer. Check 'sudo wg show' for a handshake.
No handshake usually means outbound UDP/51820 is blocked, or the server key is wrong."
echo "tunnel $ENV: up ($TUNNEL_IP answers)"

# --- 3. Host keys ----------------------------------------------------------------------------------

refresh_host_key "$TUNNEL_IP" "$(scan "$TUNNEL_IP")"
refresh_host_key "$PUBLIC_IP" "$(scan "$PUBLIC_IP")"
if [ -n "$DB_NODE" ]; then
    refresh_host_key "$DB_NODE" "$({ node_ssh "$TUNNEL_IP" ssh-keyscan -T 5 -t ed25519 "$DB_NODE" 2>/dev/null || true; } | awk '!/^#/ { print; exit }')"
fi

# --- 4. The kubeconfig -----------------------------------------------------------------------------

install -d -m 0700 "$HOME/.kube"
fetched="$(node_ssh "$TUNNEL_IP" sudo cat /etc/rancher/k3s/k3s.yaml)" || die "cannot read k3s.yaml from $TUNNEL_IP"
# k3s names its cluster, user and context `default`, and serves on the loopback address.
sed -e "s|https://127.0.0.1:6443|https://$TUNNEL_IP:6443|" -e "s|: default\$|: $NAME|" <<<"$fetched" >"$KUBE_FILE.new"
if [ -f "$KUBE_FILE" ] && cmp -s "$KUBE_FILE" "$KUBE_FILE.new"; then
    rm -f "$KUBE_FILE.new"
    echo "kubeconfig $KUBE_FILE: unchanged"
else
    [ -f "$KUBE_FILE" ] && cp -p "$KUBE_FILE" "$KUBE_FILE.bak.$STAMP"
    mv "$KUBE_FILE.new" "$KUBE_FILE"
    chmod 600 "$KUBE_FILE"
    echo "kubeconfig $KUBE_FILE: written"
fi

# A digest of the server, CA and client credentials of one context, so nothing secret is printed.
entry_digest() {
    kubectl --kubeconfig "$1" config view --raw --minify --flatten --context "$NAME" \
        -o 'jsonpath={.clusters[0].cluster.server} {.clusters[0].cluster.certificate-authority-data} {.users[0].user.client-certificate-data} {.users[0].user.client-key-data}' \
        2>/dev/null | cksum
}
has_context() { kubectl --kubeconfig "$1" config get-contexts -o name 2>/dev/null | grep -x "$NAME" >/dev/null; }

if [ -f "$KUBE_CONFIG" ] && has_context "$KUBE_CONFIG"; then
    if [ "$(entry_digest "$KUBE_CONFIG")" = "$(entry_digest "$KUBE_FILE")" ]; then
        echo "kubeconfig $KUBE_CONFIG: $NAME unchanged"
    else
        cp -p "$KUBE_CONFIG" "$KUBE_CONFIG.bak.$STAMP"
        kubectl --kubeconfig "$KUBE_CONFIG" config delete-context "$NAME" >/dev/null
        kubectl --kubeconfig "$KUBE_CONFIG" config delete-cluster "$NAME" >/dev/null
        kubectl --kubeconfig "$KUBE_CONFIG" config delete-user "$NAME" >/dev/null
        KUBECONFIG="$KUBE_CONFIG:$KUBE_FILE" kubectl config view --flatten >"$KUBE_CONFIG.new"
        mv "$KUBE_CONFIG.new" "$KUBE_CONFIG"
        chmod 600 "$KUBE_CONFIG"
        echo "kubeconfig $KUBE_CONFIG: $NAME replaced, old file kept as $KUBE_CONFIG.bak.$STAMP"
    fi
elif [ "$MERGE" = 1 ]; then
    if [ -f "$KUBE_CONFIG" ]; then
        cp -p "$KUBE_CONFIG" "$KUBE_CONFIG.bak.$STAMP"
        KUBECONFIG="$KUBE_CONFIG:$KUBE_FILE" kubectl config view --flatten >"$KUBE_CONFIG.new"
        mv "$KUBE_CONFIG.new" "$KUBE_CONFIG"
    else
        cp "$KUBE_FILE" "$KUBE_CONFIG"
    fi
    chmod 600 "$KUBE_CONFIG"
    echo "kubeconfig $KUBE_CONFIG: $NAME added"
else
    echo "kubeconfig $KUBE_CONFIG: has no $NAME, left as it is (--merge adds it)"
fi

# --- 5. Proof --------------------------------------------------------------------------------------

kubectl --kubeconfig "$KUBE_FILE" --context "$NAME" get nodes
