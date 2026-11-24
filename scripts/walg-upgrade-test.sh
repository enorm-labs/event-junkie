#!/usr/bin/env bash
#
# walg-upgrade-test.sh — assert where `walg-upgrade.sh` connects, that it reads the pins from variables.tf, and what it installs,
# patches and refuses, against a fabricated ssh that runs the node half on a fabricated node.
#
# Usage: scripts/walg-upgrade-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/walg-upgrade.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BIN="$WORK/bin"
NODE="$WORK/node"
export FAKE="$WORK" EJ_CLUSTERS_DIR="$WORK/clusters" EJ_VARIABLES_TF="$WORK/variables.tf"
export PATH="$BIN:$PATH"
mkdir -p "$BIN" "$NODE/bin" "$NODE/etc" "$NODE/tools" "$WORK/clusters/staging" "$WORK/clusters/production"
for env in staging:10.1.1.10 production:10.0.1.20; do
    printf 'spec:\n  values:\n    database:\n      host: "%s"\n' "${env#*:}" >"$WORK/clusters/${env%%:*}/helm-release.yaml"
done

sha256() { if command -v sha256sum >/dev/null; then sha256sum "$1"; else shasum -a 256 "$1"; fi | cut -d' ' -f1; }

# A release tarball whose wal-g reports v3.0.10, and one with other bytes for the mismatch.
walg_binary() { printf '#!/usr/bin/env bash\nprintf "wal-g version %s\\tabc123\\t2026.11.01\\tPostgreSQL\\n"\n' "$1"; }
mkdir -p "$WORK/release"
walg_binary v3.0.10 >"$WORK/release/wal-g-pg-24.04-amd64"
chmod +x "$WORK/release/wal-g-pg-24.04-amd64"
tar -czf "$WORK/good.tgz" -C "$WORK/release" wal-g-pg-24.04-amd64
printf 'not the release' >"$WORK/bad.tgz"
GOOD_SUM="$(sha256 "$WORK/good.tgz")"
ARM_SUM="$(printf 'b%.0s' {1..64})"

# The pins as variables.tf writes them, prose naming another version included.
pins() {
    cat >"$EJ_VARIABLES_TF" <<EOF
variable "walg_version" {
  description = <<-EOT
    wal-g release tag, once "v1.0.0".
  EOT
  type        = string
  default     = "$1"
}

variable "walg_checksums" {
  type = map(string)
  default = {
    amd64 = "$2"
    arm64 = "$ARM_SUM"
  }
}
EOF
}

# The node: ssh runs `bash -s` on stdin with the node's absolute paths moved under $NODE. sudo logs and runs the rest.
cat >"$BIN/ssh" <<'EOF'
#!/usr/bin/env bash
echo "ssh $*" >>"$FAKE/events.log"
cmd="${*: -1}"
[[ "$cmd" == "export LC_ALL=C.UTF-8; "* ]] || { echo "no LC_ALL: $cmd" >&2; exit 9; }
cmd="${cmd#export LC_ALL=C.UTF-8; }"
[ -z "${FAKE_UNREACHABLE:-}" ] || exit 255
case "$cmd" in
    true) ;;
    "bash -s -- "*)
        script="$(cat)"
        script="${script//\/usr\/local\/bin\//$FAKE/node/bin/}"
        script="${script//\/etc\/event-junkie\//$FAKE/node/etc/}"
        read -ra args <<<"${cmd#bash -s -- }"
        PATH="$FAKE/node/tools:$PATH" bash -c "$script" bash "${args[@]}"
        ;;
    *) echo "unexpected command: $cmd" >&2; exit 9 ;;
esac
EOF
cat >"$NODE/tools/sudo" <<'EOF'
#!/usr/bin/env bash
echo "sudo $*" >>"$FAKE/events.log"
[ "$1" != -u ] || shift 2
exec "$@"
EOF
cat >"$NODE/tools/curl" <<'EOF'
#!/usr/bin/env bash
echo "curl ${*: -1}" >>"$FAKE/events.log"
while [ $# -gt 0 ]; do [ "$1" = -o ] && out="$2"; shift; done
cp "$FAKE/${FAKE_TARBALL:-good}.tgz" "$out"
EOF
# psql: archived_count rises after pg_switch_wal unless FAKE_NO_ARCHIVE.
cat >"$NODE/tools/psql" <<'EOF'
#!/usr/bin/env bash
sql="${*: -1}"
echo "psql $sql" >>"$FAKE/events.log"
n="$(cat "$FAKE/node/archived")"
case "$sql" in
    *pg_switch_wal*) [ -n "${FAKE_NO_ARCHIVE:-}" ] || echo $((n + 1)) >"$FAKE/node/archived"; echo 0/B6000000 ;;
    *format*) echo "archived_count $n, last 000000010000000000000042 at 2026-11-24; failed_count 3, last failed 2026-10-01; stats_reset 2026-09-01" ;;
    *archived_count*) echo "$n" ;;
    *) echo "" ;;
