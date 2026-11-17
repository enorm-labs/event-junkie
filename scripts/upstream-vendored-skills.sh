#!/usr/bin/env bash
#
# upstream-vendored-skills.sh — are the vendored skills behind upstream, right now?
#
# `gh` and `asd-ste100` under .claude/skills/ are copies of third-party repositories. No manifest
# declares them, so neither Dependabot nor Renovate sees them (ADR-024).
#
# Usage:
#   scripts/upstream-vendored-skills.sh          # one line per skill
#   scripts/upstream-vendored-skills.sh --json   # for vendored-skill-reminder.yml
#
# Exit 0 all current, 1 something is behind, 2 the check could not be made. A check that cannot
# reach upstream must not report "up to date".
#
# **Everything is read from each skill's VENDORED.md, never restated here**: the `Upstream:` URL
# (`https://github.com/<owner>/<repo>`, or `…/tree/<branch>/<path>` for a skill inside a larger
# repository) and the 40-character commit on the `Vendored at` line. A skill directory with a
# VENDORED.md is checked; one without is not vendored. "Behind" counts the upstream commits that
# touch the skill's path, newest first, up to 100.
#
# **This only detects.** The update is the re-copy in each VENDORED.md, followed by the gates — a
# bot cannot do it, because a hosted Renovate cannot run the copy and a SHA bump alone is a lie.
#
# Requires: curl, jq. GITHUB_TOKEN or GH_TOKEN lifts the 60-per-hour anonymous limit.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SKILLS="$REPO_ROOT/.claude/skills"

JSON=0

die() {
    printf 'upstream-vendored-skills.sh: %s\n' "$1" >&2
    exit 2
}

usage() {
    awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --json) JSON=1 ;;
        -h | --help)
            usage
            exit 0
            ;;
        *) die "unknown argument $1" ;;
    esac
    shift
done

for tool in curl jq; do
    command -v "$tool" >/dev/null || die "$tool is required but not on PATH"
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# `curl --fail`, so an outage is an error and never an empty answer.
fetch() {
    local url="$1" out="$2"
    local -a auth=()
    local token="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
    [[ -n "$token" ]] && auth=(-H "Authorization: Bearer ${token}")
    curl -fsSL --retry 3 -H 'Accept: application/vnd.github+json' "${auth[@]}" "$url" -o "$out" ||
        die "could not fetch $url"
}

shopt -s nullglob
records=("$SKILLS"/*/VENDORED.md)
[[ ${#records[@]} -gt 0 ]] || die "no VENDORED.md under ${SKILLS#"$REPO_ROOT/"} — the extraction matched nothing"

results="$WORK/results.jsonl"
: >"$results"
behind=0

for record in "${records[@]}"; do
    name="$(basename "$(dirname "$record")")"
    rel="${record#"$REPO_ROOT/"}"

    url="$(grep -m1 -oE '^Upstream: <https://github\.com/[^>]+>' "$record" | sed -E 's/^Upstream: <(.*)>$/\1/')" ||
        die "$rel has no 'Upstream: <https://github.com/…>' line"
    pinned="$(grep -m1 -E '^Vendored at' "$record" | grep -oE '[0-9a-f]{40}')" ||
        die "$rel has no 40-character commit on its 'Vendored at' line"

    # https://github.com/<owner>/<repo>[/tree/<branch>/<path>]
    spec="${url#https://github.com/}"
    owner="${spec%%/*}"
    rest="${spec#*/}"
    repo="${rest%%/*}"
    branch=""
    path=""
    if [[ "$rest" == */tree/* ]]; then
        tree="${rest#*/tree/}"
        branch="${tree%%/*}"
        [[ "$tree" == */* ]] && path="${tree#*/}"
    fi

    query="per_page=100"
    [[ -n "$branch" ]] && query="$query&sha=$branch"
    [[ -n "$path" ]] && query="$query&path=$path"
    fetch "https://api.github.com/repos/$owner/$repo/commits?$query" "$WORK/$name.json"

    latest="$(jq -r '.[0].sha // empty' "$WORK/$name.json")"
    [[ -n "$latest" ]] || die "upstream $owner/$repo${path:+ ($path)} lists no commits"
    latest_date="$(jq -r '.[0].commit.committer.date' "$WORK/$name.json")"
    # The pinned commit's position in the list is how many newer commits touch the skill.
    position="$(jq -r --arg sha "$pinned" '[.[].sha] | index($sha) // empty' "$WORK/$name.json")"

    if [[ "$latest" == "$pinned" ]]; then
        status="current"
        count=0
    elif [[ -n "$position" ]]; then
        status="behind"
        count="$position"
    else
        status="behind"
        count="100+"
    fi
    [[ "$status" == "behind" ]] && behind=1

    jq -nc --arg name "$name" --arg record "$rel" --arg upstream "$url" --arg pinned "$pinned" \
        --arg latest "$latest" --arg latestDate "$latest_date" --arg status "$status" --arg behind "$count" \
        --arg compare "https://github.com/$owner/$repo/compare/$pinned...$latest" \
        '{name: $name, record: $record, upstream: $upstream, pinned: $pinned, latest: $latest,
          latestDate: $latestDate, status: $status, behind: $behind, compare: $compare}' >>"$results"
done

if [[ "$JSON" -eq 1 ]]; then
    jq -sc . "$results"
else
    jq -r '"\(.name)\t\(.status)\(if .status == "behind" then " by \(.behind) commits" else "" end)\tpinned \(.pinned[0:8]), upstream \(.latest[0:8]) (\(.latestDate[0:10]))"' "$results" |
        column -t -s $'\t'
fi

exit "$behind"
