#!/usr/bin/env bash
#
# walg-upgrade.sh — install the pinned wal-g on an environment's database node in place, and prove that WAL still archives.
#
# Usage:
#   scripts/walg-upgrade.sh <staging|production>             # install, patch bootstrap.env, prove the archive, walg check
#   scripts/walg-upgrade.sh <staging|production> --dry-run   # print installed against pinned and bootstrap.env; change nothing
#
# Requires: the WireGuard tunnel (scripts/ej.sh up <env>), ssh with the key in ~/.ssh/config (CLUSTER_ACCESS.md), sudo on the node.
# Reaches: the database node over SSH, production's through the k3s node; the node downloads the release from github.com.
#
# Exit 0 done, or the dry run read the node. 1 stopped before any change: a bad argument, a pin that cannot be read, a node that
# cannot be reached, a failed download, or a bootstrap.env without exactly one of each WALG_ line. 2 the tarball failed its pinned
# SHA-256, nothing installed. 3 a check failed: the installed version, bootstrap.env read back, the archive
# probe or `walg check`. On 3 the binary and bootstrap.env can already carry the new version.
#
# The tag and both checksums come from `walg_version` and `walg_checksums` in infra/modules/environment/variables.tf, never
# from here. BACKUPS.md §8 says why this does not run backups.sh: that restarts PostgreSQL, and the binary is the only part a
# bump changes. bootstrap.env holds the node's own copy of the pin. A stale copy makes the next backups.sh run reinstall the old
# version, so it is patched and read back. A second run only proves: it installs nothing that is already current.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    printf 'walg-upgrade: %s\n' "$1" >&2
    exit 1
}

ENV="${1:-}"
case "$ENV" in
    staging | production) ;;
    *) die "expected staging or production, got '${ENV}'" ;;
esac
shift
MODE=upgrade
case "${1:-}" in
    --dry-run) MODE=dry-run ;;
    '') ;;
    *) die "unknown argument '$1'" ;;
esac

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTERS_DIR="${EJ_CLUSTERS_DIR:-$REPO/deploy/clusters}"
VARIABLES="${EJ_VARIABLES_TF:-$REPO/infra/modules/environment/variables.tf}"
SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"

# hcl_default and hcl_map_default as in upstream-node-pins.sh: anchored to the assignment, because the blocks name the version in prose.
hcl_default() {
    awk -v want="$1" '
        $0 ~ "^variable \"" want "\" \\{" { inblock = 1; next }
        inblock && /^\}/ { exit }
        inblock && /^[[:space:]]*default[[:space:]]*=/ {
            if (match($0, /"[^"]*"/)) { print substr($0, RSTART + 1, RLENGTH - 2); exit }
        }
    ' "$VARIABLES"
}

hcl_map_default() {
    awk -v want="$1" -v key="$2" '
        $0 ~ "^variable \"" want "\" \\{" { inblock = 1; next }
        inblock && /^\}/ { exit }
        inblock && /^[[:space:]]*default[[:space:]]*=[[:space:]]*\{/ { inmap = 1; next }
        inmap && $0 ~ "^[[:space:]]*" key "[[:space:]]*=" {
            if (match($0, /"[^"]*"/)) { print substr($0, RSTART + 1, RLENGTH - 2); exit }
        }
    ' "$VARIABLES"
}

[ -f "$VARIABLES" ] || die "$VARIABLES does not exist"
TAG="$(hcl_default walg_version)"
SUM_AMD64="$(hcl_map_default walg_checksums amd64)"
SUM_ARM64="$(hcl_map_default walg_checksums arm64)"
# The values travel in the ssh command line, so they must be exactly what a tag and a SHA-256 look like.
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+[A-Za-z0-9.-]*$ ]] || die "no walg_version tag in $VARIABLES (read '${TAG}')"
[[ "$SUM_AMD64" =~ ^[0-9a-f]{64}$ ]] || die "no walg_checksums amd64 SHA-256 in $VARIABLES (read '${SUM_AMD64}')"
[[ "$SUM_ARM64" =~ ^[0-9a-f]{64}$ ]] || die "no walg_checksums arm64 SHA-256 in $VARIABLES (read '${SUM_ARM64}')"

