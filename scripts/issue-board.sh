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
#   scripts/issue-board.sh batch [file]      # many issues at once; reads stdin when no file
#
# An issue that is not yet on the board is added to it. Nothing is hardcoded: every id is
# resolved at run time, so renaming an option in the UI does not silently break this.
#
# ## Why `batch` exists
#
# The single-issue path re-resolves the project, the field, the option and the whole item list on
# *every* call — four to five GraphQL requests each, and the item list is the expensive one. That
# is the right trade for one edit and the wrong one for sixteen.
#
# Learned the expensive way (2026-08-13): sixteen back-to-back invocations tripped GitHub's
# **secondary** rate limiter, the one that governs mutations. It fails in a way that reads as a
# bug rather than a limit — `gh` reports "API rate limit exceeded" while `gh api rate_limit` still
# shows thousands of points remaining, because the primary points budget and the secondary write
# limiter are counted separately. `batch` resolves everything once and then issues at most two
# mutations per issue.
#
# ## Batch input
#
# One issue per line: `<issue> [status] [priority]`. A status may contain spaces ("In progress"),
# so the priority is recognised **by shape from the end of the line** rather than by position.
# `-` in either slot leaves that field untouched, `#` starts a comment, blank lines are skipped.
#
#   474 Blocked P2
#   475 Ready P1
#   476 In progress        # status only — priority left as it is
#   480 - P2               # priority only
#
# Every line is parsed and validated **before anything is written**, so a typo on the last line
# does not leave the first thirty applied and the rest not.

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

# Field and option lookups against an already-fetched `fields` payload. Deliberately pure
# printers that emit nothing when there is no match, rather than calling `die` themselves: they
# are used inside command substitutions, where an `exit` would only leave the subshell and the
# failure would pass silently. Validation therefore stays in the caller's own scope.
field_id_of() { # field_id_of <fields-json> <field-name>
    jq -r --arg f "$2" '.fields[] | select(.name==$f) | .id' <<<"$1"
}
option_id_of() { # option_id_of <fields-json> <field-name> <option-name>
    jq -r --arg f "$2" --arg o "$3" \
        '.fields[] | select(.name==$f) | .options[] | select(.name==$o) | .id' <<<"$1"
}
option_names_of() { # option_names_of <fields-json> <field-name>
    jq -r --arg f "$2" '[.fields[] | select(.name==$f) | .options[].name] | join(", ")' <<<"$1"
}

