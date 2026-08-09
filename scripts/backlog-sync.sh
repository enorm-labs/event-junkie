#!/usr/bin/env bash
#
# backlog-sync.sh — turn the reviewable manifest in .github/backlog/ into GitHub issues.
#
# The one-time migration of TODO.md into the issue tracker (docs/GITHUB_ISSUES_MIGRATION.md)
# runs through this script rather than through 120 hand-typed `gh issue create` calls, so the
# whole backlog is reviewable as a PR diff *before* anything is created, and so a run that
# dies halfway can be resumed instead of restarted.
#
# Usage: scripts/backlog-sync.sh <command> [options]
#
#   validate            Parse and check every manifest file. Changes nothing. Runs in CI.
#   plan                Print what apply would create and update. The default.
#   apply               Create or update issues, in filename order
#   link                Re-render bodies with resolved cross-links, attach sub-issues
#   project             Add every known issue to the project, set Status and Priority
#   report              Print the slug -> issue-number mapping
#   preview --only SLUG Print the exact body that would be posted, footer and all
#
# Options:
#   --limit N           Process at most N manifest files (apply/link/project). For a cautious
#                       first run: `apply --limit 5`, look at the result, then run the rest.
#   --only SLUG         Process just this one slug
#
# Environment:
#   BACKLOG_OFFLINE=1   Skip the checks that need the API (label/milestone existence). Lets
#                       `validate` run without credentials; everything else still needs them.
#
# Manifest format — one markdown file per issue, .github/backlog/NNNN-<slug>.md. The numeric
# prefix is the creation order, so issue numbers come out in rough priority order. YAML front
# matter, markdown body:
#
#   ---
#   slug: importer-bug-late-night-drop     # unique, stable, kebab-case
#   title: A late-night club event is dropped at midnight
#   type: Task | Bug | Feature             # maps to the org's GitHub issue types
#   milestone: v0.2 — Deployable           # omit or null for the unscheduled backlog
#   labels: [importer, "area:data-quality", "size:M"]
#   priority: P0 | P1 | P2                 # project field, not a label
#   status: Backlog                        # project field; defaults to Backlog
#   parent: some-epic-slug                 # attaches as a GitHub sub-issue
#   related: [other-slug]                  # rendered as a Links footer
#   blocked-by: [other-slug]               # rendered as a Links footer; also wants a
#   ---                                    #   blocked/needs-* label, which validate checks for
#
#   The markdown body starts here.
#
# The identifier field is `slug`, not `key`, for two reasons: it is the word this codebase
# already uses for a stable url-safe name, and gitleaks' entropy-based `generic-api-key` rule
# fires on `key: <high-entropy-string>` — which it did, on one manifest file out of 146. A
# name that only sometimes trips a secret scanner is worse than one that never does.
#
# Labels quoted in YAML on purpose: a bare colon inside a scalar is a parse error, which is
# exactly the mistake a hand-rolled `field: value` splitter would have swallowed in silence.
#
# The body is deliberately NOT inside the YAML. It is the bulk of every file and it is
# markdown — kept outside, editors lint and render it, code fences need no re-indenting, and
# a body edit is a clean one-issue diff.
#
# Idempotency: .github/backlog/.created.json maps slug -> issue number and is committed. A
# slug with a number is updated in place, never recreated. It is written after EVERY issue,
# not at the end, so an interrupted run resumes exactly where it stopped.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MANIFEST_DIR="$REPO_ROOT/.github/backlog"
LOCKFILE="$MANIFEST_DIR/.created.json"

REPO="enorm-labs/event-checker"
PROJECT_OWNER="enorm-labs"
PROJECT_NUMBER=1

VALID_TYPES="Task Bug Feature"
VALID_PRIORITIES="P0 P1 P2"
VALID_STATUSES="Backlog|Ready|In progress|In review|Blocked|Done"

