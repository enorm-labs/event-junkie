#!/usr/bin/env bash
#
# comment-lint.sh — the comment rules, for the languages detekt and ESLint cannot see.
#
# Usage:
#   scripts/comment-lint.sh report [--top N]   # list every violation
#   scripts/comment-lint.sh check              # fail if an area has more than the baseline allows
#   scripts/comment-lint.sh update-baseline    # accept the current counts
#
# Covers .tf, .tfvars, .sh, .py, .yaml, .yml. Kotlin is detekt's LongComment; TS and Vue are
# event-junkie/max-comment-lines. See AGENTS.md §Comments and #713.
#
# Silence one block with `# comment-lint: allow <reason>` on the line above it. The reason is not
# optional, and a bare directive is itself a violation.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE="$REPO_ROOT/scripts/comment-lint-baseline.txt"

MAX_BLOCK="${COMMENT_LINT_MAX_BLOCK:-25}"
# A file header is the script's only `--help` in most of these files, and documents an interface
# rather than reasoning interleaved with code. It gets a higher cap, not an exemption — the same
# shape `LongComment/venue` has on the Kotlin side (#714). Density and the area ratchet still bound it.
MAX_HEADER_BLOCK="${COMMENT_LINT_MAX_HEADER_BLOCK:-40}"
HEADER_STARTS_BY=3
MAX_DENSITY="${COMMENT_LINT_MAX_DENSITY:-55}"
EXCLUDE='node_modules/|/build/|/dist/|/coverage/'
# Files where a date is the policy rather than a smell. A suppression carries one so its staleness
# is visible — "a suppression that outlives its issue is a gate that has been quietly switched off"
# (zizmor.yml). These two are nothing but suppressions and their rationale; elsewhere a date in a
# comment is describing when something changed, which git already holds. See AGENTS.md §Comments.
DATE_IS_POLICY='^(zizmor\.yml|\.trivyignore|\.github/dependabot\.yml)$'
SOURCE_GLOBS=(*.tf *.tfvars *.sh *.py *.yaml *.yml)

