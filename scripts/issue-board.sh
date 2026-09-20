#!/usr/bin/env bash
#
# issue-board.sh — read and set an issue's Status and Priority on the Event Junkie project board.
#
# Status and Priority are project *fields*, not labels (AGENTS.md § The Backlog), so `gh issue edit`
# cannot touch them: setting one means resolving the project, field, option and item ids, then
# `gh project item-edit`. Every id is resolved at run time, and an issue not yet on the board is added.
#
# Usage:
#   scripts/issue-board.sh show <issue>
#   scripts/issue-board.sh status <issue> <Backlog|Ready|In progress|In review|Blocked|Done>
#   scripts/issue-board.sh priority <issue> <P0|P1|P2>
#   scripts/issue-board.sh batch [file]      # many issues at once; reads stdin when no file
#
# **`status <n> Done` closes the issue** — the project's `Auto-close issue` workflow closes on Done and
# `Item closed` sets Done on close. If a card fails to move after a merge, those settings are the
# first place to look.
#
# **`batch` resolves the project and both fields once**, then at most two mutations per issue.
#
# **Every lookup is a targeted query (#1040).** GitHub prices GraphQL by the nodes a request could
# return, 5,000 points per hour per user: `gh project item-list --limit 500` costs 405 points to find
# one id and `field-list --limit 50` costs 102, so nine board updates exhausted the hour. The queries
# below cost 1 point each. **Do not replace them with `gh project item-list` or `field-list`.**
# **`gh api rate_limit` lies about GraphQL** — it reported `remaining=5000/5000` while writes were
# refused; the truth is `X-Ratelimit-Used` on `gh api graphql --include`.
#
# Batch input is one issue per line, `<issue> [status] [priority]`. A status may contain spaces, so
# the priority is recognised **by shape from the end of the line**. `-` leaves a field untouched, `#`
# starts a comment:
#
#   474 Blocked P2
#   476 In progress        # status only
#   480 - P2               # priority only
#
# Every line is validated **before anything is written**.
set -euo pipefail

case "${1:-}" in
    -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

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

# The project id and every single-select field with its options, in one 1-point query. `first: 20` is
# a real ceiling: a query that could return 500 of anything is what cost 405 points. The `*_of`
# helpers below take this one payload and print from it.
PROJECT_PAYLOAD=""
project_payload() {
    [[ -n "$PROJECT_PAYLOAD" ]] && { printf '%s' "$PROJECT_PAYLOAD"; return 0; }
    # shellcheck disable=SC2016
    # `$org` and `$number` are GraphQL variables bound by `-F`, not shell ones.
    PROJECT_PAYLOAD="$(gh api graphql -f query='
      query($org:String!,$number:Int!){
        organization(login:$org){
          projectV2(number:$number){
            id
            fields(first:20){
              nodes{ ... on ProjectV2SingleSelectField{ id name options{ id name } } }
            }
          }
        }
      }' -F org="$PROJECT_OWNER" -F number="$PROJECT_NUMBER" --jq '
        {id: .data.organization.projectV2.id,
         fields: [.data.organization.projectV2.fields.nodes[] | select(.name)]}')" ||
        die "could not read project #$PROJECT_NUMBER for $PROJECT_OWNER"
    printf '%s' "$PROJECT_PAYLOAD"
}

project_id() { project_payload | jq -r '.id'; }
fields() { project_payload; }

# One issue's board item with its field values, in one 1-point query. Emits the item or nothing, so
# callers test for empty: an issue not on the board is a normal result. `projectItems(first: 20)`
# because an issue can sit on several boards; the filter picks ours by number.
item_lookup() {
    local number="$1"
    # shellcheck disable=SC2016
    # GraphQL variables again, bound by `-F`; single quotes keep the shell out.
    gh api graphql -f query='
      query($owner:String!,$repo:String!,$number:Int!){
        repository(owner:$owner,name:$repo){
          issue(number:$number){
            projectItems(first:20){
              nodes{
                id
                project{ number }
                fieldValues(first:20){
                  nodes{
                    ... on ProjectV2ItemFieldSingleSelectValue{
                      name
                      field{ ... on ProjectV2SingleSelectField{ name } }
                    }
                  }
                }
              }
            }
          }
        }
      }' -F owner="${REPO%%/*}" -F repo="${REPO#*/}" -F number="$number" \
        --jq ".data.repository.issue.projectItems.nodes[]
              | select(.project.number == $PROJECT_NUMBER)" 2>/dev/null || true
}

# The board item, adding the issue on a miss. `item-add` is idempotent on the API side but churns
# `updatedAt`, so it runs only when the lookup misses.
item_id() {
    local number="$1" id
    id="$(item_lookup "$number" | jq -r '.id // empty')"
    if [[ -z "$id" ]]; then
        id="$(gh project item-add "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" \
            --url "https://github.com/$REPO/issues/$number" --format json --jq '.id')"
    fi
    printf '%s' "$id"
}

# Pure printers that emit nothing on no match, rather than calling `die`: inside a command
# substitution an `exit` would only leave the subshell. Validation stays in the caller's scope.
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

# The manifest carries the short code; the board's option labels carry their meaning.
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
# An issue not on the board is added, and the new id is folded back into BATCH_ITEMS so a second
# line for the same issue is a lookup.
BATCH_ITEMS='{"items":[]}'

# The field separator must NOT be a tab: tab is IFS *whitespace*, so `read` collapses runs and
# discards empty fields, and "480 - P0" would write the priority's option id into Status. A unit
# separator is not IFS whitespace and cannot occur in an option label.
US=$'\037'

# Resolves an issue's board item into BATCH_ITEM_ID, adding it on a miss. It *assigns* rather than
# prints, because a command substitution runs in a subshell and the BATCH_ITEMS update would be
# thrown away with it.
BATCH_ITEM_ID=""
batch_item_id() {
    local number="$1"
    # The cache holds only ids this run added; a lookup miss on a board of any size is one point.
    BATCH_ITEM_ID="$(jq -r --argjson n "$number" \
        'first(.items[] | select(.content.number == $n) | .id) // empty' <<<"$BATCH_ITEMS")"
    [[ -n "$BATCH_ITEM_ID" ]] || BATCH_ITEM_ID="$(item_lookup "$number" | jq -r '.id // empty')"
    if [[ -z "$BATCH_ITEM_ID" ]]; then
        BATCH_ITEM_ID="$(gh project item-add "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" \
            --url "https://github.com/$REPO/issues/$number" --format json --jq '.id')"
        BATCH_ITEMS="$(jq --argjson n "$number" --arg id "$BATCH_ITEM_ID" \
            '.items += [{ id: $id, content: { number: $n } }]' <<<"$BATCH_ITEMS")"
    fi
}

# One input line into the b_* globals; returns 1 on a blank or comment-only line. The priority is read
# off the *end*, because a status can be two words.
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

    # Pass 1 — parse and validate everything. No writes.
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

    # Pass 2 — apply. Two ids resolved for the run, two mutations per issue at most.
    pid="$(project_id)"

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
    # The same 1-point query the writes use.
    item_lookup "$number" | jq -r '
        (.fieldValues.nodes | map(select(.field.name) | {key: .field.name, value: .name}) | from_entries) as $v |
        "  status    \($v.Status // "—")\n  priority  \($v.Priority // "—")"'
}

main() {
    local cmd="${1:-}"
    [[ -n "$cmd" ]] ||
        die "usage: issue-board.sh <show|status|priority> <issue> [value] | issue-board.sh batch [file]"

    # `batch` takes a file rather than an issue number, so it is dispatched before the issue-number check.
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
