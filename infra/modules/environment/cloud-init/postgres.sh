#!/usr/bin/env bash
#
# PostgreSQL from the PGDG apt repository, listening on the private network, with PGDATA on a
# Hetzner volume that outlives the node. Roles, databases and extensions belong to the
# application's lifecycle (#261), and `wal-g` is in backups.sh (#270) because `archive_mode`
# needs a restart, so a cluster that already exists.
#
# THE VOLUME IS THE POINT (#460). `user_data` is a force-new attribute, so any edit under
# cloud-init/ rebuilds the node. This script adopts the cluster on the volume and never re-creates
# it: there is no `mkfs` here at all (the provider formats once, `format = "ext4"` in volume.tf),
# the seed step copies only into a volume with no cluster, and a cluster of another major version
# stops the boot. The package installs first and creates its cluster on the local disk, so
# /etc/postgresql/<v>/main is always Debian's own; the volume then mounts over /var/lib/postgresql
# and the shadowed local copy is thrown away unread.
#
# Unproven: on the dedicated node every fetch goes over IPv6. If apt.postgresql.org is unreachable
# that way, the fix is `postgres_public_ipv4` in variables.tf.

set -euo pipefail

# shellcheck source=/dev/null
source /etc/event-junkie/bootstrap.env

readonly KEYRING=/usr/share/keyrings/pgdg.asc
readonly CONF_DIR="/etc/postgresql/${POSTGRES_VERSION}/main"
readonly SERVICE="postgresql@${POSTGRES_VERSION}-main"
readonly DATA_ROOT=/var/lib/postgresql
readonly SEED_MOUNT=/mnt/pgdata
readonly FSTAB_MARKER="# event-junkie: PGDATA volume"

export DEBIAN_FRONTEND=noninteractive

if ! [[ -s "${KEYRING}" ]]; then
    curl -fsSL https://www.postgresql.org/media/keys/ACCC4CF8.asc -o "${KEYRING}"
fi

cat >/etc/apt/sources.list.d/pgdg.list <<EOF
deb [signed-by=${KEYRING}] https://apt.postgresql.org/pub/repos/apt $(lsb_release -cs)-pgdg main
EOF

apt-get update
apt-get install -y --no-install-recommends "postgresql-${POSTGRES_VERSION}"

# ---------------------------------------------------------------------------
# PGDATA onto the volume
# ---------------------------------------------------------------------------

# The attachment cannot happen until the server exists, so the device missing for the first few
# seconds is expected. Bounded at five minutes and loud, as in k3s.sh; on the co-located node this
# delays k3s, which is the right order.
for _ in $(seq 1 60); do
    [[ -b "${POSTGRES_DATA_DEVICE}" ]] && break
    sleep 5
done

if ! [[ -b "${POSTGRES_DATA_DEVICE}" ]]; then
    echo "postgres: ${POSTGRES_DATA_DEVICE} did not appear within 5 minutes" >&2
    exit 1
fi

# fstab keeps the stable /dev/disk/by-id path, findmnt reports the kernel name it resolves to.
DATA_DEVICE="$(readlink -f "${POSTGRES_DATA_DEVICE}")"
readonly DATA_DEVICE

if [[ "$(findmnt -no SOURCE "${DATA_ROOT}" || true)" == "${DATA_DEVICE}" ]]; then
    echo "postgres: ${DATA_ROOT} is already on the volume"
else
    # Whatever the package's cluster wrote is on the local disk, about to be shadowed, never read again.
    systemctl stop "${SERVICE}"

    install -d -m 0755 "${SEED_MOUNT}"
    mount "${DATA_DEVICE}" "${SEED_MOUNT}"

    if [[ -f "${SEED_MOUNT}/${POSTGRES_VERSION}/main/PG_VERSION" ]]; then
        echo "postgres: adopting the existing cluster on the volume"
    else
        # A different major version means POSTGRES_VERSION moved under a populated volume. Seeding beside
        # it would start the empty cluster, which presents as "the database lost its data". pg_upgrade,
        # by hand.
        existing="$(find "${SEED_MOUNT}" -maxdepth 3 -name PG_VERSION -printf '%h\n' | head -1 || true)"
        if [[ -n "${existing}" ]]; then
            echo "postgres: volume holds a cluster at ${existing}, but this node wants ${POSTGRES_VERSION} - refusing to seed beside it, see pg_upgrade" >&2
            exit 1
        fi

        echo "postgres: seeding the volume from the cluster the package just created"
        cp -a "${DATA_ROOT}/." "${SEED_MOUNT}/"
    fi

    umount "${SEED_MOUNT}"
    rmdir "${SEED_MOUNT}"

    # `nofail` is not a weakening: without it a missing volume drops the node into an emergency shell
    # it has no console for. The RequiresMountsFor drop-in below is what keeps PostgreSQL off the
    # local disk, failing the service rather than the boot.
    if ! grep -qF "${FSTAB_MARKER}" /etc/fstab; then
        cat >>/etc/fstab <<EOF

