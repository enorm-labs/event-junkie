#!/usr/bin/env bash
#
# Bring up the Hetzner private network interface, because on a first apply cloud-init does not.
#
# The private NIC is attached to the server by the API, and its address arrives by DHCP. cloud-init
# normally renders it into /etc/netplan/50-cloud-init.yaml from instance metadata — but that config
# is written at the `init-local` stage, which is very early, and the interface is not always there
# yet.
#
# **It is not there when the network and the servers are created by the same apply**, which is
# exactly what a first apply of an environment does: `hcloud_network`, `hcloud_network_subnet` and
# both servers land within seconds of each other, so the NIC attaches while the machine is already
# booting. Observed on production 2026-08-21 — both nodes came up with `enp7s0` DOWN and no stanza
# in the rendered netplan, while the API reported them attached with addresses assigned:
#
#     production-k3s        10.0.1.10   mac 86:00:00:37:53:f5
#     production-postgres   10.0.1.20   mac 86:00:00:37:53:f8
#
# Staging never hit it: its rebuilds attach to a network that has existed for days, so the NIC is
# present before the machine boots. **That is what makes this worth a script rather than a fix by
# hand — it only happens on the apply nobody has done before, and it disappears on every retry.**
#
# What it looks like when missing: no private address anywhere, so the k3s node registers with the
# wrong `--node-ip`, PostgreSQL has nothing to bind to, and a dedicated database node becomes
# unreachable by every route at once — its private path is dead and its public firewall admits
# nothing inbound, by design.
#
# Idempotent and safe to re-run: if the interface already carries the expected address it does
# nothing at all.

set -euo pipefail

# shellcheck source=/dev/null
source /etc/event-junkie/bootstrap.env

readonly DROPIN=/etc/netplan/60-private-net.yaml
# Hetzner assigns private NICs a MAC in this OUI; the public one is 92:00:… on the same machines.
# Matching on that rather than on `enp7s0` because the interface name is a property of the instance
# type, and this module already runs on more than one.
readonly PRIVATE_MAC_PREFIX=86:00:00

if [ -z "${PRIVATE_IPV4:-}" ]; then
    echo "private-net: no PRIVATE_IPV4 in bootstrap.env — nothing to do"
    exit 0
fi

if ip -br addr show | grep -q "${PRIVATE_IPV4}/"; then
    echo "private-net: ${PRIVATE_IPV4} is already configured"
    exit 0
fi

# The NIC can appear seconds after boot. Wait for it rather than racing it — and bound the wait, so
# a genuinely absent interface fails the boot loudly instead of hanging cloud-init forever.
iface=
for _ in $(seq 1 30); do
    iface=$(ip -o link show |
        awk -v prefix="${PRIVATE_MAC_PREFIX}" '$0 ~ "link/ether "prefix {print substr($2, 1, length($2)-1); exit}')
    [ -n "${iface}" ] && break
    sleep 2
done

if [ -z "${iface}" ]; then
    echo "private-net: no interface with a ${PRIVATE_MAC_PREFIX} MAC after 60s" >&2
    ip -br link >&2
    exit 1
fi

echo "private-net: configuring ${iface} for DHCP"

cat > "${DROPIN}" <<EOF
# Written by private-net.sh. cloud-init renders 50-cloud-init.yaml before this interface exists on
# a first apply, so its stanza is missing there; netplan merges both files and this one supplies it.
network:
  version: 2
  ethernets:
    ${iface}:
      dhcp4: true
      # So a boot where the interface is genuinely absent does not block on waiting for it.
      optional: true
EOF
chmod 0600 "${DROPIN}"

netplan apply

# Prove it, rather than assuming netplan succeeded: DHCP still has to answer.
for _ in $(seq 1 30); do
    if ip -br addr show "${iface}" | grep -q "${PRIVATE_IPV4}/"; then
        echo "private-net: ${iface} has ${PRIVATE_IPV4}"
        exit 0
    fi
    sleep 2
done

echo "private-net: ${iface} came up but never received ${PRIVATE_IPV4}" >&2
ip -br addr show "${iface}" >&2
exit 1