set_field() {
    local number="$1" field_name="$2" option_name="$3"
    local all field_id option_id
    all="$(fields)"
    field_id="$(field_id_of "$all" "$field_name")"
    [[ -n "$field_id" ]] || die "no '$field_name' field on the project"
    option_id="$(option_id_of "$all" "$field_name" "$option_name")"
    [[ -n "$option_id" ]] || die "no '$option_name' option on '$field_name' — valid: $(
        option_names_of "$all" "$field_name"
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

# --- batch -------------------------------------------------------------------------------------
#
# The item list is fetched once into BATCH_ITEMS and then treated as a local index. An issue that
# is not on the board is added, and the new id is folded back into the index so a second line for
# the same issue is a lookup rather than another `item-add`.
BATCH_ITEMS=""

# The field separator for a parsed row. It must NOT be a tab: tab is IFS *whitespace*, so `read`
# collapses runs of it and discards empty fields — which silently shifts every column after an
# omitted one, and a row like "480 - P0" ends up writing the priority's option id into the Status
# field. A unit separator is not IFS whitespace, so empty fields survive, and it cannot occur in
# an option label.
US=$'\037'

# Resolves an issue's board item into BATCH_ITEM_ID, adding it to the board on a miss.
#
# It *assigns* rather than prints, which looks like the awkward choice and is the load-bearing
# one: the caller would have to write `item="$(batch_item_id …)"`, and a command substitution runs
# in a subshell, so the BATCH_ITEMS update below would be thrown away with it. Every repeated
# issue would then `item-add` again — the exact per-issue round trip this mode exists to avoid.
BATCH_ITEM_ID=""
batch_item_id() {
    local number="$1"
    BATCH_ITEM_ID="$(jq -r --argjson n "$number" \
        'first(.items[] | select(.content.number == $n) | .id) // empty' <<<"$BATCH_ITEMS")"
    if [[ -z "$BATCH_ITEM_ID" ]]; then
        BATCH_ITEM_ID="$(gh project item-add "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" \
            --url "https://github.com/$REPO/issues/$number" --format json --jq '.id')"
        BATCH_ITEMS="$(jq --argjson n "$number" --arg id "$BATCH_ITEM_ID" \
            '.items += [{ id: $id, content: { number: $n } }]' <<<"$BATCH_ITEMS")"
    fi
}

# One input line into the b_* globals. Returns 1 when the line holds nothing (blank, or a comment
# only) so the caller skips it without treating it as an error.
#
# The parse reads the priority off the *end* rather than by field position, because a status can
# be two words: "476 In progress P1" has to split as (476, "In progress", P1), which no
# whitespace-column scheme gets right.
parse_batch_line() {
    local line="$1" rest last
    b_number=""
    b_status=""
    b_priority=""

    line="${line%%#*}"
    line="$(printf '%s' "$line" | tr -s '[:space:]' ' ')" # collapse runs, so columns may be aligned
    line="${line# }"
    line="${line% }"
    [[ -n "$line" ]] || return 1

    b_number="${line%% *}"
    rest=""
    [[ "$line" == *" "* ]] && rest="${line#* }"

    last="${rest##* }"
    if [[ "$last" =~ ^[Pp][0-9]$ ]]; then
        b_priority="$last"
        if [[ "$rest" == *" "* ]]; then rest="${rest% *}"; else rest=""; fi
    fi

    b_status="$rest"
    [[ "$b_status" != "-" ]] || b_status=""
    return 0
}

batch() {
    local input="${1:-}"
    [[ -n "$input" && "$input" != "-" ]] || input=/dev/stdin
    [[ "$input" == /dev/stdin || -r "$input" ]] || die "cannot read '$input'"

    local all pid status_field priority_field
    all="$(fields)"
    status_field="$(field_id_of "$all" "Status")"
    [[ -n "$status_field" ]] || die "no 'Status' field on the project"
    priority_field="$(field_id_of "$all" "Priority")"
    [[ -n "$priority_field" ]] || die "no 'Priority' field on the project"

    # Pass 1 — parse and validate everything. No writes, so a bad line costs nothing.
    local -a rows=()
    local line lineno=0 status_option priority_option_id priority_label
    while IFS= read -r line || [[ -n "$line" ]]; do
        lineno=$((lineno + 1))
        parse_batch_line "$line" || continue
        [[ "$b_number" =~ ^[0-9]+$ ]] || die "line $lineno: '$b_number' is not an issue number"
        [[ -n "$b_status" || -n "$b_priority" ]] ||
            die "line $lineno: #$b_number has neither a status nor a priority"

        status_option=""
        if [[ -n "$b_status" ]]; then
            status_option="$(option_id_of "$all" "Status" "$b_status")"
            [[ -n "$status_option" ]] ||
                die "line $lineno: no '$b_status' option on 'Status' — valid: $(option_names_of "$all" "Status")"
        fi

        priority_option_id=""
        priority_label=""
        if [[ -n "$b_priority" ]]; then
            priority_label="$(priority_option "$b_priority")"
            priority_option_id="$(option_id_of "$all" "Priority" "$priority_label")"
            [[ -n "$priority_option_id" ]] ||
                die "line $lineno: no '$priority_label' option on 'Priority' — valid: $(option_names_of "$all" "Priority")"
        fi

        rows+=("$(printf '%s%s%s%s%s%s%s%s%s' \
            "$b_number" "$US" "$status_option" "$US" "$priority_option_id" "$US" \
            "$b_status" "$US" "$priority_label")")
    done <"$input"

    [[ ${#rows[@]} -gt 0 ]] || die "no issues in the input"

    # Pass 2 — apply. Two ids resolved for the whole run, two mutations per issue at most.
    pid="$(project_id)"
    BATCH_ITEMS="$(gh project item-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" \
        --limit 500 --format json)"

    local row number s_opt p_opt s_label p_label item
    for row in "${rows[@]}"; do
        IFS="$US" read -r number s_opt p_opt s_label p_label <<<"$row"
        batch_item_id "$number"
        item="$BATCH_ITEM_ID"
        [[ -n "$item" ]] || die "#$number could not be resolved on the board"

        if [[ -n "$s_opt" ]]; then
            gh project item-edit --id "$item" --project-id "$pid" \
                --field-id "$status_field" --single-select-option-id "$s_opt" >/dev/null
            printf '#%s Status -> %s\n' "$number" "$s_label"
        fi
        if [[ -n "$p_opt" ]]; then
            gh project item-edit --id "$item" --project-id "$pid" \
                --field-id "$priority_field" --single-select-option-id "$p_opt" >/dev/null
            printf '#%s Priority -> %s\n' "$number" "$p_label"
        fi
    done

    printf '%s issue(s) updated\n' "${#rows[@]}"
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
    local cmd="${1:-}"
    [[ -n "$cmd" ]] ||
        die "usage: issue-board.sh <show|status|priority> <issue> [value] | issue-board.sh batch [file]"

    # `batch` takes a file rather than an issue number, so it is dispatched before the
    # issue-number check the other three share.
    case "$cmd" in
        batch | --batch)
            shift
            batch "${1:-}"
            return
            ;;
    esac

    local number="${2:-}"
    [[ -n "$number" ]] || die "usage: issue-board.sh <show|status|priority> <issue> [value]"
    [[ "$number" =~ ^[0-9]+$ ]] || die "'$number' is not an issue number"

    case "$cmd" in
        show) show "$number" ;;
        status) set_field "$number" "Status" "${3:?a status is required}" ;;
        priority) set_field "$number" "Priority" "$(priority_option "${3:?a priority is required}")" ;;
        *) die "unknown command '$cmd' — try show, status, priority, batch" ;;
    esac
}

main "$@"
