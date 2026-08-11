#!/usr/bin/env bash
#
# issue-board.sh — read and set an issue's Status and Priority on the Event Junkie project board.
#
# Status and Priority are project *fields*, not labels — a deliberate split (AGENTS.md § The
# Backlog): intrinsic properties of the work are labels, planning state lives on the board,
# because priority churns and label churn is noise.
#
# The cost of that split is that `gh issue edit` cannot touch either one. Setting them means
# resolving the project id, the field id, the option id and the item id, then calling
# `gh project item-edit` — four lookups that nobody should retype. Hence this script.
#
# Usage:
#   scripts/issue-board.sh show <issue>
#   scripts/issue-board.sh status <issue> <Backlog|Ready|In progress|In review|Blocked|Done>
#   scripts/issue-board.sh priority <issue> <P0|P1|P2>
#
# An issue that is not yet on the board is added to it. Nothing is hardcoded: every id is
# resolved at run time, so renaming an option in the UI does not silently break this.

set -euo pipefail

REPO="${BACKLOG_REPO:-enorm-labs/event-junkie}"
PROJECT_OWNER="enorm-labs"
PROJECT_NUMBER=1

die() {
    printf '\033[1;31mxx\033[0m %s\n' "$*" >&2
    exit 1
}
need() { command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not installed"; }

need gh
need jq

project_id() { gh project view "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json --jq '.id'; }
fields() { gh project field-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --limit 50 --format json; }

# The board item for an issue, adding the issue to the board if it is not there yet. `item-add`
# is idempotent on the API side, but calling it unconditionally would churn the item's updatedAt
# on every status change, so it only runs when the lookup misses.
item_id() {
    local number="$1" id
    id="$(gh project item-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --limit 500 --format json |
        jq -r --argjson n "$number" '.items[] | select(.content.number == $n) | .id')"
    if [[ -z "$id" ]]; then
        id="$(gh project item-add "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" \
            --url "https://github.com/$REPO/issues/$number" --format json --jq '.id')"
    fi
    printf '%s' "$id"
}

set_field() {
    local number="$1" field_name="$2" option_name="$3"
    local all field_id option_id
    all="$(fields)"
    field_id="$(jq -r --arg f "$field_name" '.fields[] | select(.name==$f) | .id' <<<"$all")"
    [[ -n "$field_id" ]] || die "no '$field_name' field on the project"
    option_id="$(jq -r --arg f "$field_name" --arg o "$option_name" \
        '.fields[] | select(.name==$f) | .options[] | select(.name==$o) | .id' <<<"$all")"
    [[ -n "$option_id" ]] || die "no '$option_name' option on '$field_name' — valid: $(
        jq -r --arg f "$field_name" '[.fields[] | select(.name==$f) | .options[].name] | join(", ")' <<<"$all"
    )"

    gh project item-edit --id "$(item_id "$number")" --project-id "$(project_id)" \
        --field-id "$field_id" --single-select-option-id "$option_id" >/dev/null
    printf '#%s %s -> %s\n' "$number" "$field_name" "$option_name"
}

# The manifest carries the short code; the board's option labels carry their meaning. Keeping the
# mapping here means a reworded option is a one-line change in one place.
priority_option() {
    case "$1" in
        P0 | p0) printf 'P0 — now' ;;
        P1 | p1) printf 'P1 — next' ;;
        P2 | p2) printf 'P2 — later' ;;
        *) printf '%s' "$1" ;;
    esac
}

show() {
    local number="$1"
    gh issue view "$number" --repo "$REPO" \
        --json number,title,state,issueType,labels,milestone,assignees \
        --jq '"#\(.number) \(.title)
  state     \(.state)
  type      \(.issueType.name // "—")
  milestone \(.milestone.title // "—")
  labels    \([.labels[].name] | join(", "))
  assignee  \([.assignees[].login] | join(", ") | if . == "" then "—" else . end)"'
    gh project item-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --limit 500 --format json |
        jq -r --argjson n "$number" '.items[] | select(.content.number == $n) |
          "  status    \(.status // "—")\n  priority  \(.priority // "—")"'
}

main() {
    local cmd="${1:-}" number="${2:-}"
    [[ -n "$cmd" && -n "$number" ]] || die "usage: issue-board.sh <show|status|priority> <issue> [value]"
    [[ "$number" =~ ^[0-9]+$ ]] || die "'$number' is not an issue number"

    case "$cmd" in
        show) show "$number" ;;
        status) set_field "$number" "Status" "${3:?a status is required}" ;;
        priority) set_field "$number" "Priority" "$(priority_option "${3:?a priority is required}")" ;;
        *) die "unknown command '$cmd' — try show, status, priority" ;;
    esac
}

main "$@"
