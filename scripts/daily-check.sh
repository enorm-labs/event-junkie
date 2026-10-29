#!/usr/bin/env bash
#
# daily-check.sh — the GitHub half of a maintainer's start of day, as one JSON object.
#
# Usage:
#   scripts/daily-check.sh [--hours N]       # runs, bot issues, reports, security, pull requests, release
#
# Needs `gh`, authenticated with the scopes that read Dependabot and code-scanning alerts. Reads only:
# no cluster, no tunnel, no local git state. `/daily-check` feeds on it; the cluster half is
# `scripts/ej.sh status` and `/log-check`.
#
# --hours defaults to 24 and sets what counts as new. The keys: `failed_runs` (per workflow, whether
# the latest finished run is still red), `stuck_runs` (any run unfinished after six hours),
# `disabled_workflows`, `missed_schedules` (an active workflow whose last scheduled run is older than
# its cron period plus DAILY_CHECK_GRACE_HOURS, default 8), `release_main` (the latest release.yml on main, whatever its
# age), `bot_issues` (every open issue automation opened), `reports` (whether the latest plausibility
# and OWASP report was answered or filed), `security`, `pull_requests`, `release`, `unclosed` and
# `after_deploy` (merged pull requests whose after-deploy steps are still unticked, and whether the
# latest release carries them). An
# API that refuses answers `{"error": …}` in place of its key, never an empty list. Exit code: 0, or 2
# on bad arguments.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    echo "daily-check: $1" >&2
    exit 2
}

HOURS=24
while [ $# -gt 0 ]; do
    case "$1" in
        --hours)
            [[ "${2:-}" =~ ^[0-9]+$ ]] || die "--hours needs a number"
            HOURS="$2"
            shift 2
            ;;
        *) die "unknown argument '$1'; see --help" ;;
    esac
done

REPO="$(gh repo view --json nameWithOwner -q .nameWithOwner)"
SINCE="$(date -u -v-"${HOURS}"H +%FT%TZ 2>/dev/null || date -u -d "-${HOURS} hours" +%FT%TZ)"

# One refused API must not sink the report, and an empty list must not stand in for a refusal.
api() {
    local answer
    if answer=$(gh api "$@" 2>&1); then
        printf '%s' "$answer" | jq -s 'add // []'
    else
        jq -n --arg e "$(printf '%s' "$answer" | head -c 200)" '{error: $e}'
    fi
}

# Pull-request runs are that pull request's checks; the rest is what runs on main or on a schedule.
failed_runs() {
    gh run list -R "$REPO" --limit 2000 --created ">=$SINCE" \
        --json databaseId,workflowName,conclusion,status,event,headBranch,createdAt,url |
        jq --arg since "$SINCE" '
            [.[] | select(.event != "pull_request" and .event != "pull_request_target" and .event != "pull_request_review")
                 | select(.headBranch == "main" or (.event | test("schedule|dispatch|workflow_run")))]
            | group_by(.workflowName)
            | map(
                (map(select(.status == "completed")) | sort_by(.createdAt)) as $done
                | ($done | map(select(.conclusion | test("failure|timed_out|startup_failure")))) as $red
                | select($red | length > 0)
                | {workflow: .[0].workflowName, failures: ($red | length), runs: ($done | length),
                   still_red: ($done[-1].conclusion | test("failure|timed_out|startup_failure")),
                   last_failure: ($red[-1] | {at: .createdAt, event, branch: .headBranch, url})})
            | sort_by(.still_red | not)'
}

# A hosted job ends at six hours, so a run unfinished after that waits on a runner, an approval or an
# environment. Every event counts, because a stuck pull-request run also blocks its merge.
stuck_runs() {
    local cutoff
    cutoff="$(date -u -v-6H +%FT%TZ 2>/dev/null || date -u -d "-6 hours" +%FT%TZ)"
    for status in queued in_progress waiting pending requested; do
        gh run list -R "$REPO" --status "$status" --limit 100 \
            --json databaseId,workflowName,status,event,headBranch,createdAt,url
    done | jq -s --arg cutoff "$cutoff" \
        'add | map(select(.createdAt < $cutoff) | {workflow: .workflowName, status, event, branch: .headBranch, since: .createdAt, url})
             | sort_by(.since)'
}

# A disabled workflow fails nothing: its schedule just stops.
disabled_workflows() {
    gh workflow list -R "$REPO" --all --limit 200 --json name,path,state |
        jq 'map(select(.state != "active"))'
}

