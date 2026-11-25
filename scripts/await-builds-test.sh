#!/usr/bin/env bash
#
# await-builds-test.sh — assert what `await-builds.sh` decides when the GitHub API fails.
#
# A stub `gh` on PATH answers from fixed JSON through the real `jq`, and fails a given number of calls to
# each endpoint first. A transient 504 must not fail the publish. An API that stays down must, and it
# must never read as a verdict on the build.
#
# Usage: scripts/await-builds-test.sh — needs jq, reaches no network, writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AWAIT="$REPO_ROOT/scripts/await-builds.sh"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

mkdir -p "$WORK/bin"
cat >"$WORK/bin/gh" <<'STUB'
#!/usr/bin/env bash
# gh api <path> --jq <filter>. FAIL_<endpoint>=<n> fails the first n calls, with the error body on stdout as gh does.
url="$2"
filter="$4"
case "$url" in
    */runs\?*head_sha=*) endpoint=RUN ;;
    */workflows/*/runs\?*) endpoint=ANCESTORS ;;
    */jobs\?*) endpoint=JOB ;;
    */compare/*) endpoint=COMPARE ;;
    */actions/runs/*) endpoint=STATUS ;;
    *) exit 64 ;;
esac
echo "$endpoint" >>"$STUB_DIR/calls"
limit="FAIL_$endpoint"
if (($(grep -c "^$endpoint$" "$STUB_DIR/calls") <= ${!limit:-0})); then
    echo '{"message":"We could not respond to your request in time."}'
    echo "gh: We couldn't respond to your request in time. (HTTP 504)" >&2
    exit 1
fi
case "$endpoint" in
    RUN)
        if [[ "$url" == *frontend* ]]; then
            json='{"workflow_runs":[]}'
        else
            json='{"workflow_runs":[{"id":7,"created_at":"2026-11-25","status":"completed","conclusion":"success","html_url":"u"}]}'
        fi
        ;;
    JOB) json='{"jobs":[{"name":"Gate","status":"completed","conclusion":"'"${GATE:-success}"'"}]}' ;;
    ANCESTORS) json='{"workflow_runs":[{"head_sha":"aaaaaaaa1","conclusion":"success","html_url":"u"}]}' ;;
    COMPARE) json='{"status":"ahead"}' ;;
    STATUS) json='{"status":"completed"}' ;;
esac
jq -r "$filter" <<<"$json"
STUB
chmod +x "$WORK/bin/gh"

failures=0

# expect <name> <exit> <pattern in the output> [VAR=value]... -- <workflow>...
expect() {
    local name="$1" want="$2" pattern="$3" status=0 output
    local vars=()
    shift 3
    while [[ "$1" != "--" ]]; do
        vars+=("$1")
        shift
    done
    shift
    rm -f "$WORK/calls"
    output="$(env PATH="$WORK/bin:$PATH" STUB_DIR="$WORK" GITHUB_REPOSITORY=enorm-labs/event-junkie GITHUB_STEP_SUMMARY= \
        APPEAR_SECONDS=1 COMPLETE_SECONDS=5 POLL_SECONDS=1 RETRY_SECONDS=0 ${vars[@]+"${vars[@]}"} \
        "$AWAIT" bbbbbbbb2 "$@" 2>&1)" || status=$?
    if [[ "$status" == "$want" ]] && grep -q -- "$pattern" <<<"$output"; then
        echo "ok   $name"
    else
        echo "FAIL $name: exit $status (want $want), output must match: $pattern"
        printf '     %s\n' "${output//$'\n'/$'\n     '}"
        failures=$((failures + 1))
    fi
}

BACKEND='build-backend.yml:Gate'
FRONTEND='build-frontend.yml:Gate'
DOWN='::error::await-builds: .* failed 3 times'

expect "a green gate job passes" 0 "✓ build-backend" -- "$BACKEND"
expect "a red gate job fails" 1 "ended failure" GATE=failure -- "$BACKEND"
expect "a skipped workflow takes its ancestor's result" 0 "ancestor aaaaaaaa passed" -- "$FRONTEND"

for endpoint in RUN JOB; do
    expect "a transient 504 on $endpoint passes" 0 "✓ build-backend" "FAIL_$endpoint=2" -- "$BACKEND"
    expect "an API down on $endpoint fails" 1 "$DOWN" "FAIL_$endpoint=99" -- "$BACKEND"
done
for endpoint in RUN ANCESTORS COMPARE; do
    expect "a transient 504 on $endpoint passes a skipped workflow" 0 "ancestor aaaaaaaa passed" "FAIL_$endpoint=2" -- "$FRONTEND"
done
for endpoint in ANCESTORS COMPARE; do
    expect "an API down on $endpoint is named as one" 1 "the API failed while finding an ancestor run" "FAIL_$endpoint=99" -- "$FRONTEND"
done

expect "a misspelt job name fails at once" 1 "ended no-such-job" -- 'build-backend.yml:Typo'
expect "a transient 504 on the run's status passes the lookup" 1 "ended no-such-job" FAIL_STATUS=2 -- 'build-backend.yml:Typo'
expect "an API down on the run's status fails" 1 "$DOWN" FAIL_STATUS=99 -- 'build-backend.yml:Typo'
expect "a retried attempt is warned about" 0 "::warning::await-builds: .* attempt 1 of 3" FAIL_JOB=1 -- "$BACKEND"

if ((failures > 0)); then
    echo "$failures case(s) failed"
    exit 1
fi
echo "all cases passed"