# GitHub's secondary rate limit bites well before its documented hourly one. At ~0.45s
# between mutations a 120-issue run takes under two minutes and has never tripped it;
# without the pause, a few hundred back-to-back writes reliably do.
MUTATION_SLEEP="${BACKLOG_MUTATION_SLEEP:-0.45}"

LIMIT=0
ONLY=""

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33m!!\033[0m %s\n' "$*" >&2; }
die() {
    printf '\033[1;31mxx\033[0m %s\n' "$*" >&2
    exit 1
}
need() { command -v "$1" >/dev/null 2>&1 || die "'$1' is required but not installed (try: brew install $1)"; }

offline() { [[ "${BACKLOG_OFFLINE:-0}" == "1" ]]; }

# ---------------------------------------------------------------- manifest parsing

manifest_files() {
    local f
    for f in "$MANIFEST_DIR"/[0-9]*.md; do
        [[ -e "$f" ]] || continue
        if [[ -n "$ONLY" ]]; then
            [[ "$(meta "$f" '.slug')" == "$ONLY" ]] || continue
        fi
        printf '%s\n' "$f"
    done
}

# Front matter as JSON. yq's --front-matter=extract reads only the fenced block.
meta_json() { yq --front-matter=extract -o=json '.' "$1"; }

# A single scalar field, or "" when absent/null.
meta() { yq --front-matter=extract -r "${2} // \"\"" "$1"; }

# A list field, one item per line. Absent or null yields nothing.
meta_list() { yq --front-matter=extract -r "(${2} // []) | .[]" "$1" 2>/dev/null || true; }

# Everything after the closing front-matter fence.
body_of() {
    awk 'NR==1 && $0=="---" {infm=1; next}
         infm && $0=="---"   {infm=0; started=1; next}
         started' "$1"
}

# ---------------------------------------------------------------- lockfile

lock_read() {
    [[ -f "$LOCKFILE" ]] || printf '{}' >"$LOCKFILE"
    cat "$LOCKFILE"
}

issue_for() { lock_read | jq -r --arg k "$1" '.[$k] // ""'; }

lock_write() {
    local slug="$1" number="$2" tmp
    tmp="$(mktemp)"
    lock_read | jq --arg k "$slug" --argjson n "$number" '.[$k] = $n' |
        jq -S '.' >"$tmp"
    mv "$tmp" "$LOCKFILE"
}

# ---------------------------------------------------------------- body rendering

# Issue body = the manifest body plus a generated Links footer. Both `apply` and `link` use
# this, so running `link` is simply "render again, now that every number is known" — there is
# no separate half-rendered state to reason about.
render_body() {
    local file="$1" parent related blocked line footer=""

    parent="$(meta "$file" '.parent')"
    related="$(meta_list "$file" '.related')"
    blocked="$(meta_list "$file" '.["blocked-by"]')"

    if [[ -n "$parent$related$blocked" ]]; then
        footer=$'\n\n---\n\n### Links\n'
        if [[ -n "$parent" ]]; then
            footer+=$'\n'"- **Parent:** $(ref "$parent")"
        fi
        while IFS= read -r line; do
            [[ -n "$line" ]] || continue
            footer+=$'\n'"- **Blocked by:** $(ref "$line")"
        done <<<"$blocked"
        while IFS= read -r line; do
            [[ -n "$line" ]] || continue
            footer+=$'\n'"- **Related:** $(ref "$line")"
        done <<<"$related"
    fi

    printf '%s%s\n' "$(body_of "$file")" "$footer"
}

# A manifest slug rendered as an issue reference — a number once it exists, the slug itself
# until then, so a partially-applied run still produces a readable body.
ref() {
    local n
    n="$(issue_for "$1")"
    if [[ -n "$n" ]]; then printf '#%s' "$n"; else printf '`%s` *(not yet created)*' "$1"; fi
}

# ---------------------------------------------------------------- validate

validate() {
    local file slug title type milestone priority status parent label line
    local errors=0 count=0
    local -a slugs=()
    # A newline-delimited string rather than an associative array: /bin/bash on macOS is
    # still 3.2, which has neither, and this script should not depend on which bash wins
    # the PATH race.
    local seen=""

    local repo_labels="" repo_milestones=""
    if ! offline; then
        repo_labels="$(gh label list --repo "$REPO" --limit 200 --json name --jq '.[].name')"
        repo_milestones="$(gh api "repos/$REPO/milestones?state=all&per_page=100" --jq '.[].title')"
    fi

    # Pass 1 — every file on its own, and collect the slug set for pass 2.
    while IFS= read -r file; do
        count=$((count + 1))
        local where="${file#"$REPO_ROOT"/}"

        if ! meta_json "$file" >/dev/null 2>&1; then
            warn "$where: front matter is not valid YAML"
            errors=$((errors + 1))
            continue
        fi

        slug="$(meta "$file" '.slug')"
        title="$(meta "$file" '.title')"
        type="$(meta "$file" '.type')"
        milestone="$(meta "$file" '.milestone')"
        priority="$(meta "$file" '.priority')"
        status="$(meta "$file" '.status')"

        [[ -n "$slug" ]] || {
            warn "$where: missing 'slug'"
            errors=$((errors + 1))
        }
        [[ -n "$title" ]] || {
            warn "$where: missing 'title'"
            errors=$((errors + 1))
        }
        [[ -n "$(body_of "$file" | tr -d '[:space:]')" ]] || {
            warn "$where: body is empty"
            errors=$((errors + 1))
        }

        if [[ -n "$slug" ]]; then
            [[ "$slug" =~ ^[a-z0-9]+(-[a-z0-9]+)*$ ]] || {
                warn "$where: slug '$slug' is not kebab-case"
                errors=$((errors + 1))
            }
            if grep -qxF -- "$slug" <<<"$seen"; then
                warn "$where: duplicate slug '$slug'"
                errors=$((errors + 1))
            fi
            seen+="$slug"$'\n'
            slugs+=("$slug")
            # The filename should carry the slug, so an issue is findable by name.
            [[ "$(basename "$file")" == *"$slug.md" ]] ||
                warn "$where: filename does not end in '$slug.md' (cosmetic, not fatal)"
        fi

        if [[ -n "$type" ]]; then
            grep -qw -- "$type" <<<"$VALID_TYPES" || {
                warn "$where: type '$type' is not one of: $VALID_TYPES"
                errors=$((errors + 1))
            }
        else
            warn "$where: missing 'type'"
            errors=$((errors + 1))
        fi

        if [[ -n "$priority" ]]; then
            grep -qw -- "$priority" <<<"$VALID_PRIORITIES" || {
                warn "$where: priority '$priority' is not one of: $VALID_PRIORITIES"
                errors=$((errors + 1))
            }
        fi

        if [[ -n "$status" ]]; then
            grep -qx -- "$status" <<<"${VALID_STATUSES//|/$'\n'}" || {
                warn "$where: status '$status' is not a project Status option"
                errors=$((errors + 1))
            }
        fi

        if [[ -n "$milestone" ]] && ! offline; then
            grep -qxF -- "$milestone" <<<"$repo_milestones" || {
                warn "$where: milestone '$milestone' does not exist in $REPO"
                errors=$((errors + 1))
            }
        fi

        if ! offline; then
            while IFS= read -r label; do
                [[ -n "$label" ]] || continue
                grep -qxF -- "$label" <<<"$repo_labels" || {
                    warn "$where: label '$label' does not exist in $REPO"
                    errors=$((errors + 1))
                }
            done < <(meta_list "$file" '.labels')
        fi

        # A blocked-by with no state label is invisible in the one board view built to surface
        # exactly this. The Blocked view filters on any of the three, so `needs-deployment` or
        # `needs-decision` satisfies it just as well as `blocked` — they name *why*.
        if [[ -n "$(meta_list "$file" '.["blocked-by"]')" ]]; then
            meta_list "$file" '.labels' | grep -qxE 'blocked|needs-decision|needs-deployment' ||
                warn "$where: has blocked-by but no blocked/needs-decision/needs-deployment label — it will not show in the Blocked view"
        fi
    done < <(manifest_files)

    # Pass 2 — cross-references, now that the whole slug set is known.
    local known
    known="$(printf '%s\n' "${slugs[@]}")"
    while IFS= read -r file; do
        local where="${file#"$REPO_ROOT"/}"
        # Pass 1 already reported unparseable files. Skipping them here means one bad file
        # yields one error rather than aborting the run and hiding every problem after it.
        meta_json "$file" >/dev/null 2>&1 || continue
        slug="$(meta "$file" '.slug')"
        parent="$(meta "$file" '.parent')"

        for field in parent related 'blocked-by'; do
            local refs
            if [[ "$field" == "parent" ]]; then refs="$parent"; else
                refs="$(meta_list "$file" ".[\"$field\"]")"
            fi
            while IFS= read -r line; do
                [[ -n "$line" ]] || continue
                grep -qxF -- "$line" <<<"$known" || {
                    warn "$where: $field '$line' is not a known slug"
                    errors=$((errors + 1))
                }
                [[ "$line" != "$slug" ]] || {
                    warn "$where: $field refers to itself"
                    errors=$((errors + 1))
                }
            done <<<"$refs"
        done

        # Sub-issues nest one level only, so a parent may not itself have a parent.
        if [[ -n "$parent" ]]; then
            local grandparent
            grandparent="$(grep -rl "^slug: $parent$" "$MANIFEST_DIR"/[0-9]*.md 2>/dev/null | head -1)"
            if [[ -n "$grandparent" && -n "$(meta "$grandparent" '.parent')" ]]; then
                warn "$where: parent '$parent' is itself a child — sub-issues nest one level only"
                errors=$((errors + 1))
            fi
        fi
    done < <(manifest_files)

    if ((errors > 0)); then
        die "$errors problem(s) across $count manifest file(s)"
    fi
    log "$count manifest file(s), no problems"
}

# ---------------------------------------------------------------- plan / apply

plan() {
    local file slug number create=0 update=0
    printf '%-6s %-42s %-9s %s\n' 'ACTION' 'SLUG' 'ISSUE' 'TITLE'
    while IFS= read -r file; do
        slug="$(meta "$file" '.slug')"
        number="$(issue_for "$slug")"
        if [[ -n "$number" ]]; then
            printf '%-6s %-42s %-9s %s\n' 'update' "$slug" "#$number" "$(meta "$file" '.title')"
            update=$((update + 1))
        else
            printf '%-6s %-42s %-9s %s\n' 'create' "$slug" '—' "$(meta "$file" '.title')"
            create=$((create + 1))
        fi
    done < <(limited)
    log "$create to create, $update to update"
}

limited() {
    if ((LIMIT > 0)); then manifest_files | head -n "$LIMIT"; else manifest_files; fi
}

apply() {
    require_clean_lockfile
    local file slug title type milestone number label args i=0

    while IFS= read -r file; do
        slug="$(meta "$file" '.slug')"
        title="$(meta "$file" '.title')"
        type="$(meta "$file" '.type')"
        milestone="$(meta "$file" '.milestone')"
        number="$(issue_for "$slug")"

        # `gh issue create` and `gh issue edit` do NOT share a label flag: create takes
        # --label, edit takes --add-label/--remove-label. Building one arg list for both
        # fails on the first update, which is exactly how this was found.
        args=(--repo "$REPO" --title "$title" --body-file -)
        [[ -n "$type" ]] && args+=(--type "$type")
        [[ -n "$milestone" ]] && args+=(--milestone "$milestone")

        if [[ -n "$number" ]]; then
            # The manifest is authoritative, so an update has to reconcile labels in both
            # directions. --add-label alone would let a label dropped from the manifest
            # survive on the issue forever, and the drift would be invisible.
            local current wanted
            wanted="$(meta_list "$file" '.labels' | sort)"
            current="$(gh issue view "$number" --repo "$REPO" --json labels --jq '.labels[].name' | sort)"
            while IFS= read -r label; do
                [[ -n "$label" ]] && args+=(--add-label "$label")
            done < <(comm -23 <(printf '%s\n' "$wanted") <(printf '%s\n' "$current"))
            while IFS= read -r label; do
                [[ -n "$label" ]] && args+=(--remove-label "$label")
            done < <(comm -13 <(printf '%s\n' "$wanted") <(printf '%s\n' "$current"))

            render_body "$file" | gh issue edit "$number" "${args[@]}" >/dev/null
            printf '  update #%-6s %s\n' "$number" "$slug"
        else
            while IFS= read -r label; do
                [[ -n "$label" ]] && args+=(--label "$label")
            done < <(meta_list "$file" '.labels')

            local url
            url="$(render_body "$file" | gh issue create "${args[@]}")"
            number="${url##*/}"
            [[ "$number" =~ ^[0-9]+$ ]] || die "could not read an issue number out of '$url'"
            lock_write "$slug" "$number"
            printf '  create #%-6s %s\n' "$number" "$slug"
        fi
        i=$((i + 1))
        sleep "$MUTATION_SLEEP"
    done < <(limited)

    log "$i issue(s) processed — .created.json updated, commit it"
}

# An uncommitted lockfile means a previous run was interrupted and its result was never
# reviewed. Applying on top of that silently buries whatever went wrong.
require_clean_lockfile() {
    if [[ -f "$LOCKFILE" ]] && ! git -C "$REPO_ROOT" diff --quiet -- "$LOCKFILE" 2>/dev/null; then
        die ".created.json has uncommitted changes — review and commit them before applying again"
    fi
}

# ---------------------------------------------------------------- link

link() {
    local file slug number parent parent_number i=0

    log 'Re-rendering bodies with resolved cross-links'
    while IFS= read -r file; do
        slug="$(meta "$file" '.slug')"
        number="$(issue_for "$slug")"
        [[ -n "$number" ]] || {
            warn "$slug has no issue yet — run apply first"
            continue
        }
        [[ -n "$(meta "$file" '.parent')$(meta_list "$file" '.related')$(meta_list "$file" '.["blocked-by"]')" ]] || continue
        render_body "$file" | gh issue edit "$number" --repo "$REPO" --body-file - >/dev/null
        printf '  links  #%-6s %s\n' "$number" "$slug"
        i=$((i + 1))
        sleep "$MUTATION_SLEEP"
    done < <(limited)

    log 'Attaching sub-issues'
    while IFS= read -r file; do
        parent="$(meta "$file" '.parent')"
        [[ -n "$parent" ]] || continue
        slug="$(meta "$file" '.slug')"
        number="$(issue_for "$slug")"
        parent_number="$(issue_for "$parent")"
        [[ -n "$number" && -n "$parent_number" ]] || {
            warn "$slug: parent or child not created yet"
            continue
        }
        add_sub_issue "$parent_number" "$number" && printf '  sub    #%-6s -> #%s\n' "$number" "$parent_number"
        sleep "$MUTATION_SLEEP"
    done < <(limited)

    log "$i body/bodies re-rendered"
}

node_id() { gh api "repos/$REPO/issues/$1" --jq '.node_id'; }

add_sub_issue() {
    local parent_id child_id
    parent_id="$(node_id "$1")"
    child_id="$(node_id "$2")"
    gh api graphql -f query="
      mutation {
        addSubIssue(input:{issueId:\"$parent_id\", subIssueId:\"$child_id\"}) {
          issue { number }
        }
      }" >/dev/null 2>&1 || {
        warn "could not attach #$2 under #$1 (already attached?)"
        return 1
    }
}

# ---------------------------------------------------------------- project

project() {
    need jq
    local project_id status_field priority_field fields
    fields="$(gh project field-list "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json)"
    project_id="$(gh project view "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" --format json --jq '.id')"
    status_field="$(jq -r '.fields[] | select(.name=="Status")' <<<"$fields")"
    priority_field="$(jq -r '.fields[] | select(.name=="Priority")' <<<"$fields")"

    local file slug number status priority item_id i=0
    while IFS= read -r file; do
        slug="$(meta "$file" '.slug')"
        number="$(issue_for "$slug")"
        [[ -n "$number" ]] || continue
        status="$(meta "$file" '.status')"
        priority="$(meta "$file" '.priority')"
        [[ -n "$status" ]] || status="Backlog"

        item_id="$(gh project item-add "$PROJECT_NUMBER" --owner "$PROJECT_OWNER" \
            --url "https://github.com/$REPO/issues/$number" --format json --jq '.id')"

        set_select "$project_id" "$item_id" "$status_field" "$status"
        [[ -n "$priority" ]] && set_select "$project_id" "$item_id" "$priority_field" "$(priority_option "$priority")"

        printf '  board  #%-6s %-10s %s\n' "$number" "${priority:-—}" "$slug"
        i=$((i + 1))
        sleep "$MUTATION_SLEEP"
    done < <(limited)
    log "$i issue(s) on the board"
}

