#!/usr/bin/env bash
#
# restore-drill.sh — run RESTORE_RUNBOOK.md §4, §5 and §7 end to end, and print the timings.
#
# Usage:
#   restore-drill.sh            # on the database node, as ops, with passwordless sudo
#   restore-drill.sh --force    # also on an environment that is not staging
#   scp -i ~/.ssh/id_ed25519_hetzner scripts/restore-drill.sh ops@10.10.1.1:/tmp/restore-drill.sh
#   ssh -i ~/.ssh/id_ed25519_hetzner ops@10.10.1.1 'bash /tmp/restore-drill.sh'
#
# Runs on the database node itself, needs the wal-g credential in /etc/wal-g/credentials.env, restores
# into /var/lib/postgresql/drill on port 5433, never touches the live PGDATA and reads only the bucket.
# **It does write to the live database**: §5 needs a scratch table in `public` for point-in-time
# recovery to recover past, and drops it again. The drill belongs on staging; --force is the door
# out. An assertion stands behind every claim the output makes, and each run overwrites BACKUPS.md §9.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
    --force) FORCE=true ;;
    '') FORCE=false ;;
    *)
        echo "restore-drill: unknown argument '$1'" >&2
        exit 2
        ;;
esac


# The SSH client forwards LC_CTYPE, the node has no matching locale, and every sudo call prints perl
# warnings otherwise.
export LC_ALL=C.UTF-8 LANG=C.UTF-8

BOOTSTRAP_ENV=/etc/event-junkie/bootstrap.env
PGVER="$(sudo sed -n 's/^POSTGRES_VERSION=//p' "${BOOTSTRAP_ENV}")"
ENVIRONMENT="$(sudo sed -n 's/^BACKUP_PREFIX=//p' "${BOOTSTRAP_ENV}")"
if [[ -z "${PGVER}" || -z "${ENVIRONMENT}" ]]; then
    echo "restore-drill: ${BOOTSTRAP_ENV} names no POSTGRES_VERSION or no BACKUP_PREFIX - is this a database node?" >&2
    exit 1
fi
if [[ "${ENVIRONMENT}" != staging && "${FORCE}" != true ]]; then
    echo "restore-drill: this node is ${ENVIRONMENT}, and the drill writes a scratch table to its live database" >&2
    echo "               pass --force to run it here anyway" >&2
    exit 1
fi

DRILL_DIR=/var/lib/postgresql/drill
PGBIN="/usr/lib/postgresql/${PGVER}/bin"
DRILL_LOG=/tmp/drill.log
START_SCRIPT=/var/lib/postgresql/drill-start.sh

# A guard, not a comment: every rm -rf below goes through this, and the live cluster lives in
# /var/lib/postgresql/18/main.
[[ "${DRILL_DIR}" == /var/lib/postgresql/drill ]] || { echo "refusing: unexpected drill directory" >&2; exit 1; }