DB_HOST="$(awk '/^    database:/ { d = 1 } d && /^      host:/ { gsub(/"/, "", $2); print $2; exit }' \
    "$CLUSTERS_DIR/$ENV/helm-release.yaml")"
[ -n "$DB_HOST" ] || die "no database.host in $CLUSTERS_DIR/$ENV/helm-release.yaml"

base_ssh=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=5)
# The same shape as db-dump.sh. Staging's database is on its k3s node; production's is a separate node behind the k3s node.
# SSH forwards LC_CTYPE, the node has no such locale, and every PostgreSQL wrapper prints perl warnings otherwise.
db_ssh() {
    if [ "$ENV" = production ]; then
        "${base_ssh[@]}" -J ops@10.10.0.1 "ops@$DB_HOST" "export LC_ALL=C.UTF-8; $*"
    else
        "${base_ssh[@]}" ops@10.10.1.1 "export LC_ALL=C.UTF-8; $*"
    fi
}

db_ssh true 2>/dev/null || die "cannot reach the $ENV database node ($DB_HOST): is the tunnel up? scripts/ej.sh up $ENV"
echo "walg-upgrade: $ENV database node $DB_HOST, pinned $TAG ($MODE)"

# Runs on the node. Arguments: mode, tag, amd64 SHA-256, arm64 SHA-256. Exit codes as in the header.
rc=0
db_ssh "bash -s -- $MODE $TAG $SUM_AMD64 $SUM_ARM64" <<'NODE' || rc=$?
set -euo pipefail
cd /tmp
MODE="$1" TAG="$2" SUM_AMD64="$3" SUM_ARM64="$4"
BIN=/usr/local/bin/wal-g
WALG=/usr/local/bin/walg
ENV_FILE=/etc/event-junkie/bootstrap.env
T=""
STARTED=0
say() { printf '  %s\n' "$*"; }
fail() {
    printf 'walg-upgrade: %s\n' "$2" >&2
    exit "$1"
}
# An unexpected failure after the first change is a 3, never a 1, because 1 promises that nothing changed.
finish() {
    local st=$?
    [ -z "$T" ] || rm -rf "$T"
    if [ "$st" -ne 0 ] && [ "$st" -ne 2 ] && [ "$STARTED" = 1 ]; then exit 3; fi
    exit "$st"
}
trap finish EXIT
installed_version() { "$BIN" --version 2>/dev/null | grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+[^[:space:]]*' | head -1 || true; }
walg_lines() { sudo grep -E '^WALG_(VERSION|SHA256_AMD64|SHA256_ARM64)=' "$ENV_FILE" | sort || true; }
psql_q() { sudo -u postgres psql -X -q -tA -v ON_ERROR_STOP=1 -d postgres -c "$1"; }

case "$(dpkg --print-architecture)" in
    amd64) ASSET=wal-g-pg-24.04-amd64 SHA="$SUM_AMD64" ;;
    arm64) ASSET=wal-g-pg-24.04-aarch64 SHA="$SUM_ARM64" ;;
    *) fail 1 "unsupported architecture $(dpkg --print-architecture)" ;;
esac
want="$(printf 'WALG_SHA256_AMD64=%s\nWALG_SHA256_ARM64=%s\nWALG_VERSION=%s\n' "$SUM_AMD64" "$SUM_ARM64" "$TAG" | sort)"
installed="$(installed_version)"
have="$(walg_lines)"

say "installed: ${installed:-none} ($BIN)"
say "pinned:    $TAG ($ASSET)"
say "$ENV_FILE:"
printf '%s\n' "${have:-(no WALG_ lines)}" | sed 's/^/    /'
for key in VERSION SHA256_AMD64 SHA256_ARM64; do
    [ "$(printf '%s\n' "$have" | grep -c "^WALG_$key=")" -eq 1 ] ||
        fail 1 "$ENV_FILE does not hold exactly one WALG_$key line; nothing changed. Read it with sudo and fix it by hand"