usage() {
    sed -n '3,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# A baseline built from a half-tracked tree is worse than none: `git ls-files` cannot see a new
# file, so its comments are missing from the number and CI fails on the commit that adds them.
refuse_if_untracked() {
    cd "$REPO_ROOT"
    local pending
    pending="$(git ls-files --others --exclude-standard -- "${SOURCE_GLOBS[@]}" | grep -vE "$EXCLUDE" || true)"
    [ -z "$pending" ] && return 0
    echo "Refusing to write a baseline while these files are untracked:" >&2
    # shellcheck disable=SC2086  # One path per line is the intent.
    printf "  %s\n" $pending >&2
    echo >&2
    echo "git ls-files cannot see them, so their comments would be missing from the baseline and CI" >&2
    echo "would fail on the commit that adds them. Stage them first: git add -N <file>" >&2
    exit 2
}

files() {
    cd "$REPO_ROOT"
    git ls-files '*.tf' '*.tfvars' '*.sh' '*.py' '*.yaml' '*.yml' | grep -vE "$EXCLUDE" | awk 'NF'
}

# "<area>\t<file>:<line>\t<type>\t<detail>"
scan() {
    cd "$REPO_ROOT"
    # shellcheck disable=SC2016  # $0 and $1 are awk fields, not shell expansions.
    files | tr '\n' '\0' | xargs -0 awk -v maxblock="$MAX_BLOCK" -v maxheader="$MAX_HEADER_BLOCK" \
            -v headerby="$HEADER_STARTS_BY" -v maxdens="$MAX_DENSITY" \
            -v datepolicy="$DATE_IS_POLICY" '
        function area(f,   a) {
            split(f, a, "/")
            if (a[1] ~ /^(events-core|events-bff|events-importer|events-frontend|detekt-rules|infra|deploy|scripts)$/) return a[1]
            if (a[1] == ".github") return ".github"
            return "root"
        }
        function emit(f, ln, type, detail) { print area(f) "\t" f ":" ln "\t" type "\t" detail }
        function endblock(   n, cap) {
            n = blocklen
            blocklen = 0
            cap = (blockstart <= headerby) ? maxheader : maxblock
            if (n > cap && !allow) emit(file, blockstart, "long-block", n " lines, cap is " cap)
            allow = 0
        }
        FNR == 1 {
            if (file != "") { endblock(); density() }
            file = FILENAME; c = 0; k = 0; blocklen = 0; allow = 0
        }
        function density() {
            if (c + k > 0 && 100 * c / (c + k) > maxdens && c > 20)
                emit(file, 1, "dense-file", sprintf("%.0f%% comments, cap is %d%%", 100 * c / (c + k), maxdens))
        }
        {
            line = $0
            sub(/^[ \t]+/, "", line)
            if (line == "") { endblock(); next }
            if (FNR == 1 && line ~ /^#!/) { k++; next }
            if (line !~ /^#/) { endblock(); k++; next }

            c++
            rest = line
            sub(/^#+[ \t]?/, "", rest)

            if (rest ~ /^comment-lint:[ \t]*allow/) {
                if (rest !~ /^comment-lint:[ \t]*allow[ \t]+[^ \t]/)
                    emit(file, FNR, "bare-suppression", "no reason given")
                allow = 1
                next
            }

            if (blocklen == 0) blockstart = FNR
            blocklen++

            if (allow) next

            if (rest ~ /^#{1,6}[ \t]/) emit(file, FNR, "heading", "markdown heading in a comment")

            # Prose only: a date or a marker inside backticks or quotes is being named, not used —
            # the same rule the Kotlin and TypeScript checks apply.
            prose = rest
            gsub(/`[^`]*`/, " ", prose)
            gsub(/"[^"]*"/, " ", prose)

            if (prose ~ /[0-9][0-9][0-9][0-9]-[0-9][0-9]-[0-9][0-9]/ && file !~ datepolicy)
                emit(file, FNR, "date", "a date belongs in git blame")
            # Padded so a marker at either end still has a boundary: unpadded, "it now" matches
            # inside "that split now lives", which is not a narration of anything.
            low = " " tolower(prose) " "
            # "nothing here can be used to push" is the verb, not a change. The passive takes a
            # form of "be", which the narrating sense never does.
            gsub(/ (be|been|being|is|are|was|were|get|gets) used to /, " ", low)
            if (low ~ /(used to |previously,|previously the|as of [0-9]|[^a-z]it now[^a-z]|nowadays)/)
                emit(file, FNR, "history", "comment narrates a change")
        }
        END { if (file != "") { endblock(); density() } }
    '
}

counts() {
    scan | awk -F'\t' '{ n[$1]++ } END { for (a in n) print a "\t" n[a] }' | sort
}

report() {
    local top=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --top) top="$2"; shift 2 ;;
            *) echo "unknown option: $1" >&2; exit 2 ;;
        esac
    done

    local data
    data="$(scan)"
    if [ -z "$data" ]; then echo "No violations."; return; fi

    printf '%-18s %s\n' "TYPE" "COUNT"
    echo "$data" | awk -F'\t' '{ n[$3]++ } END { for (t in n) printf "%-18s %5d\n", t, n[t] }' | sort -k2 -rn
    printf '%-18s %5d\n' "TOTAL" "$(echo "$data" | wc -l | tr -d ' ')"
    echo
    if [ "$top" -gt 0 ]; then
        echo "$data" | awk -F'\t' '{ printf "%-52s %-16s %s\n", $2, $3, $4 }' | head -n "$top"
    else
        echo "$data" | awk -F'\t' '{ printf "%-52s %-16s %s\n", $2, $3, $4 }'
    fi
}

update_baseline() {
    refuse_if_untracked
    {
        echo "# Comment-lint violations per area — a ceiling that only moves down. See #713."
        echo "# Raise a number only with an argument in the PR; the ordinary fix is to compress the comment."
        counts
    } > "$BASELINE"
    echo "Wrote $BASELINE"
    grep -v '^#' "$BASELINE"
}

check() {
    if [ ! -f "$BASELINE" ]; then
        echo "No baseline at $BASELINE — run: scripts/comment-lint.sh update-baseline" >&2
        exit 2
    fi

    local current failed=0
    current="$(counts)"

    while IFS=$'\t' read -r area limit; do
        [ -z "${area:-}" ] && continue
        case "$area" in \#*) continue ;; esac
        local now
        now="$(echo "$current" | awk -F'\t' -v a="$area" '$1 == a { print $2 }')"
        now="${now:-0}"
        if [ "$now" -gt "$limit" ]; then
            printf '  %-18s %4d > %-4d  (+%d)\n' "$area" "$now" "$limit" "$((now - limit))"
            failed=1
        elif [ "$now" -lt "$limit" ]; then
            printf '  %-18s %4d < %-4d  (-%d, update the baseline)\n' "$area" "$now" "$limit" "$((limit - now))"
        fi
    done < "$BASELINE"

    while IFS=$'\t' read -r area now; do
        [ -z "${area:-}" ] && continue
        if ! grep -q "^${area}	" "$BASELINE"; then
            printf '  %-18s %4d  (no baseline entry)\n' "$area" "$now"
            failed=1
        fi
    done <<< "$current"

    if [ "$failed" -eq 1 ]; then
        echo
        echo "New comment-lint violations. See them with: scripts/comment-lint.sh report" >&2
        exit 1
    fi
    echo "Comment-lint violations are at or below the baseline."
}

case "${1:-report}" in
    report) shift || true; report "$@" ;;
    check) check ;;
    update-baseline) update_baseline ;;
    -h | --help | help) usage ;;
    *) usage >&2; exit 2 ;;
esac
