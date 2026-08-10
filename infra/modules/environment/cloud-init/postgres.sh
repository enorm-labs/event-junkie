#!/usr/bin/env bash
#
# PostgreSQL from the PGDG apt repository, listening on the private network.
#
# Scope stops at "a server is running and reachable from the k3s node". Roles, databases, extensions
# and `wal-g` are deliberately elsewhere — #261 and #270 — because they belong to the application's
# lifecycle, not the machine's, and baking them in here would mean a rebuild silently re-creates
# credentials.
#
# NOTE ON THE ONE UNPROVEN ASSUMPTION: on the dedicated node this runs with no public IPv4, so every
# fetch below goes over IPv6. If apt.postgresql.org turns out to be unreachable that way, this
# script fails loudly in the cloud-init log and the fix is one variable — see
# `postgres_public_ipv4` in variables.tf.

set -euo pipefail

# shellcheck source=/dev/null
source /etc/event-junkie/bootstrap.env

readonly KEYRING=/usr/share/keyrings/pgdg.asc
readonly CONF_DIR="/etc/postgresql/${POSTGRES_VERSION}/main"

export DEBIAN_FRONTEND=noninteractive

if ! [[ -s "${KEYRING}" ]]; then
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc -o "${KEYRING}"
fi

cat >/etc/apt/sources.list.d/pgdg.list <<EOF
deb [signed-by=${KEYRING}] https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main
EOF

apt-get update
apt-get install -y --no-install-recommends "postgresql-${POSTGRES_VERSION}"

# `include_dir = 'conf.d'` is already in Debian's postgresql.conf, so this is additive and survives
# a package upgrade rewriting the main file.
cat >"${CONF_DIR}/conf.d/10-event-junkie.conf" <<EOF
# Loopback plus the private address, and nothing else. The public interface is never bound, which
# is what actually keeps PostgreSQL off the internet — the Hetzner firewall does not filter private
# traffic, so it could not do this job even if it were asked to.
listen_addresses = 'localhost,${POSTGRES_LISTEN_IP}'
port = 5432
password_encryption = 'scram-sha-256'
EOF

# Appended rather than rewritten, so the distribution's local/peer entries stay intact and `sudo -u
# postgres psql` keeps working for operators.
#
# Two ranges, and the second is not redundant. On a dedicated node the connection arrives from the
# k3s node's private address, masqueraded by flannel on the way out — covered by PRIVATE_SUBNET.
# When PostgreSQL is co-located (staging), pods connect to an address on the node itself, and
# whether that gets masqueraded is not something to bet a boot on: the source may well still be the
# pod's own address. POD_CIDR covers that case, and is inert on a node pods cannot route to anyway.
readonly HBA_MARKER="# event-junkie: private network"
if ! grep -qF "${HBA_MARKER}" "${CONF_DIR}/pg_hba.conf"; then
    cat >>"${CONF_DIR}/pg_hba.conf" <<EOF

${HBA_MARKER}
host    all             all             ${PRIVATE_SUBNET}            scram-sha-256
host    all             all             ${POD_CIDR}            scram-sha-256
EOF
fi

systemctl enable postgresql
systemctl restart "postgresql@${POSTGRES_VERSION}-main"

echo "postgres: listening on ${POSTGRES_LISTEN_IP}:5432"