done

if [ "$MODE" = dry-run ]; then
    if [ "$installed" = "$TAG" ]; then say "binary: current"; else say "binary: a run installs $TAG"; fi
    if [ "$have" = "$want" ]; then say "bootstrap.env: current"; else say "bootstrap.env: a run patches the three lines"; fi
    exit 0
fi

if [ "$installed" = "$TAG" ]; then
    say "binary: already $TAG, nothing to install"
else
    T="$(mktemp -d)"
    curl -fsSL --retry 3 -o "$T/w.tgz" "https://github.com/wal-g/wal-g/releases/download/$TAG/$ASSET.tar.gz" ||
        fail 1 "could not download $ASSET $TAG; nothing changed"
    echo "$SHA  $T/w.tgz" | sha256sum -c - >/dev/null 2>&1 ||
        fail 2 "$ASSET $TAG does not match the pinned SHA-256 $SHA; nothing installed"
    tar -xzf "$T/w.tgz" -C "$T"
    STARTED=1
    sudo install -m 0755 "$T/$ASSET" "$BIN"
    [ "$(installed_version)" = "$TAG" ] || fail 3 "installed $BIN, but it reports '$(installed_version)', not $TAG"
    say "binary: installed $TAG"
fi

if [ "$have" = "$want" ]; then
    say "bootstrap.env: already current"
else
    STARTED=1
    sudo sed -i -e "s|^WALG_VERSION=.*|WALG_VERSION=$TAG|" \
        -e "s|^WALG_SHA256_AMD64=.*|WALG_SHA256_AMD64=$SUM_AMD64|" \
        -e "s|^WALG_SHA256_ARM64=.*|WALG_SHA256_ARM64=$SUM_ARM64|" "$ENV_FILE"
    # sed succeeds when no line matches, so read the file back.
    [ "$(walg_lines)" = "$want" ] || fail 3 "$ENV_FILE does not carry the pinned values after the edit; fix it by hand"
    say "bootstrap.env: patched to $TAG"
fi

# A segment through archive_command proves the backup path, a version string only the download. On an idle database
# pg_switch_wal() does nothing, so one WAL record goes first; it changes no data.
before="$(psql_q 'SELECT archived_count FROM pg_stat_archiver')"
psql_q "SELECT pg_logical_emit_message(false, 'walg', 'archive probe')" >/dev/null
psql_q 'SELECT pg_switch_wal()' >/dev/null
after="$before"
for _ in $(seq 60); do
    after="$(psql_q 'SELECT archived_count FROM pg_stat_archiver')"
    [ "$after" -gt "$before" ] && break
    sleep 2
done
# failed_count counts since stats_reset, so the times tell an old failure from a new one.
psql_q "SELECT format('archived_count %s, last %s at %s; failed_count %s, last failed %s; stats_reset %s',
    archived_count, last_archived_wal, last_archived_time, failed_count, coalesce(last_failed_time::text, 'never'), stats_reset)
    FROM pg_stat_archiver" | sed 's/^/  /'
[ "$after" -gt "$before" ] || fail 3 "archived_count stayed at $before for 120 s: WAL does not archive with $TAG"
say "archive: archived_count $before -> $after"

sudo -u postgres "$WALG" check || fail 3 "walg check failed"
NODE

case "$rc" in
    0)
        if [ "$MODE" = dry-run ]; then echo "walg-upgrade: dry run, nothing changed"; else echo "walg-upgrade: $ENV runs $TAG and archives"; fi
        ;;
    1) die "stopped on the $ENV node; nothing changed" ;;
    2) exit 2 ;;
    3)
        echo "walg-upgrade: a check on the $ENV node failed; see BACKUPS.md §8 and TROUBLESHOOTING.md § walg check fails" >&2
        exit 3
        ;;
    *) die "ssh to the $ENV node exited $rc" ;;
esac
