#!/usr/bin/env bash
#
# db-dump-test.sh — assert where `db-dump.sh` connects, that it keeps only a copy whose SHA-256 matches the node,
# removes the node's file, prunes its own old dumps and compares row counts, against a fabricated ssh.
#
# Usage: scripts/db-dump-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/db-dump.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BIN="$WORK/bin"
export FAKE="$WORK" EJ_DUMP_DIR="$WORK/dumps" EJ_CLUSTERS_DIR="$WORK/clusters"
export PATH="$BIN:$PATH"
mkdir -p "$BIN" "$WORK/clusters/staging" "$WORK/clusters/production"
for env in staging:10.1.1.10 production:10.0.1.20; do
    printf 'spec:\n  values:\n    database:\n      host: "%s"\n' "${env#*:}" >"$WORK/clusters/${env%%:*}/helm-release.yaml"
done

# The node: `bash -s` dumps, `sudo cat` copies (FAKE_DAMAGE alters it), psql counts (FAKE_COUNTS overrides).
cat >"$BIN/ssh" <<'EOF'
#!/usr/bin/env bash
echo "ssh $*" >>"$FAKE/events.log"
data="pg-dump-archive"
cmd="${*: -1}"
[[ "$cmd" == "export LC_ALL=C.UTF-8; "* ]] || { echo "no LC_ALL: $cmd" >&2; exit 9; }
case "${cmd#export LC_ALL=C.UTF-8; }" in
    true) ;;
    "bash -s")
        cat >/dev/null
        [ -z "${FAKE_DUMP_FAIL:-}" ] || exit 1
        echo "/var/tmp/ej-dump.ab12 $(printf %s "$data" | shasum -a 256 | cut -d' ' -f1) 42 18.6"
        ;;
    "sudo cat /var/tmp/ej-dump.ab12") printf %s "$data${FAKE_DAMAGE:-}" ;;
    *psql*) cat >/dev/null; printf '%b\n' "${FAKE_COUNTS:-events.artist\t7\nevents.event\t35}" ;;
esac
EOF
chmod +x "$BIN/ssh"

fail() {
    echo "FAIL $*" >&2
    exit 1
}
pass() { echo "ok   $*"; }
run() {
    : >"$WORK/events.log"
    "$SCRIPT" "$@" >"$WORK/out" 2>&1
}

run staging || fail "staging dump: $(cat "$WORK/out")"
grep -q 'ops@10.10.1.1 export LC_ALL=C.UTF-8; bash -s' "$WORK/events.log" || fail "staging dumps on its k3s node"
! grep -q -- '-J' "$WORK/events.log" || fail "staging needs no jump"
dump="$(ls "$WORK/dumps/staging/"*.dump)"
[ "$(cat "$dump")" = pg-dump-archive ] || fail "the dump is the node's archive"
[ "$(stat -c %a "$dump" 2>/dev/null || stat -f %Lp "$dump")" = 600 ] || fail "the dump is readable only by its owner"
grep -q 'events.event	35' "$dump.counts" || fail "the counts sit beside the dump"
grep -q "$(basename "$dump")" "$dump.sha256" || fail "the checksum names the dump"
grep -q 'sudo rm -f /var/tmp/ej-dump.ab12' "$WORK/events.log" || fail "the node's copy is removed"
grep -q '42 archive entries, PostgreSQL 18.6' "$WORK/out" || fail "the summary names entries and version"
pass "staging: dumped on the k3s node, copied, checked, counted, removed from the node"

run production || fail "production dump: $(cat "$WORK/out")"
grep -q -- '-J ops@10.10.0.1 ops@10.0.1.20 export LC_ALL=C.UTF-8; bash -s' "$WORK/events.log" || fail "production dumps on database.host through the k3s node"
sed -i.bak 's/10.0.1.20/10.0.1.21/' "$WORK/clusters/production/helm-release.yaml"
run production || fail "production dump after a switch"
grep -q 'ops@10.0.1.21 export LC_ALL=C.UTF-8; bash -s' "$WORK/events.log" || fail "a switched database.host moves the dump"
pass "production: database.host through the jump, and it follows a switch PR"

rm -rf "$WORK/dumps"
if FAKE_DAMAGE=x run staging; then fail "a damaged copy must fail"; fi
grep -q 'the copy is damaged' "$WORK/out" || fail "a damaged copy names the cause"
[ -z "$(ls "$WORK/dumps/staging")" ] || fail "a damaged copy leaves no file"
grep -q 'sudo rm -f /var/tmp/ej-dump.ab12' "$WORK/events.log" || fail "a damaged copy still removes the node's file"
if FAKE_DUMP_FAIL=1 run staging; then fail "a failed pg_dump must fail"; fi
grep -q 'pg_dump failed' "$WORK/out" || fail "a failed pg_dump names the cause"
pass "a damaged copy or a failed pg_dump keeps nothing"

old="$WORK/dumps/staging/events-staging-20200101T000000Z.dump"
other="$WORK/dumps/staging/notes.dump"
touch -t 202001010000 "$old" "$old.counts" "$other"
run staging || fail "staging dump with an old one"
[ ! -e "$old" ] && [ ! -e "$old.counts" ] || fail "a dump older than 30 days is deleted"
[ -e "$other" ] || fail "a file the script did not name stays"
[ "$(find "$WORK/dumps/staging" -name "events-staging-*.dump" | wc -l)" -eq 1 ] || fail "the new dump stays"
pass "dumps older than 30 days are pruned, other files stay"

counts="$(find "$WORK/dumps/staging" -name "*.counts" | head -1)"
run staging --check "$counts" || fail "equal counts pass: $(cat "$WORK/out")"
! grep -q 'bash -s' "$WORK/events.log" || fail "--check dumps nothing"
if FAKE_COUNTS='events.artist\t7\nevents.event\t34' run staging --check "$counts"; then fail "a missing row must fail"; fi
grep -q '> events.event	34' "$WORK/out" || fail "the difference is shown"
pass "--check passes on equal counts and shows a difference"

if run qa; then fail "an unknown environment must fail"; fi
pass "an unknown environment is refused"
