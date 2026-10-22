#!/usr/bin/env bash
#
# await-builds.sh — pass only when every named build workflow passed on this commit of `main`.
#
# Usage:
#   scripts/await-builds.sh <sha> <workflow-file>[:<job name>]...
#   # e.g. "$GITHUB_SHA" 'build-backend.yml:Build & Test (backend)' 'build-frontend.yml:Build & Test (frontend)'
#
# Requires: gh, authenticated (GH_TOKEN in CI). Reads GITHUB_REPOSITORY, or asks gh for the current
# repository. Reaches the GitHub API only, read-only: `actions: read` is the permission it needs.
#
# `release.yml` builds on every push to `main` and ran no test, so a commit `build-backend.yml` had
# failed was published and rolled to staging (#2185). This runs after the image scan and before the
# first push, so the wait overlaps the build and a red build stops the publish.
#
# Per workflow: the run on `main` for <sha> decides. A workflow whose path filter skipped this push has
# no such run, so the latest finished run on an ancestor of <sha> decides instead. A red `main` then
# stays unpublished through a docs-only commit on top of it. A cancelled run proves nothing and is
# passed over. APPEAR_SECONDS (180), COMPLETE_SECONDS (1800) and POLL_SECONDS (15) tune the waits.
#
# A `:<job name>` after the file waits for that job of the commit's run, not the whole run. The
# backend's informational OWASP job can outlast the wait, and it failed publishes whose gate job had
# passed in minutes (#3690).

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
    "")
        echo "await-builds: usage: $0 <sha> <workflow-file>..." >&2
        exit 2
        ;;
esac

SHA="$1"
shift
[[ $# -gt 0 ]] || {
    echo "await-builds: name at least one workflow file" >&2
    exit 2
}
REPO="${GITHUB_REPOSITORY:-$(gh repo view --json nameWithOwner --jq .nameWithOwner)}"
APPEAR_SECONDS="${APPEAR_SECONDS:-180}"
COMPLETE_SECONDS="${COMPLETE_SECONDS:-1800}"
POLL_SECONDS="${POLL_SECONDS:-15}"

# run_for <workflow> — "<id> <status> <conclusion> <url>" of the newest run on `main` for SHA, or nothing.
run_for() {
    gh api "repos/$REPO/actions/workflows/$1/runs?branch=main&head_sha=$SHA&per_page=5" \
        --jq '.workflow_runs | sort_by(.created_at) | last | select(. != null) | "\(.id) \(.status) \(.conclusion // "-") \(.html_url)"'
}

# job_for <run id> <job name> — "<status> <conclusion>" of that job in the run. A job not started yet
# reads as queued. A run that finished without it reads "completed no-such-job", so a misspelt name
# fails at once rather than after the whole wait.
job_for() {
    local found
    found="$(gh api "repos/$REPO/actions/runs/$1/jobs?per_page=100" \
        --jq '.jobs[] | select(.name == "'"$2"'") | "\(.status) \(.conclusion // "-")"')"
    if [[ -n "$found" ]]; then
        echo "$found"
    elif [[ "$(gh api "repos/$REPO/actions/runs/$1" --jq .status)" == "completed" ]]; then
        echo "completed no-such-job"
    else
        echo "queued -"
    fi
}

# is_ancestor <sha> — whether <sha> is SHA or behind it on the same line. An API error ends the
# search rather than reading as "not an ancestor", which would fall through to an older, greener run.
is_ancestor() {
    local status
    status="$(gh api "repos/$REPO/compare/$1...$SHA" --jq .status)" || exit 3
    case "$status" in
        ahead | identical) return 0 ;;
        behind | diverged) return 1 ;;
        *) exit 3 ;;
    esac
}

# ancestor_run <workflow> — "<sha> <conclusion> <url>" of the newest finished, not-cancelled run on an ancestor.
ancestor_run() {
    local run_sha conclusion url
    # fd 3, not stdin: `gh api` inside the loop reads stdin and would swallow the remaining rows.
    # No `status=completed`: beside `branch=` the API answers with no runs at all (#3769).
    while read -r -u 3 run_sha conclusion url; do
        [[ "$conclusion" == "success" || "$conclusion" == "failure" ]] || continue
        if is_ancestor "$run_sha"; then
            echo "$run_sha $conclusion $url"
            return
        fi
    done 3< <(gh api "repos/$REPO/actions/workflows/$1/runs?branch=main&per_page=30" \
        --jq '.workflow_runs[] | select(.head_sha != "'"$SHA"'") | "\(.head_sha) \(.conclusion) \(.html_url)"')
}

summary() {
    echo "$1"
    [[ -n "${GITHUB_STEP_SUMMARY:-}" ]] && echo "- $1" >>"$GITHUB_STEP_SUMMARY"
    return 0
}

failed=0
for argument in "$@"; do
    workflow="${argument%%:*}"
    job=""
    [[ "$argument" == *:* ]] && job="${argument#*:}"
    started=$SECONDS
    run=""
    while [[ -z "$run" && $((SECONDS - started)) -lt $APPEAR_SECONDS ]]; do
        run="$(run_for "$workflow")"
        [[ -n "$run" ]] || sleep "$POLL_SECONDS"
    done

    if [[ -z "$run" ]]; then
        ancestor="$(ancestor_run "$workflow")" || {
            summary "✗ $workflow: the API failed while finding an ancestor run"
            failed=1
            continue
        }
        read -r run_sha conclusion url <<<"$ancestor"
        if [[ -z "${run_sha:-}" ]]; then
            summary "✗ $workflow: no run for ${SHA:0:8} and none finished on an ancestor"
            failed=1
        elif [[ "$conclusion" == "success" ]]; then
            summary "✓ $workflow: not triggered by ${SHA:0:8}; ancestor ${run_sha:0:8} passed ($url)"
        else
            summary "✗ $workflow: not triggered by ${SHA:0:8}; ancestor ${run_sha:0:8} ended $conclusion ($url)"
            failed=1
        fi
        continue
    fi

    read -r id status conclusion url <<<"$run"
    if [[ -n "$job" ]]; then
        read -r status conclusion <<<"$(job_for "$id" "$job")"
    fi
    while [[ "$status" != "completed" && $((SECONDS - started)) -lt $COMPLETE_SECONDS ]]; do
        sleep "$POLL_SECONDS"
        if [[ -n "$job" ]]; then
            read -r status conclusion <<<"$(job_for "$id" "$job")"
        else
            read -r id status conclusion url <<<"$(run_for "$workflow")"
        fi
    done
    waited=$((SECONDS - started))
    what="$workflow${job:+ ($job)}"
    if [[ "$status" != "completed" ]]; then
        summary "✗ $what: run $id still $status after ${waited}s ($url)"
        failed=1
    elif [[ "$conclusion" == "success" ]]; then
        summary "✓ $what: passed on ${SHA:0:8} after ${waited}s ($url)"
    else
        summary "✗ $what: ended $conclusion on ${SHA:0:8} ($url)"
        failed=1
    fi
done

exit "$failed"
