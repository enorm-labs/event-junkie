#!/usr/bin/env bash
#
# WireGuard on the host, not in the cluster: emergency access must not live inside the thing that
# might be broken (PLATFORM_SETUP.md §8a). The server keypair is generated here on first boot and
# never leaves the node, which is why this is a script and not a templated config: a private key
# passed as a variable would be written to the OpenTofu state, which lives in Object Storage.
# Peers arrive as /etc/wireguard/peers.conf, public keys only.

set -euo pipefail

# shellcheck source=/dev/null
source /etc/event-junkie/bootstrap.env

readonly WG_DIR=/etc/wireguard
readonly PRIVATE_KEY="${WG_DIR}/private.key"
readonly PUBLIC_KEY="${WG_DIR}/public.key"

export DEBIAN_FRONTEND=noninteractive
apt-get install -y --no-install-recommends wireguard

install -d -m 0700 "${WG_DIR}"
umask 077

if [[ ! -s "${PRIVATE_KEY}" ]]; then
    wg genkey >"${PRIVATE_KEY}"
    wg pubkey <"${PRIVATE_KEY}" >"${PUBLIC_KEY}"
fi
chmod 0600 "${PRIVATE_KEY}"
chmod 0644 "${PUBLIC_KEY}"

{
    echo "[Interface]"
    echo "Address = ${WIREGUARD_ADDRESS}"
    echo "ListenPort = ${WIREGUARD_PORT}"
    echo "PrivateKey = $(cat "${PRIVATE_KEY}")"
    echo
    cat "${WG_DIR}/peers.conf"
} >"${WG_DIR}/wg0.conf"
chmod 0600 "${WG_DIR}/wg0.conf"

# Needed once a peer's AllowedIPs covers anything beyond the node itself; harmless otherwise.
cat >/etc/sysctl.d/99-event-junkie-forwarding.conf <<'EOF'
net.ipv4.ip_forward = 1
net.ipv6.conf.all.forwarding = 1
EOF
sysctl --system >/dev/null

systemctl enable wg-quick@wg0
systemctl restart wg-quick@wg0

# The one value a human collects from the node by hand (`infra/README.md`); printed here so it is
# in the cloud-init log if SSH is the thing that broke.
echo "wireguard: server public key is $(cat "${PUBLIC_KEY}")"
