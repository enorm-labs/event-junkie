#!/usr/bin/env bash
#
# wal-g: continuous WAL archiving and daily base backups to Object Storage (#270). The volume (#460)
# is not a substitute — it survives the node, not a `DROP TABLE` or a bad migration.
#
# THIS FILE IS COMMENTED UNUSUALLY THINLY, ON PURPOSE. It is rendered into cloud-init, where it
# shares a hard 32 KiB `user_data` cap with every other script, and on the co-located node that cap
# binds — this script is what pushed it to 100%. The reasoning that would normally live here is in
# infra/AGENTS.md § "Backups"; read that before changing anything below, and re-measure after.
#
# Two things to know before reading:
#
#   * NO S3 CREDENTIAL LIVES HERE. `user_data` is state. The machine installs the mechanism; the
#     operator writes /etc/wal-g/credentials.env by hand (CLUSTER_BOOTSTRAP.md §8b). A rebuilt node
#     therefore has timers and no credential, which is what `walg check` exists to catch.
#   * wal-g ships only as a GitHub release and github.com publishes no AAAA record, so a node with
#     no public IPv4 cannot install it. This stops the boot instead of coming up without backups.

set -euo pipefail

# shellcheck source=/dev/null
source /etc/event-junkie/bootstrap.env

readonly CONF_DIR="/etc/postgresql/${POSTGRES_VERSION}/main"
readonly SERVICE="postgresql@${POSTGRES_VERSION}-main"
readonly ENV_FILE=/etc/wal-g/wal-g.env
readonly CRED_FILE=/etc/wal-g/credentials.env
readonly BIN=/usr/local/bin/wal-g

case "$(dpkg --print-architecture)" in
    amd64) ASSET="wal-g-pg-24.04-amd64" SHA="${WALG_SHA256_AMD64}" ;;
    arm64) ASSET="wal-g-pg-24.04-aarch64" SHA="${WALG_SHA256_ARM64}" ;;
    *)
        echo "backups: unsupported architecture $(dpkg --print-architecture)" >&2
        exit 1
        ;;
esac

if ! "${BIN}" --version 2>/dev/null | grep -qF "${WALG_VERSION}"; then
    TMP="$(mktemp -d)"
    URL="https://github.com/wal-g/wal-g/releases/download/${WALG_VERSION}/${ASSET}.tar.gz"

    if ! curl -fsSL --retry 3 "${URL}" -o "${TMP}/walg.tar.gz"; then
        echo "backups: could not fetch ${URL} - a node with no public IPv4 cannot; github.com is IPv4-only" >&2
        exit 1
    fi

    # Pinned in Terraform, not fetched from the host that served the tarball.
    echo "${SHA}  ${TMP}/walg.tar.gz" | sha256sum -c - >/dev/null

    tar -xzf "${TMP}/walg.tar.gz" -C "${TMP}"
    install -m 0755 "${TMP}/${ASSET}" "${BIN}"
    rm -rf "${TMP}"
fi

install -d -m 0750 -o root -g postgres /etc/wal-g

# Both environments share the bucket; BACKUP_PREFIX is derived from `environment` and is what keeps
# staging's retention sweep away from production's backups.
cat >"${ENV_FILE}" <<EOF
WALG_S3_PREFIX=s3://${BACKUP_BUCKET}/${BACKUP_PREFIX}
AWS_ENDPOINT=${BACKUP_ENDPOINT}
AWS_REGION=${BACKUP_REGION}
AWS_S3_FORCE_PATH_STYLE=true
WALG_COMPRESSION_METHOD=brotli
PGHOST=/var/run/postgresql
PGUSER=postgres
EOF
chmod 0640 "${ENV_FILE}"
chgrp postgres "${ENV_FILE}"

[[ -s "${CRED_FILE}" ]] || echo "backups: ${CRED_FILE} is absent - archiving fails until it is written" >&2

