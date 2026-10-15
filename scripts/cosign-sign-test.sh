#!/usr/bin/env bash
#
# cosign-sign-test.sh — assert what `cosign-sign.sh` decides, not merely that it runs.
#
# A stub `cosign` on PATH fails a given number of times, then passes, and records each call. The cases
# are the ones #3331 exists for: a transient Rekor 409 must not fail the publish, and a signature that
# never verifies must.
#
# Usage: scripts/cosign-sign-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SIGN="$REPO_ROOT/scripts/cosign-sign.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin"
cat >"$WORK/bin/cosign" <<'STUB'
#!/usr/bin/env bash
# Fails the first SIGN_FAILS `sign` calls and the first VERIFY_FAILS `verify` calls, then passes.
echo "$*" >>"$STUB_DIR/calls"
count_file="$STUB_DIR/$1.count"
count=$(($(cat "$count_file" 2>/dev/null || echo 0) + 1))
echo "$count" >"$count_file"
case "$1" in
    sign) limit="${SIGN_FAILS:-0}" ;;
    verify) limit="${VERIFY_FAILS:-0}" ;;
    *) exit 64 ;;
esac
if ((count <= limit)); then
    echo "Error: [POST /api/v1/log/entries][409] createLogEntryConflict" >&2
    exit 1
fi
STUB
chmod +x "$WORK/bin/cosign"

REF="ghcr.io/enorm-labs/event-junkie/bff@sha256:af5bc39a"
failures=0

# Runs the script with a fresh stub state; sets STATUS and OUTPUT.
run() {
    rm -f "$WORK"/*.count "$WORK/calls"
    set +e
    OUTPUT="$(PATH="$WORK/bin:$PATH" STUB_DIR="$WORK" RETRY_SECONDS=0 GITHUB_REPOSITORY=enorm-labs/event-junkie \
        GITHUB_REF=refs/heads/main "$@" 2>&1)"
    STATUS=$?
    set -e
}

calls() {
    if [[ -f "$WORK/calls" ]]; then grep -c "^$1 " "$WORK/calls" || true; else echo 0; fi
}

expect() {
    local name="$1" status="$2" signs="$3" verifies="$4"
    if [[ "$STATUS" == "$status" && "$(calls sign)" == "$signs" && "$(calls verify)" == "$verifies" ]]; then
        echo "ok   $name"
    else
        echo "FAIL $name: exit $STATUS (want $status), sign $(calls sign) (want $signs), verify $(calls verify) (want $verifies)"
        printf '     %s\n' "${OUTPUT//$'\n'/$'\n     '}"
        failures=$((failures + 1))
    fi
}

run "$SIGN" "$REF"
expect "signs and verifies on the first attempt" 0 1 1

run env SIGN_FAILS=1 "$SIGN" "$REF"
expect "a 409 on the first attempt passes on the second" 0 2 1
if ! grep -q "::warning::cosign-sign: signing .* attempt 1 of 3" <<<"$OUTPUT"; then
    echo "FAIL the retried attempt is not warned about"
    failures=$((failures + 1))
fi

run env SIGN_FAILS=3 "$SIGN" "$REF"
expect "three failed attempts fail, and nothing is verified" 1 3 0

run env VERIFY_FAILS=3 "$SIGN" "$REF"
expect "a signature that never verifies fails" 1 1 3

run env VERIFY_FAILS=1 "$SIGN" "$REF"
expect "a transient verify failure passes on the second attempt" 0 1 2

run "$SIGN" "$REF" "ghcr.io/enorm-labs/event-junkie/injector:latest"
expect "a reference without a digest is refused before any call" 2 0 0

run "$SIGN" "$REF" "${REF/bff/importer}"
expect "each reference is signed and verified" 0 2 2
if ! grep -q -- "--certificate-identity https://github.com/enorm-labs/event-junkie/.github/workflows/release.yml@refs/heads/main" "$WORK/calls"; then
    echo "FAIL verify does not check the workflow identity the clusters accept"
    failures=$((failures + 1))
fi

if ((failures > 0)); then
    echo "$failures case(s) failed"
    exit 1
fi
echo "all cases passed"