esac
EOF
cat >"$NODE/tools/dpkg" <<'EOF'
#!/usr/bin/env bash
echo "${FAKE_ARCH:-amd64}"
EOF
printf '#!/usr/bin/env bash\n:\n' >"$NODE/tools/sleep"
# GNU sed on the node; BSD sed on a Mac needs an empty suffix after -i.
cat >"$NODE/tools/sed" <<'EOF'
#!/usr/bin/env bash
if [ "$(uname)" = Darwin ] && [ "$1" = -i ]; then shift; exec /usr/bin/sed -i '' "$@"; fi
exec /usr/bin/sed "$@"
EOF
command -v sha256sum >/dev/null || printf '#!/usr/bin/env bash\nexec shasum -a 256 "$@"\n' >"$NODE/tools/sha256sum"
chmod +x "$BIN/ssh" "$NODE/tools/"*

# A node on v3.0.9 whose bootstrap.env carries the old pins, and a walg dispatcher.
reset_node() {
    walg_binary "${1:-v3.0.9}" >"$NODE/bin/wal-g"
    cat >"$NODE/bin/walg" <<'EOF'
#!/usr/bin/env bash
echo "walg $*" >>"$FAKE/events.log"
[ -z "${FAKE_WALG_FAIL:-}" ]
EOF
    chmod +x "$NODE/bin/wal-g" "$NODE/bin/walg"
    printf 'K3S_VERSION=v1.34.2+k3s1\nWALG_VERSION=%s\nWALG_SHA256_AMD64=%s\nWALG_SHA256_ARM64=%s\nBACKUP_BUCKET=b\n' \
        "${1:-v3.0.9}" "${2:-$(printf 'a%.0s' {1..64})}" "${3:-$(printf 'c%.0s' {1..64})}" >"$NODE/etc/bootstrap.env"
    echo 7 >"$NODE/archived"
}

fail() {
    echo "FAIL $*" >&2
    exit 1
}
pass() { echo "ok   $*"; }
run() {
    : >"$WORK/events.log"
    set +e
    "$SCRIPT" "$@" >"$WORK/out" 2>&1
    status=$?
    set -e
}
logged() { grep -qF -- "$1" "$WORK/events.log"; }

pins v3.0.10 "$GOOD_SUM"
reset_node
run staging
[ "$status" -eq 0 ] || fail "staging upgrade exits 0: $(cat "$WORK/out")"
logged 'ops@10.10.1.1 export LC_ALL=C.UTF-8; bash -s -- upgrade v3.0.10' || fail "staging runs on its k3s node with the pinned tag"
! logged '-J' || fail "staging needs no jump"
logged 'curl https://github.com/wal-g/wal-g/releases/download/v3.0.10/wal-g-pg-24.04-amd64.tar.gz' || fail "the tarball of the pinned tag"
logged "sudo install -m 0755" || fail "the binary is installed"
"$NODE/bin/wal-g" --version | grep -q v3.0.10 || fail "the node's wal-g reports the pin"
grep -qx "WALG_VERSION=v3.0.10" "$NODE/etc/bootstrap.env" || fail "bootstrap.env carries the tag"
grep -qx "WALG_SHA256_AMD64=$GOOD_SUM" "$NODE/etc/bootstrap.env" || fail "bootstrap.env carries the amd64 pin"
grep -qx "WALG_SHA256_ARM64=$ARM_SUM" "$NODE/etc/bootstrap.env" || fail "bootstrap.env carries the arm64 pin"
grep -qx "K3S_VERSION=v1.34.2+k3s1" "$NODE/etc/bootstrap.env" || fail "bootstrap.env keeps its other lines"
logged "pg_logical_emit_message(false, 'walg', 'archive probe')" || fail "a WAL record goes before the switch"
logged 'pg_switch_wal()' || fail "the WAL segment is switched"
logged 'sudo -u postgres' || fail "psql and walg run as postgres"
logged 'walg check' || fail "walg check runs"
grep -q 'installed: v3.0.9' "$WORK/out" || fail "the installed version prints first"
grep -q 'archive: archived_count 7 -> 8' "$WORK/out" || fail "the archive count rises"
grep -q 'failed_count 3, last failed 2026-10-01; stats_reset 2026-09-01' "$WORK/out" || fail "failed_count prints beside its times"
pass "staging: pins from variables.tf, installed, bootstrap.env patched, archive proved, walg check"