# A schedule that never fired fails nothing either. Its period comes from the cron fields, not a full
# parse: day of month or month set is quarterly at most, day of week set is weekly over the days named,
# else daily. Cron fires 4.5 to 7 h late here, so the grace is 8 h. The file is read from main, which is
# what GitHub schedules.
schedule_lines() {
    local path file cron last
    gh workflow list -R "$REPO" --limit 200 --json name,path,state |
        jq -r '.[] | select(.state == "active" and (.path | startswith(".github/workflows/"))) | [.name, .path] | @tsv' |
        while IFS=$'\t' read -r name path; do
            # A workflow whose file left main stays listed as active, and has no schedule.
            if ! file=$(gh api -H 'Accept: application/vnd.github.raw' "repos/$REPO/contents/$path?ref=main" 2>&1); then
                [[ "$file" == *"HTTP 404"* ]] && continue
                echo "$file" >&2
                return 1
            fi
            cron=$(printf '%s\n' "$file" | sed -nE "s/^[[:space:]]*- cron:[[:space:]]*['\"]([^'\"]+)['\"].*/\1/p")
            [ -n "$cron" ] || continue
            last=$(gh run list -R "$REPO" --workflow "$path" --event schedule --limit 1 --json createdAt,url --jq '.[0] // null')
            jq -n --arg name "$name" --arg path "$path" --arg cron "$cron" --argjson last "${last:-null}" \
                '{workflow: $name, path: $path, crons: ($cron | split("\n")), last_run: (if $last then {at: $last.createdAt, url: $last.url} else null end)}'
        done
}

missed_schedules() {
    local lines err
    err=$(mktemp)
    if ! lines=$(schedule_lines 2>"$err"); then
        jq -n --arg e "$(head -c 200 "$err")" '{error: $e}'
        rm -f "$err"
        return
    fi
    rm -f "$err"
    printf '%s' "$lines" | jq -s --argjson grace "${DAILY_CHECK_GRACE_HOURS:-8}" '
        map(.period_hours = ([.crons[] | split(" ") |
                if .[2] != "*" or .[3] != "*" then 2232
                elif .[4] != "*" then 168 / ([.[4] | split(",")[] | if test("-") then (split("-") | (.[1] | tonumber) - (.[0] | tonumber) + 1) else 1 end] | add)
                else 24 end] | min)
            | .overdue_hours = (if .last_run then ((now - (.last_run.at | fromdateiso8601)) / 3600 - .period_hours | floor) else null end)
            | select(.last_run == null or .overdue_hours >= $grace)
            | del(.crons))
        | sort_by(-(.overdue_hours // 1e9))'
}

release_main() {
    gh run list -R "$REPO" --workflow release.yml --branch main --limit 1 \
        --json conclusion,status,createdAt,headSha,url | jq '.[0] // null'
}

bot_issues() {
    gh issue list -R "$REPO" --state open --author "github-actions[bot]" --limit 100 \
        --json number,title,createdAt,labels,url |
        jq --arg since "$SINCE" 'map({number, title, url, created: .createdAt, new: (.createdAt >= $since),
                                     labels: [.labels[].name]})'
}

