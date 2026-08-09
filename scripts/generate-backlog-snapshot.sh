#!/usr/bin/env bash
#
# generate-backlog-snapshot.sh — render the open issues into build/BACKLOG.md.
#
# GitHub Issues is the backlog. This renders a local, grep-able mirror of it so that *finding*
# work is a file read rather than a network round trip per question. Changing work still goes
# through `gh`.
#
# That split is deliberate. Before the migration, TODO.md was one file that 23 others pointed at,
# and five agent prompts told agents to read *and append to* it. Replacing every one of those
# reads with `gh issue list` would have made the tracker slower and less reliable to consult than
# the file it replaced — so reads get this snapshot, and only writes moved to the API.
#
# Usage: scripts/generate-backlog-snapshot.sh [output-file]
#
# **Run it before you rely on it.** The output is generated on demand and is NOT committed — it
# lives under build/, which is gitignored.
#
# This began as a workflow that committed the file on every issue event. That cannot work here:
# the `main` ruleset requires every change to arrive by pull request and only an OrganizationAdmin
# may bypass it, and GitHub rejects the Actions bot as a bypass actor outright ("must be part of
# the ruleset source or owner organization"). The workflow failed on its first real run and the
# committed copy was stale within the hour. Generating on demand has no such failure mode, and a
# file regenerated before use cannot be quietly out of date — which a committed one silently was.
#
# The header carries a generation timestamp, so a stale local copy is visible rather than assumed.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="${1:-$REPO_ROOT/build/BACKLOG.md}"
REPO="${BACKLOG_REPO:-enorm-labs/event-checker}"

mkdir -p "$(dirname "$OUT")"

command -v gh >/dev/null 2>&1 || {
    echo "gh is required" >&2
    exit 1
}
command -v jq >/dev/null 2>&1 || {
    echo "jq is required" >&2
    exit 1
}

# Milestone display order. Anything not listed sorts after these, alphabetically; issues with no
# milestone land in a final "Unscheduled" section.
MILESTONE_ORDER=(
    "v0.2 — Deployable"
    "v0.3 — Launch-ready"
    "v1.0 — Go-live"
    "Phase 2 — Coverage & polish"
    "Phase 3 — Accounts & personalization"
    "Phase 4 — Social & ecosystem"
)

issues="$(gh issue list --repo "$REPO" --state open --limit 500 \
    --json number,title,labels,milestone,issueType,url)"

total="$(jq 'length' <<<"$issues")"

# One issue as a table row. Labels are split into the three axes the tracker actually uses, so
# the row reads as "what kind of work / how big / why it cannot start" rather than a label soup.
render_rows() {
    jq -r '
      sort_by(.number)[] |
      (.labels | map(.name)) as $l |
      ($l | map(select(startswith("area:") or . == "importer" or . == "documentation"))
          | map(sub("^area:"; "")) | join(", ")) as $area |
      ($l | map(select(startswith("size:"))) | map(sub("^size:"; "")) | join("")) as $size |
      ($l | map(select(. == "blocked" or . == "needs-decision" or . == "needs-deployment"))
          | join(", ")) as $state |
      "| [#\(.number)](\(.url)) | \(.title | gsub("\\|"; "\\\\|")) | \(.issueType.name // "—") | \($area // "—") | \($size) | \($state) |"
    ' <<<"$1"
}

section() {
    local title="$1" filter="$2" subset count
    subset="$(jq -c "$filter" <<<"$issues")"
    count="$(jq 'length' <<<"$subset")"
    [[ "$count" -gt 0 ]] || return 0

    printf '\n## %s — %s open\n\n' "$title" "$count"
    printf '| # | Title | Type | Area | Size | State |\n'
    printf '|---|---|---|---|---|---|\n'
    render_rows "$subset"
}

{
    cat <<HEADER
<!-- GENERATED, NOT COMMITTED — DO NOT EDIT.
     Source of truth: https://github.com/$REPO/issues
     Regenerate: scripts/generate-backlog-snapshot.sh -->

# Backlog

A snapshot of the **$total open issues** in [the tracker](https://github.com/$REPO/issues),
grouped by milestone.

**Generated $(date -u '+%Y-%m-%d %H:%M UTC').** Regenerate before relying on it — this file is
written on demand into \`build/\` and is not committed, so it is exactly as current as the last
time someone ran the script.

**It is for finding work, not for recording it.** Edits here are overwritten. To add, change or
close something, use the tracker — \`/new-issue\`, \`/next-issue\`, \`/start-issue\`, or \`gh\`
directly.

- **Board** — <https://github.com/orgs/enorm-labs/projects/1> (Status and Priority live there, not
  in labels)
- **Direction and phases** — [VISION_ROADMAP_IDEAS.md](VISION_ROADMAP_IDEAS.md)
- **State column** — \`blocked\` waits on another issue, \`needs-decision\` on a choice,
  \`needs-deployment\` on a live origin. The last of those is not neglected work.
HEADER

    for m in "${MILESTONE_ORDER[@]}"; do
        section "$m" "[.[] | select(.milestone.title == \"$m\")]"
    done

    # Anything in a milestone not named above — so a new milestone cannot silently vanish.
    known="$(printf '%s\n' "${MILESTONE_ORDER[@]}" | jq -R . | jq -sc .)"
    others="$(jq -c --argjson k "$known" \
        '[.[] | select(.milestone != null and (.milestone.title as $t | $k | index($t) | not))]' <<<"$issues")"
    if [[ "$(jq 'length' <<<"$others")" -gt 0 ]]; then
        for m in $(jq -r '[.[].milestone.title] | unique | .[]' <<<"$others"); do
            section "$m" "[.[] | select(.milestone.title == \"$m\")]"
        done
    fi

    section "Unscheduled" '[.[] | select(.milestone == null)]'
} >"$OUT"

echo "wrote $OUT ($total open issues)"
