#!/usr/bin/env bash
#
# comment-lint.sh — the comment rules, for the languages detekt and ESLint cannot see.
#
# Usage:
#   scripts/comment-lint.sh report [--top N]   # list every violation
#   scripts/comment-lint.sh check              # fail if there is any violation at all
#
# Covers .tf, .tfvars, .sh, .py, .yaml, .yml. Kotlin is detekt's LongComment; TS and Vue are
# event-junkie/max-comment-lines. See .github/instructions/comments.instructions.md and #713.
#
# Silence one block with `# comment-lint: allow <reason>` on the line above it, or a whole file's
# density with `# comment-lint: allow-file <reason>`. The reason is not optional either way, and a
# bare directive is itself a violation.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# A block's length is its lines that carry something, not its lines. Blank ` # ` separators between
# paragraphs are structure and do not count (#750), matching `LongComment` since #741; delimiters and
# indentation do. So 25 is what a reader scrolls past, minus the breaks that make it scrollable.
MAX_BLOCK="${COMMENT_LINT_MAX_BLOCK:-25}"
# A file header is the script's only `--help` in most of these files, and documents an interface
# rather than reasoning interleaved with code. It gets a higher cap, not an exemption; the density
# rule still bounds it.
MAX_HEADER_BLOCK="${COMMENT_LINT_MAX_HEADER_BLOCK:-40}"
HEADER_STARTS_BY=3
# **55, where `CommentDensity` and the ESLint rule both cap at 70** (#721). The argument for parity
# is real — in Kotlin one comment explains a twenty-line function, while in HCL and YAML one comment
# explains one assignment, so the same reasoning lands at a higher ratio here — but 70 was measured
# to flag two files where 55 flags seventeen, and raising a cap to clear findings is the move #713
# rejected when it threw away a working KDoc rewrapper. A file that genuinely cannot fit says so
# with `allow-file` and a reason, which is visible in review; a looser number is not.
#
# The floor stays at 21 comment lines (`c > 20` below) rather than moving to the 25 its counterparts
# use, for the same reason: it would retire three findings by redefinition.
MAX_DENSITY="${COMMENT_LINT_MAX_DENSITY:-55}"
EXCLUDE='node_modules/|/build/|/dist/|/coverage/'
# Files where a date is the policy rather than a smell. A suppression carries one so its staleness
# is visible — "a suppression that outlives its issue is a gate that has been quietly switched off"
# (zizmor.yml). These two are nothing but suppressions and their rationale; elsewhere a date in a
# comment is describing when something changed, which git already holds. See the comments instructions.
DATE_IS_POLICY='^(zizmor\.yml|\.trivyignore|\.github/dependabot\.yml)$'

usage() {
    sed -n '3,15p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
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
            file = FILENAME; c = 0; k = 0; blocklen = 0; allow = 0; fileallow = 0
        }
        function density() {
            if (c + k > 0 && 100 * c / (c + k) > maxdens && c > 20 && !fileallow)
                emit(file, 1, "dense-file", sprintf("%.0f%% comments, cap is %d%%", 100 * c / (c + k), maxdens))
        }
        {
            line = $0
            sub(/^[ \t]+/, "", line)
            if (line == "") { endblock(); next }
            if (FNR == 1 && line ~ /^#!/) { k++; next }
            if (line !~ /^#/) { endblock(); k++; next }

            rest = line
            sub(/^#+[ \t]?/, "", rest)

            # Tested before the block directive, which is a prefix of it. It does not count toward the
            # ratio either: a suppression should not be able to push the thing it suppresses over.
            if (rest ~ /^comment-lint:[ \t]*allow-file/) {
                if (rest !~ /^comment-lint:[ \t]*allow-file[ \t]+[^ \t]/)
                    emit(file, FNR, "bare-suppression", "no reason given")
                fileallow = 1
                next
            }

            c++

            if (rest ~ /^comment-lint:[ \t]*allow/) {
                if (rest !~ /^comment-lint:[ \t]*allow[ \t]+[^ \t]/)
                    emit(file, FNR, "bare-suppression", "no reason given")
                allow = 1
                next
            }

            # A blank ` # ` between paragraphs is structure, not length. Counting it charges a
            # comment for being paragraphed, which makes the same words cheaper written as one wall
            # of text — `LongComment` stopped doing that in #741 and this is the other half (#750).
            # Density is unaffected: `c` above still counts every comment line, as all three
            # implementations do, so a blank line lands in both halves of that ratio.
            if (blocklen == 0) blockstart = FNR
            if (rest ~ /[^ \t]/) blocklen++

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

check() {
    local violations total
    violations="$(counts)"
    total="$(awk -F'	' '{ n += $2 } END { print n + 0 }' <<< "$violations")"

    if [ "$total" -eq 0 ]; then
        echo "No comment-lint violations."
        return 0
    fi

    while IFS=$'	' read -r area n; do
        case "$area" in "") continue ;; esac
        printf '  %-18s %4d
' "$area" "$n"
    done <<< "$violations"

    echo
    echo "$total comment-lint violations. See them with: scripts/comment-lint.sh report" >&2
    exit 1
}

case "${1:-report}" in
    report) shift || true; report "$@" ;;
    check) check ;;
    -h | --help | help) usage ;;
    *) usage >&2; exit 2 ;;
esac