pg() { sudo -u postgres "$@"; }
live() { pg psql -tAX -d events -c "$1"; }
scratch() { pg psql -tAX -h /tmp -p 5433 -d events -c "$1"; }
walg() { pg bash -c 'set -a; . /etc/wal-g/wal-g.env; . /etc/wal-g/credentials.env; set +a; exec "$@"' _ "$@"; }
banner() { printf '\n=== %s  (%s)\n' "$1" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"; }
elapsed() { echo "$(( $(date +%s) - $1 ))"; }

# Waits until the WAL segment current before a switch has reached the bucket; otherwise a PITR target
# in the newest segment is beyond the end of the archive and the restored cluster never promotes.
wait_for_archive() {
    local segment="$1" archived i
    for i in $(seq 1 90); do
        archived="$(live "select coalesce(last_archived_wal, '') from pg_stat_archiver")"
        if [[ -n "${archived}" && ! "${archived}" < "${segment}" ]]; then
            echo "archived ${archived} after ${i}s"
            return 0
        fi
        sleep 1
    done
    echo "archiving did not reach ${segment} within 90s" >&2
    return 1
}

switch_wal() {
    local segment
    segment="$(live "select pg_walfile_name(pg_current_wal_lsn())")"
    live "select pg_switch_wal()" >/dev/null
    wait_for_archive "${segment}"
}

# RESTORE_RUNBOOK.md §4.2, verbatim, installed on every run so the drill proves the published script.
install_start_script() {
    sudo tee "${START_SCRIPT}" >/dev/null <<'SCRIPT'
#!/bin/bash
set -euo pipefail
D=/var/lib/postgresql/drill
TARGET="${1:-}"

cat > "$D/postgresql.conf" <<CONF
data_directory = '$D'
hba_file = '$D/pg_hba.conf'
ident_file = '$D/pg_ident.conf'
port = 5433
listen_addresses = 'localhost'
unix_socket_directories = '/tmp'
archive_mode = off
restore_command = '/usr/local/bin/walg fetch %f %p'
CONF

if [[ -n "$TARGET" ]]; then
    cat >> "$D/postgresql.conf" <<CONF
recovery_target_time = '$TARGET'
recovery_target_action = 'promote'
CONF
fi

printf 'local all all trust\nhost all all 127.0.0.1/32 trust\n' > "$D/pg_hba.conf"
: > "$D/pg_ident.conf"
: > "$D/postgresql.auto.conf"
touch "$D/recovery.signal"
chmod 0700 "$D"
SCRIPT
    # Appended rather than written inside the quoted heredoc, because this is the one line that needs a
    # value from here.
    # shellcheck disable=SC2016 # `$D` belongs to the file being written.
    printf '\n%s/pg_ctl -D "$D" -l /tmp/drill.log -w -t 120 start\n' "${PGBIN}" |
        sudo tee -a "${START_SCRIPT}" >/dev/null
    sudo chown postgres:postgres "${START_SCRIPT}"
    sudo chmod 0755 "${START_SCRIPT}"
}

# `pg_ctl -w stop` returns as soon as the PID file is gone, and a backend can outlive that; a `rm -rf`
# that races it fails "Directory not empty". So wait for the processes. The bracket in `[p]ostgres`
# is load-bearing: `pgrep -f` matches its own command line too, so a plain pattern kills itself.
readonly SCRATCH_PATTERN="[p]ostgres -D ${DRILL_DIR}"
# `restore_command` runs `wal-g wal-fetch` as a child, which can still be writing after the
# postmaster is gone.
readonly FETCH_PATTERN="[w]al-g wal-fetch"

stop_scratch() {
    local i
    pg "${PGBIN}/pg_ctl" -D "${DRILL_DIR}" -w -t 120 stop || true
    for i in $(seq 1 30); do
        pgrep -f "${SCRATCH_PATTERN}" >/dev/null || pgrep -f "${FETCH_PATTERN}" >/dev/null || break
        sleep 1
    done
    if pgrep -f "${SCRATCH_PATTERN}" >/dev/null; then
        echo "a postmaster on ${DRILL_DIR} outlived pg_ctl - stopping it" >&2
        sudo pkill -TERM -f "${SCRATCH_PATTERN}" || true
        sleep 5
    fi
    if sudo ss -lnt | grep -q ':5433'; then
        echo "port 5433 is still bound - not deleting ${DRILL_DIR}" >&2
        return 1
    fi
    # Quiet retries; losing the race once is expected. The last attempt says why.
    for i in 1 2; do
        if sudo rm -rf "${DRILL_DIR}" 2>/dev/null; then
            break
        fi
        sleep 2
    done
    if [[ -e "${DRILL_DIR}" ]] && ! sudo rm -rf "${DRILL_DIR}"; then
        echo "could not delete ${DRILL_DIR}" >&2
        sudo ls -lA "${DRILL_DIR}" | head -20 >&2
        return 1
    fi
    sudo rm -f "${DRILL_LOG}"
}

banner 'Phase 0 - preflight'
pg /usr/local/bin/walg check
df -h /var/lib/postgresql | tail -1
DB_SIZE="$(live "select pg_size_pretty(pg_database_size('events'))")"
echo "events database size: ${DB_SIZE}"
# An `if` rather than `cmd && { exit 1; }`: under `set -e` the second form exits when the guard passes.
if sudo ss -lnt | grep -q ':5433'; then
    echo "port 5433 is already bound - a previous drill is still running" >&2
    exit 1
fi
if [[ -e "${DRILL_DIR}" ]]; then
    echo "${DRILL_DIR} already exists - clean it up first" >&2
    exit 1
fi
LIVE_BEFORE="$(live "select (select count(*) from events.event) || ' events, ' || (select count(*) from events.artist) || ' artists, ' || (select count(*) from events.venue) || ' venues'")"
echo "live: ${LIVE_BEFORE}"

banner 'Phase 1 - a base backup, and a marker row written after it'
# Dropped first, so a second run counts its own two marker rows rather than four.
live "drop table if exists public.restore_drill" >/dev/null
live "create table public.restore_drill (id serial primary key, note text, at timestamptz not null default now())" >/dev/null
live "insert into public.restore_drill (note) values ('written before the base backup')" >/dev/null
T0=$(date +%s)
sudo systemctl start walg-basebackup
BASE_BACKUP_SECONDS="$(elapsed "${T0}")"
echo "base backup: ${BASE_BACKUP_SECONDS}s"
live "insert into public.restore_drill (note) values ('written after the base backup')" >/dev/null
# The recovery target for §5: after both marker rows, before the drop. The pause keeps the target out
# of the same timestamp as the commit it must follow.
sleep 2
TARGET="$(live "select to_char(clock_timestamp() at time zone 'UTC', 'YYYY-MM-DD HH24:MI:SS.US+00')")"
echo "recovery target for phase 3: ${TARGET}"
switch_wal

banner 'Phase 2 - RESTORE_RUNBOOK.md §4, a full replay from the bucket alone'
T0=$(date +%s)
walg wal-g backup-fetch "${DRILL_DIR}" LATEST
FETCH_SECONDS="$(elapsed "${T0}")"
install_start_script
T0=$(date +%s)
pg "${START_SCRIPT}"
REPLAY_SECONDS="$(elapsed "${T0}")"
echo "backup-fetch: ${FETCH_SECONDS}s, replay and promote: ${REPLAY_SECONDS}s"
scratch "select (select count(*) from events.event) ev, (select count(*) from events.artist) ar, (select count(*) from events.venue) ve"
MARKERS="$(scratch "select count(*) from public.restore_drill")"
echo "marker rows in the restored copy: ${MARKERS}"
[[ "${MARKERS}" == '2' ]] || { echo "expected both marker rows - the row written after the base backup did not replay" >&2; exit 1; }
scratch "select id, note, at from public.restore_drill order by id"
stop_scratch

banner 'Phase 3 - RESTORE_RUNBOOK.md §5, point-in-time recovery past a destructive statement'
live "drop table public.restore_drill" >/dev/null
echo "dropped public.restore_drill on the live database"
switch_wal
walg wal-g backup-fetch "${DRILL_DIR}" LATEST
pg "${START_SCRIPT}" "${TARGET}"
# `pg_ctl -w start` returns when the server accepts connections, and a cluster still in recovery does;
# promotion happens when replay reaches the target, one wal-g fetch per segment. Poll for it.
IN_RECOVERY=t
for i in $(seq 1 120); do
    IN_RECOVERY="$(scratch 'select pg_is_in_recovery()')"
    if [[ "${IN_RECOVERY}" == 'f' ]]; then
        echo "promoted after ${i}s"
        break
    fi
    sleep 1
done
sudo grep -E 'recovery stopping|last completed transaction|archive recovery complete' "${DRILL_LOG}" || true
if [[ "${IN_RECOVERY}" != 'f' ]]; then
    echo "the restored cluster is still in recovery after 120s - promote did not fire" >&2
    scratch 'select pg_last_wal_replay_lsn(), pg_last_xact_replay_timestamp()' >&2 || true
    sudo tail -20 "${DRILL_LOG}" >&2
    exit 1
fi
RECOVERED="$(scratch "select count(*) from public.restore_drill")"
echo "marker rows recovered by PITR: ${RECOVERED}"
[[ "${RECOVERED}" == '2' ]] || { echo "PITR did not recover the dropped table" >&2; exit 1; }
STILL_DROPPED="$(live "select count(*) from pg_tables where schemaname = 'public' and tablename = 'restore_drill'")"
[[ "${STILL_DROPPED}" == '0' ]] || { echo "the live database was modified by the restore" >&2; exit 1; }
echo 'the live database is still without the table, as it should be'

banner 'Phase 4 - RESTORE_RUNBOOK.md §7, cleanup and proof that archiving is healthy'
stop_scratch
pg psql -xX -c 'select archived_count, failed_count, last_archived_wal, last_failed_wal from pg_stat_archiver'
sudo systemctl start walg-basebackup
pg /usr/local/bin/walg check
LIVE_AFTER="$(live "select (select count(*) from events.event) || ' events, ' || (select count(*) from events.artist) || ' artists, ' || (select count(*) from events.venue) || ' venues'")"
echo "live before: ${LIVE_BEFORE}"
echo "live after:  ${LIVE_AFTER}"

banner 'Result'
cat <<SUMMARY
database size            ${DB_SIZE}
base backup              ${BASE_BACKUP_SECONDS}s
backup-fetch             ${FETCH_SECONDS}s
replay and promote       ${REPLAY_SECONDS}s
restore to serving       $(( FETCH_SECONDS + REPLAY_SECONDS ))s
SUMMARY
echo 'both halves passed'
