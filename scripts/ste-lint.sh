#!/usr/bin/env bash
#
# ste-lint.sh — the sentence rules for docs/, ratcheted the way comment volume is.
#
# Usage:
#   scripts/ste-lint.sh report [--top N]   # list every finding
#   scripts/ste-lint.sh stats              # sentences scanned, and how many are over the cap
#   scripts/ste-lint.sh check              # fail if an area carries more than the baseline allows
#   scripts/ste-lint.sh update-baseline    # accept the current counts
#
# Reaches no network and writes nothing except the baseline. See #733 and
# .github/instructions/documentation.instructions.md.
#
# Only the structural half of ASD-STE100 is checkable here: the lexical rules are defined by an
# approved dictionary this repository is not licensed to carry, so nothing this prints means a
# document is STE-compliant. scripts/ste_lint.py holds the scanner — a sentence spans lines, which
# is why this one rule needs a parser rather than the awk the comment checks use.
#
# Areas are the three rewrite phases, so each phase lowers exactly one number and no improvement in
# one can hide a regression in another. Silence a block with `<!-- ste-lint: allow <reason> -->` on
# the line above it; the reason is not optional and a bare directive is itself a finding.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE="$REPO_ROOT/scripts/ste-baseline.txt"
SCANNER="$REPO_ROOT/scripts/ste_lint.py"

SOURCE_GLOBS=('docs/*.md' 'docs/*/*.md')

usage() {
    sed -n '3,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# A baseline built from a half-tracked tree is worse than none: `git ls-files` cannot see a new
# file, so its findings are missing from the number and CI fails on the commit that adds them.
refuse_if_untracked() {
    cd "$REPO_ROOT"
    local pending
    pending="$(git ls-files --others --exclude-standard -- "${SOURCE_GLOBS[@]}")"
    [ -z "$pending" ] && return 0
    echo "Refusing to write a baseline while these files are untracked:" >&2
    # shellcheck disable=SC2086  # One path per line is the intent.
    printf "  %s\n" $pending >&2
    echo >&2
    echo "git ls-files cannot see them, so their findings would be missing from the baseline and CI" >&2
    echo "would fail on the commit that adds them. Stage them first: git add -N <file>" >&2
    exit 2
}

files() {
    cd "$REPO_ROOT"
    git ls-files "${SOURCE_GLOBS[@]}" | awk 'NF'
}

# "<area>\t<file>:<line>\t<type>\t<detail>"
scan() {
    cd "$REPO_ROOT"
    files | tr '\n' '\0' | xargs -0 python3 "$SCANNER"
}

counts() {
    scan | awk -F'\t' '{ n[$1]++ } END { for (a in n) print a "\t" n[a] }' | sort
}

stats() {
    cd "$REPO_ROOT"
    files | tr '\n' '\0' | xargs -0 python3 "$SCANNER" --stats \
        | awk -F'\t' '
            BEGIN { printf "%-12s %10s %10s %8s\n", "AREA", "SENTENCES", "OVER CAP", "SHARE" }
            { printf "%-12s %10d %10d %7.1f%%\n", $1, $2, $3, $2 ? 100 * $3 / $2 : 0; s += $2; l += $3 }
            END { printf "%-12s %10d %10d %7.1f%%\n", "TOTAL", s, l, s ? 100 * l / s : 0 }'
}

report() {
    local top=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --top)
                top="$2"
                shift 2
                ;;
            *)
                echo "unknown option: $1" >&2
                exit 2
                ;;
        esac
    done

    local data
    data="$(scan)"
    if [ -z "$data" ]; then
        echo "No findings."
        return
    fi

    printf '%-18s %s\n' "TYPE" "COUNT"
    echo "$data" | awk -F'\t' '{ n[$3]++ } END { for (t in n) printf "%-18s %5d\n", t, n[t] }' | sort -k2 -rn
    printf '%-18s %5d\n' "TOTAL" "$(echo "$data" | wc -l | tr -d ' ')"
    echo
    if [ "$top" -gt 0 ]; then
        echo "$data" | awk -F'\t' '{ printf "%-56s %-16s %s\n", $2, $3, $4 }' | head -n "$top"
    else
        echo "$data" | awk -F'\t' '{ printf "%-56s %-16s %s\n", $2, $3, $4 }'
    fi
}

update_baseline() {
    refuse_if_untracked
    {
        echo "# ASD-STE100 findings per area — a ceiling that only moves down. See #733."
        echo "# One area per rewrite phase. Raise a number only with an argument in the PR;"
        echo "# the ordinary fix is to split the sentence."
        echo "#"
        echo "# An area with no row here is at zero, which is where all three finished. That is the"
        echo "# goal state, not a missing file: do not run update-baseline to make a red check pass."
        counts
    } > "$BASELINE"
    echo "Wrote $BASELINE"
    grep -v '^#' "$BASELINE"
}

check() {
    if [ ! -f "$BASELINE" ]; then
        echo "No baseline at $BASELINE — run: scripts/ste-lint.sh update-baseline" >&2
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
            printf '  %-12s %4d > %-4d  (+%d)\n' "$area" "$now" "$limit" "$((now - limit))"
            failed=1
        elif [ "$now" -lt "$limit" ]; then
            printf '  %-12s %4d < %-4d  (-%d, update the baseline)\n' "$area" "$now" "$limit" "$((limit - now))"
        fi
    done < "$BASELINE"

    while IFS=$'\t' read -r area now; do # an absent area is a limit of zero, not an unknown (#733)
        [ -z "${area:-}" ] && continue
        if ! grep -q "^${area}	" "$BASELINE"; then
            printf '  %-12s %4d > 0     (+%d)\n' "$area" "$now" "$now"
            failed=1
        fi
    done <<< "$current"

    if [ "$failed" -eq 1 ]; then
        echo
        echo "New ASD-STE100 findings. See them with: scripts/ste-lint.sh report" >&2
        exit 1
    fi
    echo "Documentation findings are at or below the baseline."
}

case "${1:-report}" in
    report)
        shift || true
        report "$@"
        ;;
    stats) stats ;;
    check) check ;;
    update-baseline) update_baseline ;;
    -h | --help | help) usage ;;
    *)
        usage >&2
        exit 2
        ;;
esac
