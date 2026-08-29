#!/usr/bin/env bash
#
# PostgreSQL from the PGDG apt repository, listening on the private network, with PGDATA on a
# Hetzner volume that outlives the node.
#
# Scope stops at "a server is running and reachable from the k3s node". Roles, databases and
# extensions are deliberately elsewhere — #261 — because they belong to the application's lifecycle,
# not the machine's, and baking them in here would mean a rebuild silently re-creates credentials.
#
# `wal-g` is next door in backups.sh (#270) rather than here, and the split is not cosmetic: it
# turns on `archive_mode`, which needs a *restart* and therefore a cluster that already exists. It
# runs immediately after this script wherever PostgreSQL runs.
#
# THE VOLUME IS THE POINT (#460). `user_data` is a force-new attribute, so any edit under
# cloud-init/ rebuilds the node — and before the volume existed, that destroyed the database. This
# script therefore runs, by design, against a volume that already holds a cluster. It has to *adopt*
# that cluster and must never re-create it. Two things make that structural rather than careful:
#
#   * There is no `mkfs` here at all. The provider formats the volume once, at creation
#     (`format = "ext4"` in volume.tf). A destructive command that does not exist cannot be made
#     conditional wrongly.
#   * The seed step copies only into a volume with no cluster on it, and a cluster of an unexpected
#     major version stops the boot rather than being worked around.
#
# The trick that keeps this short: the package is installed first and creates its cluster on the
# local disk as usual, so /etc/postgresql/<v>/main is always complete and always Debian's own. The
# volume is then mounted *over* /var/lib/postgresql. Nothing overrides `data_directory`, and the
# shadowed local copy is thrown away unread.
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

# The attachment cannot happen until the server exists, which is after this boot has already
# started — so the device being missing for the first few seconds is expected, not a fault. Bounded
# at five minutes and loud, for the same reason k3s.sh waits rather than assumes. On the co-located
# node this also delays k3s, which runs next; that is the right order, because a k3s that came up
# first would only have to be told about the database afterwards anyway.
for _ in $(seq 1 60); do
    [[ -b "${POSTGRES_DATA_DEVICE}" ]] && break
    sleep 5
done

if ! [[ -b "${POSTGRES_DATA_DEVICE}" ]]; then
    echo "postgres: ${POSTGRES_DATA_DEVICE} did not appear within 5 minutes" >&2
    exit 1
fi

# fstab and findmnt disagree about spelling: fstab keeps the stable /dev/disk/by-id path, findmnt
# reports the kernel name it resolves to.
DATA_DEVICE="$(readlink -f "${POSTGRES_DATA_DEVICE}")"
readonly DATA_DEVICE

if [[ "$(findmnt -no SOURCE "${DATA_ROOT}" || true)" == "${DATA_DEVICE}" ]]; then
    echo "postgres: ${DATA_ROOT} is already on the volume"
else
    # Stop the cluster the package just started. Whatever it wrote is on the local disk and is about
    # to be shadowed; none of it is read again.
    systemctl stop "${SERVICE}"

    install -d -m 0755 "${SEED_MOUNT}"
    mount "${DATA_DEVICE}" "${SEED_MOUNT}"

    if [[ -f "${SEED_MOUNT}/${POSTGRES_VERSION}/main/PG_VERSION" ]]; then
        echo "postgres: adopting the existing cluster on the volume"
    else
        # A cluster of a *different* major version means POSTGRES_VERSION moved under a populated
        # volume. Seeding beside it would leave two clusters and start the empty one, which presents
        # as "the database lost its data" rather than as an error. pg_upgrade is the answer, by hand.
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

    # `nofail` is not a weakening. Without it a missing volume stalls local-fs.target and drops the
    # node into an emergency shell it has no console for — on the co-located node that takes k3s
    # with it. What actually keeps PostgreSQL off the local disk is the RequiresMountsFor drop-in
    # below, which fails the service rather than the boot.
    if ! grep -qF "${FSTAB_MARKER}" /etc/fstab; then
        cat >>/etc/fstab <<EOF