run staging
[ "$status" -eq 0 ] || fail "a second run exits 0: $(cat "$WORK/out")"
! logged 'curl ' || fail "a current node downloads nothing"
! logged 'sudo install' || fail "a current node installs nothing"
! logged 'sudo sed' || fail "a current bootstrap.env is not edited"
logged 'pg_switch_wal()' || fail "a current node still proves the archive"
logged 'walg check' || fail "a current node still runs walg check"
grep -q 'already v3.0.10' "$WORK/out" || fail "a current node says so"
pass "already current: no install, no edit, the proof still runs"

reset_node
run production
[ "$status" -eq 0 ] || fail "production upgrade: $(cat "$WORK/out")"
logged '-J ops@10.10.0.1 ops@10.0.1.20 export LC_ALL=C.UTF-8; bash -s -- upgrade' || fail "production runs on database.host through the k3s node"
pass "production: database.host through the jump"

reset_node
run staging --dry-run
[ "$status" -eq 0 ] || fail "a dry run exits 0: $(cat "$WORK/out")"
logged 'bash -s -- dry-run v3.0.10' || fail "the dry run reaches the node"
for verb in 'curl ' 'sudo install' 'sudo sed' 'pg_switch_wal' 'walg check'; do
    ! logged "$verb" || fail "a dry run issues no $verb"
done
grep -q 'installed: v3.0.9' "$WORK/out" || fail "a dry run prints the installed version"
grep -q 'pinned:    v3.0.10' "$WORK/out" || fail "a dry run prints the pinned version"
grep -q 'WALG_VERSION=v3.0.9' "$WORK/out" || fail "a dry run prints bootstrap.env's lines"
grep -q 'a run installs v3.0.10' "$WORK/out" || fail "a dry run says what a run does"
"$NODE/bin/wal-g" --version | grep -q v3.0.9 || fail "a dry run leaves the binary"
pass "--dry-run reads the node and changes nothing"

reset_node
FAKE_TARBALL=bad run staging
[ "$status" -eq 2 ] || fail "a checksum mismatch exits 2, got $status: $(cat "$WORK/out")"
for verb in 'sudo install' 'sudo sed'; do
    ! logged "$verb" || fail "a checksum mismatch issues no $verb"
done
grep -q 'does not match the pinned SHA-256' "$WORK/out" || fail "a checksum mismatch names the cause"
"$NODE/bin/wal-g" --version | grep -q v3.0.9 || fail "a checksum mismatch leaves the old binary"
pass "a checksum mismatch aborts before the install"

reset_node
sed -i.bak '/^WALG_SHA256_ARM64=/d' "$NODE/etc/bootstrap.env"
run staging
[ "$status" -eq 1 ] || fail "an unmatched bootstrap.env exits 1, got $status"
for verb in 'curl ' 'sudo install' 'sudo sed'; do
    ! logged "$verb" || fail "an unmatched bootstrap.env issues no $verb"
done
grep -q 'one WALG_SHA256_ARM64 line' "$WORK/out" || fail "an unmatched bootstrap.env names the line"
pass "a bootstrap.env without the three lines aborts before the install"

reset_node
FAKE_NO_ARCHIVE=1 run staging
[ "$status" -eq 3 ] || fail "WAL that does not archive exits 3, got $status"
grep -q 'archived_count stayed at 7' "$WORK/out" || fail "a stuck archive names the count"
! logged 'walg check' || fail "walg check waits for the archive proof"
reset_node v3.0.10 "$GOOD_SUM" "$ARM_SUM"
FAKE_WALG_FAIL=1 run staging
[ "$status" -eq 3 ] || fail "a failing walg check exits 3, got $status"
pass "a stuck archive or a failing walg check exits 3"

pins v3.0.10 "not-a-checksum"
run staging
[ "$status" -eq 1 ] || fail "an unreadable checksum pin exits 1"
grep -q 'no walg_checksums amd64' "$WORK/out" || fail "an unreadable checksum pin names the pin"
sed -i.bak 's/^variable "walg_version"/variable "walg_tag"/' "$EJ_VARIABLES_TF"
run staging
[ "$status" -eq 1 ] || fail "a missing version pin exits 1"
grep -q 'no walg_version' "$WORK/out" || fail "a missing version pin names the pin"
! grep -q 'bash -s' "$WORK/events.log" || fail "an unreadable pin reaches no node"
pass "a pin that cannot be read is refused before the node"

pins v3.0.10 "$GOOD_SUM"
FAKE_UNREACHABLE=1 run production
[ "$status" -eq 1 ] || fail "an unreachable node exits 1"
grep -q 'is the tunnel up? scripts/ej.sh up production' "$WORK/out" || fail "an unreachable node names the tunnel"
run qa
[ "$status" -eq 1 ] || fail "an unknown environment exits 1"
pass "an unreachable node and an unknown environment are refused"