# A report reads as handled once a person has commented after it, or an issue filed since cites it.
report() {
    local prefix="$1" number
    number=$(gh issue list -R "$REPO" --state open --search "\"$prefix\" in:title" --limit 5 --json number,title |
        jq --arg p "$prefix" 'map(select(.title | startswith($p))) | sort_by(.number) | last | .number // empty')
    [ -n "$number" ] || {
        jq -n --arg p "$prefix" '{title: $p, error: "no open issue with this title"}'
        return
    }
    local comments reported filed
    comments=$(gh api "repos/$REPO/issues/$number/comments" --paginate | jq -s 'add // []')
    reported=$(jq -r 'map(select(.user.type == "Bot")) | last | .created_at // empty' <<<"$comments")
    filed='[]'
    [ -z "$reported" ] || filed=$(gh issue list -R "$REPO" --state all --search "$number in:body created:>=$reported" --limit 100 \
        --json number -q "map(.number) | map(select(. != $number))")
    jq --argjson n "$number" --arg p "$prefix" --arg repo "$REPO" --argjson filed "$filed" '
        (map(select(.user.type == "Bot")) | last) as $bot
        | (map(select(.user.type != "Bot")) | last) as $person
        | {title: $p, number: $n, url: "https://github.com/\($repo)/issues/\($n)",
           latest_report: ($bot | if . then {at: .created_at, url: .html_url, head: (.body | .[0:300])} else null end),
           latest_answer: ($person.created_at // null), filed_since: $filed,
           unanswered: ($bot != null and ($person == null or $person.created_at < $bot.created_at) and ($filed | length) == 0)}' <<<"$comments"
}

security() {
    local dependabot scanning quality
    dependabot=$(api "repos/$REPO/dependabot/alerts?state=open&per_page=100" --paginate)
    scanning=$(api "repos/$REPO/code-scanning/alerts?state=open&ref=refs/heads/main&per_page=100" --paginate)
    # `state=open` is not optional: the bare call and `state=all` both answer [] whatever exists.
    quality=$(api "repos/$REPO/code-quality/findings?state=open&per_page=100" --paginate)
    jq -n --arg since "$SINCE" --argjson d "$dependabot" --argjson s "$scanning" --argjson q "$quality" '
        def summary(sev; tool):
            if type == "object" then . else
            {open: length, new: map(select(.created_at >= $since)) | length,
             by: (group_by(sev + " " + tool) | map({key: .[0] | (sev + " " + tool), value: length}) | from_entries),
             new_items: [.[] | select(.created_at >= $since) | {number, url: .html_url}]} end;
        {dependabot: ($d | summary(.security_advisory.severity // "?"; .dependency.package.name // "?")),
         code_scanning: ($s | summary(.rule.security_severity_level // .rule.severity // "?"; .tool.name // "?")),
         code_quality: ($q | if type == "object" then . else {open: length} end)}'
}

pull_requests() {
    gh pr list -R "$REPO" --state open --limit 100 \
        --json number,title,author,createdAt,isDraft,mergeStateStatus,reviewDecision,autoMergeRequest,statusCheckRollup,changedFiles,additions,deletions,url |
        jq 'map({number, title, url, author: .author.login, bot: (.author.is_bot // (.author.login | startswith("app/"))),
                 created: .createdAt, draft: .isDraft, merge_state: .mergeStateStatus, review: .reviewDecision,
                 auto_merge: (.autoMergeRequest != null), files: .changedFiles, lines: (.additions + .deletions),
                 failed: [.statusCheckRollup[] | select((.conclusion // .state) | test("FAILURE|ERROR|TIMED_OUT|CANCELLED")) | (.name // .context)],
                 pending: [.statusCheckRollup[] | select(((.conclusion // .state) // "") | test("^$|PENDING|EXPECTED")) | (.name // .context)] | length})
            | sort_by(.bot | not)'
}

# The Dependency Dashboard lists what Renovate holds back; an unticked box waits for a person, except
# the manual-job box that is always there.
renovate_dashboard() {
    gh issue list -R "$REPO" --state open --author "renovate[bot]" --search "Dependency Dashboard in:title" --limit 1 --json number,body,url |
        jq '.[0] | if . then {number, url, unticked: [.body | split("\n")[] | select(test("^ *- \\[ \\] ") and (test("manual job") | not)) | .[0:160]]} else null end'
}

release() {
    local tag
    tag=$(gh release view -R "$REPO" --json tagName -q .tagName 2>/dev/null) || {
        jq -n '{error: "no release found"}'
        return
    }
    gh api "repos/$REPO/compare/$tag...main" |
        jq --arg tag "$tag" '{last: $tag, ahead: .ahead_by,
                              commits: [.commits[] | .commit.message | split("\n")[0]]}'
}

# A merged pull request's `Closes #N` usually closes N; when GitHub registers no closing reference, N stays open unnoticed.
unclosed() {
    local merged n
    merged=$(gh pr list -R "$REPO" --state merged --search "merged:>=$SINCE" --limit 100 --json number,body |
        jq -r '.[] | .number as $pr | .body | scan("(?m)^Closes #([0-9]+)")[] | "\($pr) \(.)"')
    while read -r pr n; do
        [ -n "$n" ] || continue
        gh issue view -R "$REPO" "$n" --json number,state,title |
            jq -c --argjson pr "$pr" 'select(.state == "OPEN") | {issue: .number, title, pr: $pr}'
    done <<<"$merged" | jq -s .
}

# A merged pull request labelled `after-deploy` keeps its label until `/post-release` ticks every step.
# `released`: whether the latest release tag contains its merge commit, so production runs it.
# `due` per step: the date of a `(from YYYY-MM-DD)`, `deploy+<N>d` for `(N days after deploy)`, `dark-day:<slug>`
# for a `(dark day <slug>)`, `deploy+<N>d` for "a week after deploy" in the text, `when:<text>` for any other parenthesis, the date of an `on YYYY-MM-DD` in a step
# without one, else `now`. An unknown form is never `now`, or the step shows as due on every run.
after_deploy() {
    local tag prs number sha
    tag=$(gh release view -R "$REPO" --json tagName -q .tagName 2>/dev/null || true)
    prs=$(gh pr list -R "$REPO" --state merged --label after-deploy --limit 100 --json number,title,mergeCommit,body)
    printf '%s' "$prs" | jq -r '.[] | "\(.number) \(.mergeCommit.oid)"' | while read -r number sha; do
        [ -n "$sha" ] || continue
        local released=false
        if [ -n "$tag" ] && gh api "repos/$REPO/compare/$tag...$sha" --jq '.status' 2>/dev/null | grep -qE '^(behind|identical)$'; then
            released=true
        fi
        printf '%s' "$prs" | jq -c --argjson n "$number" --argjson released "$released" --arg tag "$tag" '
            .[] | select(.number == $n) |
            {pr: .number, title, released: $released, release: $tag,
             unticked: [(.body | capture("(?s)## After deploy[^\n]*\n(?<s>.*?)(\n## |$)").s // "")
                        | split("\n")[] | select(test("^ *- \\[ \\] "))
                        | {step: .[0:160],
                           due: ((capture("^ *- \\[ \\] [a-z]+ \\(from (?<d>[0-9]{4}-[0-9]{2}-[0-9]{2})\\)") | .d)
                                 // (capture("^ *- \\[ \\] [a-z]+ \\((?<n>[0-9]+) days? after deploy\\)") | "deploy+" + .n + "d")
                                 // (capture("^ *- \\[ \\] [a-z]+ \\(dark day (?<s>[a-z0-9-]+)\\)") | "dark-day:" + .s)
                                 // (capture("(?i)\\b(?<n>[0-9]+|an?|one|two|three|four) (?<u>days?|weeks?) after (the )?deploy")
                                     | ({"a": 1, "an": 1, "one": 1, "two": 2, "three": 3, "four": 4}[.n | ascii_downcase] // (.n | tonumber))
                                       * (if .u | test("^(?i)week") then 7 else 1 end)
                                     | "deploy+" + tostring + "d")
                                 // (capture("^ *- \\[ \\] [a-z]+ \\((?<w>[^)]*)\\)") | "when:" + .w)
                                 // (capture("^ *- \\[ \\] [a-z]+:.*\\bon (?<d>[0-9]{4}-[0-9]{2}-[0-9]{2})\\b") | .d)
                                 // "now")}]}'
    done | jq -s .
}

jq -n --arg repo "$REPO" --arg since "$SINCE" --argjson hours "$HOURS" \
    --argjson failed_runs "$(failed_runs)" \
    --argjson stuck_runs "$(stuck_runs)" \
    --argjson disabled_workflows "$(disabled_workflows)" \
    --argjson missed_schedules "$(missed_schedules)" \
    --argjson release_main "$(release_main)" \
    --argjson bot_issues "$(bot_issues)" \
    --argjson plausibility "$(report 'Plausibility check — nightly reports')" \
    --argjson owasp "$(report 'OWASP Top 10 — weekly reports')" \
    --argjson security "$(security)" \
    --argjson pull_requests "$(pull_requests)" \
    --argjson renovate_dashboard "$(renovate_dashboard)" \
    --argjson release "$(release)" \
    --argjson unclosed "$(unclosed)" \
    --argjson after_deploy "$(after_deploy)" \
    '{repo: $repo, since: $since, hours: $hours, failed_runs: $failed_runs, stuck_runs: $stuck_runs,
      disabled_workflows: $disabled_workflows,
      missed_schedules: $missed_schedules, release_main: $release_main,
      bot_issues: $bot_issues, reports: [$plausibility, $owasp], security: $security,
      pull_requests: $pull_requests, renovate_dashboard: $renovate_dashboard, release: $release, unclosed: $unclosed,
      after_deploy: $after_deploy}'