${FSTAB_MARKER}
${POSTGRES_DATA_DEVICE}  ${DATA_ROOT}  ext4  defaults,noatime,nofail,x-systemd.device-timeout=90s  0  2
EOF
    fi

    systemctl daemon-reload
    mount "${DATA_ROOT}"

    # mkfs leaves the filesystem root root-owned; this is postgres's home directory. PGDATA itself
    # keeps the 0700 that cp -a carried over.
    chown postgres:postgres "${DATA_ROOT}"
    chmod 0755 "${DATA_ROOT}"
fi

# The check that matters. If the mount silently did not happen, PostgreSQL would start on the local
# disk and look entirely healthy while serving an empty database — the failure that looks like
# success. Refusing to start is better than that in every case.
if [[ "$(findmnt -no SOURCE "${DATA_ROOT}" || true)" != "${DATA_DEVICE}" ]]; then
    echo "postgres: ${DATA_ROOT} is not on ${POSTGRES_DATA_DEVICE} - refusing to start" >&2
    exit 1
fi

# Enforces the same thing across a *reboot*, when this script does not run at all: RequiresMountsFor
# pulls in the .mount unit systemd generates from the fstab line and refuses to start PostgreSQL if
# it fails.
install -d -m 0755 "/etc/systemd/system/${SERVICE}.service.d"
cat >"/etc/systemd/system/${SERVICE}.service.d/10-event-junkie-volume.conf" <<EOF
[Unit]
RequiresMountsFor=${DATA_ROOT}
EOF

# ---------------------------------------------------------------------------
# Binding the private address must not depend on interface timing (#813)
# ---------------------------------------------------------------------------
#
# PostgreSQL binds what it can and carries on. Asked for 'localhost,<private ip>' when the private
# address is not assigned yet, it takes 127.0.0.1 and ::1, logs one line, and reports success:
#
#   LOG:  could not bind IPv4 address "10.1.1.10": Cannot assign requested address
#
# The unit stays `active (running)`, the configuration is intact, and `pg_settings` still reports
# the address it was asked for, because that is what the file says. Only `ss` shows the truth, so
# nothing on the node detects this state and every client gets `connection refused`. See #813.
#
# **`ip_nonlocal_bind` is the fix, and the ordering below is not a substitute for it.** It lets the
# bind succeed against an address that does not exist yet, which removes the race rather than
# ordering around it — the standard setting for VIP failover, and correct here for the same reason:
# the address is ours, it is simply not up yet. The race is not hypothetical. needrestart is
# configured `$nrconf{restart} = 'a'`, so a libssl upgrade restarts systemd-networkd and PostgreSQL
# together, and PostgreSQL can win.
#
# The IPv6 line is inert today, because the private network is IPv4 only. It is set so the guard
# stays true if that ever changes, rather than silently covering half the case.
cat >/etc/sysctl.d/99-event-junkie-postgres.conf <<'EOF'
# See postgres.sh: PostgreSQL must be able to bind the private address before the link is up.
net.ipv4.ip_nonlocal_bind = 1
net.ipv6.ip_nonlocal_bind = 1
EOF
sysctl -p /etc/sysctl.d/99-event-junkie-postgres.conf >/dev/null

# Ordering as well, because a normal boot should not rely on the sysctl above to paper over a unit
# that starts too early. `network.target` — all this unit had — means networking has been *started*;
# `network-online.target` means an address is *assigned*, and systemd-networkd-wait-online is
# already enabled on this node.
#
# **This alone would not have prevented the outage, which is why it is not the whole fix.** Ordering
# holds within a systemd transaction, and when needrestart restarts networkd there is no guarantee
# that network-online.target is deactivated first. So this makes a cold boot honest; the sysctl is
# what covers the restart.
cat >"/etc/systemd/system/${SERVICE}.service.d/20-event-junkie-network.conf" <<'EOF'
[Unit]
Wants=network-online.target
After=network-online.target
EOF
systemctl daemon-reload

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

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
systemctl restart "${SERVICE}"

echo "postgres: listening on ${POSTGRES_LISTEN_IP}:5432, PGDATA on ${POSTGRES_DATA_DEVICE}"