# The project's option labels carry their meaning ("P0 — now"); the manifest carries the
# short code, so nothing has to be re-typed if the wording of an option is ever softened.
priority_option() {
    case "$1" in
        P0) printf 'P0 — now' ;;
        P1) printf 'P1 — next' ;;
        P2) printf 'P2 — later' ;;
        *) die "unknown priority '$1'" ;;
    esac
}

set_select() {
    local project_id="$1" item_id="$2" field_json="$3" option_name="$4" field_id option_id
    field_id="$(jq -r '.id' <<<"$field_json")"
    option_id="$(jq -r --arg n "$option_name" '.options[] | select(.name==$n) | .id' <<<"$field_json")"
    [[ -n "$option_id" ]] || die "no option '$option_name' on that field"
    gh project item-edit --id "$item_id" --project-id "$project_id" \
        --field-id "$field_id" --single-select-option-id "$option_id" >/dev/null
}

# ---------------------------------------------------------------- report

report() {
    local file slug number
    printf '%-9s %-42s %-28s %s\n' 'ISSUE' 'SLUG' 'MILESTONE' 'TITLE'
    while IFS= read -r file; do
        slug="$(meta "$file" '.slug')"
        number="$(issue_for "$slug")"
        printf '%-9s %-42s %-28s %s\n' "${number:+#$number}" "$slug" \
            "$(meta "$file" '.milestone')" "$(meta "$file" '.title')"
    done < <(manifest_files)
}

# ---------------------------------------------------------------- main

main() {
    need yq
    need jq
    local cmd="${1:-plan}"
    [[ $# -gt 0 ]] && shift

    while [[ $# -gt 0 ]]; do
        case "$1" in
            --limit)
                LIMIT="$2"
                shift 2
                ;;
            --only)
                ONLY="$2"
                shift 2
                ;;
            *) die "unknown option '$1'" ;;
        esac
    done

    [[ -d "$MANIFEST_DIR" ]] || die "no manifest directory at $MANIFEST_DIR"
    # Only an offline `validate` can run without credentials; everything else talks to the API.
    if ! { [[ "$cmd" == "validate" ]] && offline; }; then need gh; fi

    case "$cmd" in
        validate) validate ;;
        plan) plan ;;
        preview)
            local f
            while IFS= read -r f; do
                printf '\033[1;34m--- %s\033[0m\n' "$(meta "$f" '.title')"
                render_body "$f"
            done < <(limited)
            ;;
        apply) apply ;;
        link) link ;;
        project) project ;;
        report) report ;;
        *) die "unknown command '$cmd' — try validate, plan, preview, apply, link, project, report" ;;
    esac
}

main "$@"