${FSTAB_MARKER}
${POSTGRES_DATA_DEVICE}  ${DATA_ROOT}  ext4  defaults,noatime,nofail,x-systemd.device-timeout=90s  0  2
EOF
    fi

    systemctl daemon-reload
    mount "${DATA_ROOT}"

    # mkfs leaves the root root-owned; this is postgres's home. PGDATA keeps the 0700 cp -a carried.
    chown postgres:postgres "${DATA_ROOT}"
    chmod 0755 "${DATA_ROOT}"
fi

# If the mount silently did not happen, PostgreSQL would start on the local disk and look healthy
# while serving an empty database. Refusing to start is better in every case.
if [[ "$(findmnt -no SOURCE "${DATA_ROOT}" || true)" != "${DATA_DEVICE}" ]]; then
    echo "postgres: ${DATA_ROOT} is not on ${POSTGRES_DATA_DEVICE} - refusing to start" >&2
    exit 1
fi

# The same check across a reboot, when this script does not run: RequiresMountsFor pulls in the
# .mount unit systemd generates from the fstab line and refuses to start PostgreSQL if it fails.
install -d -m 0755 "/etc/systemd/system/${SERVICE}.service.d"
cat >"/etc/systemd/system/${SERVICE}.service.d/10-event-junkie-volume.conf" <<EOF
[Unit]
RequiresMountsFor=${DATA_ROOT}
EOF

# ---------------------------------------------------------------------------
# Binding the private address must not depend on interface timing (#813)
# ---------------------------------------------------------------------------
#
# PostgreSQL binds what it can and carries on. Asked for 'localhost,<private ip>' before the
# address is assigned, it takes loopback only, logs one line and reports success:
#
#   LOG:  could not bind IPv4 address "10.1.1.10": Cannot assign requested address
#
# The unit stays `active (running)` and `pg_settings` reports the address the file says; only `ss`
# shows the truth, and every client gets `connection refused` (#813).
#
# `ip_nonlocal_bind` is the fix, and the ordering below is not a substitute: it lets the bind
# succeed against an address that is not up yet, removing the race rather than ordering around
# it. The race is real: needrestart runs with `$nrconf{restart} = 'a'`, so a libssl upgrade
# restarts systemd-networkd and PostgreSQL together, and PostgreSQL can win.
#
# The IPv6 line is inert today (the private network is IPv4 only) and keeps the guard whole.
cat >/etc/sysctl.d/99-event-junkie-postgres.conf <<'EOF'
# See postgres.sh: PostgreSQL must be able to bind the private address before the link is up.
net.ipv4.ip_nonlocal_bind = 1
net.ipv6.ip_nonlocal_bind = 1
EOF
sysctl -p /etc/sysctl.d/99-event-junkie-postgres.conf >/dev/null

# Ordering as well, so a cold boot does not rely on the sysctl: `network.target` means networking
# has started, `network-online.target` means an address is assigned, and
# systemd-networkd-wait-online is enabled on this node. This alone would not have prevented the
# outage: when needrestart restarts networkd, nothing guarantees network-online.target is
# deactivated first. The sysctl covers the restart.
cat >"/etc/systemd/system/${SERVICE}.service.d/20-event-junkie-network.conf" <<'EOF'
[Unit]
Wants=network-online.target
After=network-online.target
EOF
systemctl daemon-reload

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

# `include_dir = 'conf.d'` is already in Debian's postgresql.conf, so this survives a package
# upgrade rewriting the main file.
cat >"${CONF_DIR}/conf.d/10-event-junkie.conf" <<EOF
# Loopback plus the private address, and nothing else. The public interface is never bound, which
# is what actually keeps PostgreSQL off the internet — the Hetzner firewall does not filter private
# traffic, so it could not do this job even if it were asked to.
listen_addresses = 'localhost,${POSTGRES_LISTEN_IP}'
port = 5432
password_encryption = 'scram-sha-256'
EOF

# Appended, so the distribution's local/peer entries stay and `sudo -u postgres psql` keeps working.
#
# Two ranges, and the second is not redundant: on a dedicated node the connection arrives from the
# k3s node's private address, masqueraded by flannel (PRIVATE_SUBNET). Co-located (staging), pods
# connect to an address on the node itself and the source may still be the pod's own; POD_CIDR
# covers that, and is inert on a node pods cannot route to.
readonly HBA_MARKER="# event-junkie: private network"
if ! grep -qF "${HBA_MARKER}" "${CONF_DIR}/pg_hba.conf"; then
    cat >>"${CONF_DIR}/pg_hba.conf" <<EOF

${HBA_MARKER}
host    all             all             ${PRIVATE_SUBNET}            scram-sha-256
host    all             all             ${POD_CIDR}            scram-sha-256
EOF
fi

systemctl enable postgresql
systemctl restart "${SERVICE}"

echo "postgres: listening on ${POSTGRES_LISTEN_IP}:5432, PGDATA on ${POSTGRES_DATA_DEVICE}"
