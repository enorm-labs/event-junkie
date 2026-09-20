#!/usr/bin/env bash
#
# Bring up the Hetzner private network interface, because on a first apply cloud-init does not.
#
# The private NIC is attached by the API and its address arrives by DHCP. cloud-init renders it
# into /etc/netplan/50-cloud-init.yaml at the `init-local` stage, and when the network and the
# servers are created by the same apply the NIC attaches while the machine is already booting.
# Observed on production: both nodes came up with `enp7s0` DOWN and no stanza in the rendered
# netplan, while the API reported them attached:
#
#     production-k3s        10.0.1.10   mac 86:00:00:37:53:f5
#     production-postgres   10.0.1.20   mac 86:00:00:37:53:f8
#
# Staging never hit it, its rebuilds attaching to a network that has existed for days. It only
# happens on the apply nobody has done before, and disappears on every retry.
#
# Without the address the k3s node registers with the wrong `--node-ip`, PostgreSQL has nothing
# to bind, and a dedicated database node is unreachable by every route, its public firewall
# admitting nothing inbound by design. Idempotent: does nothing if the address is already there.

set -euo pipefail

# shellcheck source=/dev/null
source /etc/event-junkie/bootstrap.env

readonly DROPIN=/etc/netplan/60-private-net.yaml
# Hetzner assigns private NICs a MAC in this OUI (public ones are 92:00:…). Matched on that rather
# than on `enp7s0`, which is a property of the instance type.
readonly PRIVATE_MAC_PREFIX=86:00:00

if [ -z "${PRIVATE_IPV4:-}" ]; then
    echo "private-net: no PRIVATE_IPV4 in bootstrap.env — nothing to do"
    exit 0
fi

if ip -br addr show | grep -q "${PRIVATE_IPV4}/"; then
    echo "private-net: ${PRIVATE_IPV4} is already configured"
    exit 0
fi

# The NIC can appear seconds after boot. Bounded, so an absent interface fails the boot loudly.
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
# Written by private-net.sh: on a first apply cloud-init renders 50-cloud-init.yaml before this
# interface exists, and netplan merges both files.
network:
  version: 2
  ethernets:
    ${iface}:
      dhcp4: true
      # So a boot where the interface is absent does not block waiting for it.
      optional: true
EOF
chmod 0600 "${DROPIN}"

netplan apply

# Prove it: DHCP still has to answer.
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
