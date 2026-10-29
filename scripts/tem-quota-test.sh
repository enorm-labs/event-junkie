#!/usr/bin/env bash
#
# tem-quota-test.sh — assert what `tem-quota.sh` prints and exits with, against a fabricated keychain and Scaleway.
#
# Fakes `security` (one file per item) and `curl` (serves the files FAKE_CONSUMPTION and FAKE_OFFERS, and
# fails with FAKE_FAIL set). `jq` is the real one. The key carries the marker zq9x, and no output and no
# curl argument may contain it.
#
# Usage: scripts/tem-quota-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SCRIPT="$REPO_ROOT/scripts/tem-quota.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BIN="$WORK/bin"
export FAKE_KC="$WORK/keychain" FAKE_LOG="$WORK/curl.log"
mkdir -p "$BIN" "$FAKE_KC"
export PATH="$BIN:$PATH"
unset SCW_TEM_READ_KEY SCW_PROJECT_ID TEM_QUOTA

cat >"$BIN/security" <<'EOF'
#!/usr/bin/env bash
while [ $# -gt 0 ]; do
    [ "$1" = -s ] && { item="$2"; shift; }
    shift
done
[ -f "$FAKE_KC/$item" ] || exit 44
cat "$FAKE_KC/$item"
EOF
cat >"$BIN/curl" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$FAKE_LOG"
cat >>"$FAKE_LOG.stdin"
case "$*" in
    *project-consumption*) file="$FAKE_CONSUMPTION" ;;
    *offer-subscriptions*) file="$FAKE_OFFERS" ;;
esac
if [ -n "${FAKE_FAIL:-}" ]; then
    echo '{"message":"insufficient permissions","type":"permissions_denied"}'
    exit 22
fi
cat "$file"
EOF
chmod +x "$BIN/security" "$BIN/curl"

printf 'SCWzq9x' >"$FAKE_KC/event-junkie-tem-read-key"
printf '11111111-2222-3333-4444-555555555555' >"$FAKE_KC/event-junkie-scaleway-project-id"
export FAKE_CONSUMPTION="$WORK/consumption.json" FAKE_OFFERS="$WORK/offers.json"

fails=0
# expect <name> <exit code> <output pattern> [command…]
expect() {
    local name="$1" want="$2" pattern="$3" out code=0
    shift 3
    out="$("$@" 2>&1)" || code=$?
    if [[ "$code" != "$want" || ! "$out" =~ $pattern || "$out" == *zq9x* ]]; then
        echo "FAIL $name: exit $code, want $want; output: $out"
        fails=$((fails + 1))
    else
        echo "ok   $name"
    fi
}
answer() {
    printf '{"monthly_emails_count": %s}' "$1" >"$FAKE_CONSUMPTION"
    printf '{"offer_subscriptions": [{"included_monthly_emails": %s}], "total_count": 1}' "$2" >"$FAKE_OFFERS"
}

answer 19 300
expect "below 80 %, both limits" 0 '^19 mails this month: 0 % of the 10000 quota, 6 % of the 300 the plan includes$' "$SCRIPT"
answer 1234 300
expect "past the included mails is no finding" 0 '12 % of the 10000 quota, 411 % of the 300' "$SCRIPT"
answer 8000 300
expect "at 80 % of the quota" 3 '^8000 mails this month: 80 % of the 10000 quota' "$SCRIPT"
answer 12000 300
expect "over the quota" 3 '120 % of the 10000 quota' "$SCRIPT"
TEM_QUOTA=50000 expect "TEM_QUOTA replaces the quota" 0 '24 % of the 50000 quota' "$SCRIPT"
TEM_QUOTA=0 expect "TEM_QUOTA must be positive" 2 'positive number' "$SCRIPT"
answer 500 0
expect "no included mails: the quota alone" 0 '^500 mails this month: 5 % of the 10000 quota$' "$SCRIPT"
printf '{"offer_subscriptions": [], "total_count": 0}' >"$FAKE_OFFERS"
expect "no subscription: the quota alone" 0 'quota$' "$SCRIPT"
answer 1234 10000
expect "--json carries both answers" 0 '"included_monthly_emails": 10000' "$SCRIPT" --json
FAKE_FAIL=1 expect "a refused call names Scaleway's message" 1 'project-consumption failed: insufficient permissions' "$SCRIPT"
printf '{}' >"$FAKE_CONSUMPTION"
expect "no count in the answer" 1 'carries no monthly_emails_count' "$SCRIPT"
rm "$FAKE_KC/event-junkie-tem-read-key"
expect "no key" 2 'no key or project ID' "$SCRIPT"
answer 1 10000
SCW_TEM_READ_KEY=SCWzq9x expect "the key from the environment" 0 '^1 mails this month' "$SCRIPT"
expect "an unknown argument" 2 'unknown argument' "$SCRIPT" --bogus

if grep -q zq9x "$FAKE_LOG" || ! grep -q '^header = "X-Auth-Token: SCWzq9x"$' "$FAKE_LOG.stdin"; then
    echo "FAIL the key must reach curl on stdin only"
    fails=$((fails + 1))
else
    echo "ok   the key reaches curl on stdin only"
fi

((fails == 0)) || {
    echo "$fails failed"
    exit 1
}
echo "all passed"
