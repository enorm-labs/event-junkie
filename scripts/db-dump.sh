#!/usr/bin/env bash
#
# db-dump.sh — copy an environment's database to this Mac with pg_dump, checked, before a migration or a cutover.
#
# Usage:
#   scripts/db-dump.sh <staging|production>                       # dump, verify, copy to ~/.event-junkie/dumps/<env>/
#   scripts/db-dump.sh <staging|production> --check <file.counts> # count the rows again and compare with a dump's counts
#
# Requires: the WireGuard tunnel (scripts/ej.sh up <env>), ssh with the key in ~/.ssh/config (CLUSTER_ACCESS.md), shasum.
#
# pg_dump runs on the database node, as postgres, so no password travels and the client matches the server: a local
# pg_dump 17 refuses an 18 server. The dump is custom format (`pg_restore` reads it), written to /var/tmp on the node,
# listed with pg_restore, copied over SSH, compared by SHA-256 and then removed from the node. Beside it go `.sha256`
# and `.counts`, the exact row count of every table, which `--check` compares after the cutover.
#
# The host is `database.host` in the cluster's helm-release.yaml, as in ej.sh, so a switch PR moves the dump too.
# A production dump holds personal data (accounts). The privacy notice promises deletion of backups after 30 days,
# so every run deletes this script's own dumps of that environment older than 30 days. Keep FileVault on.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    printf 'db-dump: %s\n' "$1" >&2
    exit 1
}

ENV="${1:-}"
case "$ENV" in
    staging | production) ;;
    *) die "expected staging or production, got '${ENV}'" ;;
esac
shift
CHECK=""
case "${1:-}" in
    --check)
        CHECK="${2:-}"
        [ -f "$CHECK" ] || die "--check needs a .counts file, got '${CHECK}'"
        ;;
    '') ;;
    *) die "unknown argument '$1'" ;;
esac

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTERS_DIR="${EJ_CLUSTERS_DIR:-$REPO/deploy/clusters}"
DUMP_DIR="${EJ_DUMP_DIR:-$HOME/.event-junkie/dumps}/$ENV"
SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
KEEP_DAYS=30

DB_HOST="$(awk '/^    database:/ { d = 1 } d && /^      host:/ { gsub(/"/, "", $2); print $2; exit }' \
    "$CLUSTERS_DIR/$ENV/helm-release.yaml")"
[ -n "$DB_HOST" ] || die "no database.host in $CLUSTERS_DIR/$ENV/helm-release.yaml"

base_ssh=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=5)
# Staging's database is on its k3s node; production's is a separate node behind the k3s node. SSH forwards
# LC_CTYPE, the node has no such locale, and every PostgreSQL wrapper prints perl warnings otherwise.
db_ssh() {
    if [ "$ENV" = production ]; then
        "${base_ssh[@]}" -J ops@10.10.0.1 "ops@$DB_HOST" "export LC_ALL=C.UTF-8; $*"
    else
        "${base_ssh[@]}" ops@10.10.1.1 "export LC_ALL=C.UTF-8; $*"
    fi
}

# One table per line, `schema.table<TAB>rows`, sorted, exact counts rather than the statistics.
count_rows() {
    db_ssh 'sudo -u postgres psql -X -q -tA -F "	" -v ON_ERROR_STOP=1 -d events' <<'SQL'
SELECT format('SELECT %L, count(*) FROM %I.%I', schemaname || '.' || relname, schemaname, relname)
FROM pg_stat_user_tables ORDER BY schemaname, relname \gexec
SQL
}

db_ssh true 2>/dev/null || die "cannot reach the $ENV database node ($DB_HOST): is the tunnel up? scripts/ej.sh up $ENV"

if [ -n "$CHECK" ]; then
    now="$(count_rows)" || die "cannot count the rows"
    if diff "$CHECK" <(printf '%s\n' "$now") >&2; then
        echo "db-dump: every table of $ENV has the row count of $CHECK"
        exit 0
    fi
    die "the row counts differ from $CHECK (< the dump, > now)"
fi

umask 077
mkdir -p "$DUMP_DIR"
stamp="$(date -u +%Y%m%dT%H%M%SZ)"
out="$DUMP_DIR/events-$ENV-$stamp.dump"

echo "db-dump: dumping $ENV on $DB_HOST"
# Prints `<path> <sha256> <entries> <server version>`. pg_restore --list proves the archive reads back.
read -r remote sum entries version < <(
    db_ssh 'bash -s' <<'REMOTE'
set -euo pipefail
f="$(sudo -u postgres mktemp /var/tmp/ej-dump.XXXXXX)"
sudo -u postgres pg_dump -d events -Fc -f "$f" || { sudo rm -f "$f"; exit 1; }
entries="$(sudo -u postgres pg_restore --list "$f" | grep -vc '^;')"
echo "$f $(sudo sha256sum "$f" | cut -d' ' -f1) $entries $(sudo -u postgres psql -X -tA -d events -c 'SHOW server_version')"
REMOTE
) || true
[[ "${remote:-}" == /var/tmp/ej-dump.* && -n "${sum:-}" ]] || die "pg_dump failed on the node"
trap 'db_ssh "sudo rm -f $remote" || echo "db-dump: remove $remote on the node by hand" >&2' EXIT

db_ssh "sudo cat $remote" >"$out.partial" || die "the copy failed"
got="$(shasum -a 256 "$out.partial" | cut -d' ' -f1)"
if [ "$got" != "$sum" ]; then
    rm -f "$out.partial"
    die "the copy is damaged: SHA-256 $got, the node has $sum"
fi
mv "$out.partial" "$out"
echo "$sum  $(basename "$out")" >"$out.sha256"
count_rows >"$out.counts" || die "the dump is safe at $out, but the row count failed"

while IFS= read -r old; do
    echo "db-dump: deleting $old, older than $KEEP_DAYS days"
    rm -f "$old" "$old.sha256" "$old.counts"
done < <(find "$DUMP_DIR" -name "events-$ENV-*.dump" -mtime +"$KEEP_DAYS")

cat <<EOF
db-dump: $out
  $(du -h "$out" | cut -f1), $entries archive entries, PostgreSQL $version, SHA-256 $sum
  $(wc -l <"$out.counts" | tr -d ' ') tables, $(awk -F '\t' '{ n += $2 } END { print n }' "$out.counts") rows in $out.counts
Compare after the migration:  scripts/db-dump.sh $ENV --check $out.counts
Restore by hand, with pg_restore 18 on a database node (RESTORE_RUNBOOK.md); a local pg_restore 17 may not read it
EOF