# One dispatcher, four verbs: `archive` is archive_command, `fetch` is the restore_command a
# recovering cluster needs, `backup` is the nightly base backup and retention sweep, `check` is the
# assertion that any of it actually happened. FIND_FULL and the 26-hour freshness bound are both
# load-bearing — AGENTS.md § "Backups" says why.
cat >/usr/local/bin/walg <<EOF
#!/bin/bash
set -euo pipefail
set -a
source ${ENV_FILE}
source ${CRED_FILE}
set +a

case "\${1:-}" in
archive)
    exec ${BIN} wal-push "\$2"
    ;;
fetch)
    exec ${BIN} wal-fetch "\$2" "\$3"
    ;;
backup)
    ${BIN} backup-push /var/lib/postgresql/${POSTGRES_VERSION}/main
    ${BIN} delete before FIND_FULL "\$(date -u -d "-${BACKUP_RETENTION_DAYS} days" +%Y-%m-%dT%H:%M:%SZ)" --confirm
    ;;
check)
    newest="\$(${BIN} backup-list | awk 'NR > 1 { print \$2 }' | sort | tail -1)"
    [[ -n "\${newest}" ]] || {
        echo "no base backup at all" >&2
        exit 1
    }

    age=\$((\$(date -u +%s) - \$(date -u -d "\${newest}" +%s)))
    ((age < 26 * 3600)) || {
        echo "newest base backup is \${age}s old" >&2
        exit 1
    }

    # A stalled archive_command fills pg_wal and eventually stops the database, on a 10 GB volume.
    use="\$(df --output=pcent /var/lib/postgresql | tail -1 | tr -dc '0-9')"
    ((use < 85)) || {
        echo "/var/lib/postgresql is \${use}% full" >&2
        exit 1
    }

    [[ -n "\${HEALTHCHECK_URL:-}" ]] && curl -fsS -m 10 --retry 3 "\${HEALTHCHECK_URL}" >/dev/null
    echo "ok: newest \${newest}, disk \${use}%"
    ;;
*)
    echo "usage: walg archive <path> | fetch <name> <dest> | backup | check" >&2
    exit 2
    ;;
esac
EOF
chmod 0755 /usr/local/bin/walg

# archive_mode needs a restart rather than a reload, which is why this is not in postgres.sh.
# archive_timeout bounds the RPO on an idle database to five minutes.
cat >"${CONF_DIR}/conf.d/20-event-junkie-walg.conf" <<EOF
archive_mode = on
archive_command = '/usr/local/bin/walg archive %p'
archive_timeout = 300
EOF

systemctl restart "${SERVICE}"

cat >/etc/systemd/system/walg-basebackup.service <<EOF
[Unit]
Description=wal-g base backup and retention sweep
Requires=${SERVICE}.service
After=${SERVICE}.service

[Service]
Type=oneshot
User=postgres
ExecStart=/usr/local/bin/walg backup
EOF

cat >/etc/systemd/system/walg-basebackup.timer <<'EOF'
[Unit]
Description=Daily wal-g base backup

[Timer]
OnCalendar=*-*-* 02:30:00
RandomizedDelaySec=30m
Persistent=true

[Install]
WantedBy=timers.target
EOF

cat >/etc/systemd/system/walg-check.service <<'EOF'
[Unit]
Description=Assert the backups are real and fresh

[Service]
Type=oneshot
User=postgres
ExecStart=/usr/local/bin/walg check
EOF

cat >/etc/systemd/system/walg-check.timer <<'EOF'
[Unit]
Description=Hourly backup freshness check

[Timer]
OnCalendar=hourly
RandomizedDelaySec=5m
Persistent=true

[Install]
WantedBy=timers.target
EOF

systemctl daemon-reload
systemctl enable --now walg-basebackup.timer walg-check.timer

echo "backups: wal-g ${WALG_VERSION} to s3://${BACKUP_BUCKET}/${BACKUP_PREFIX}, ${BACKUP_RETENTION_DAYS}-day retention"
