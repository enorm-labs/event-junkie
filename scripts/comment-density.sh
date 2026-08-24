#!/usr/bin/env bash
#
# comment-density.sh — how much of this repository is comments, and whether that number is rising.
#
# Usage:
#   scripts/comment-density.sh report [--top N] [--json]   # measure, print
#   scripts/comment-density.sh check                       # fail if any area exceeds the baseline
#   scripts/comment-density.sh update-baseline             # accept the current numbers
#
# Reaches no network and writes nothing except the baseline. See #713.
#
# A line carrying both code and a comment counts as code, as cloc does. Generated files are
# excluded (see EXCLUDE): their comment count is not a thing anyone can act on.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASELINE="$REPO_ROOT/scripts/comment-baseline.txt"

EXCLUDE='node_modules/|/build/|/dist/|/coverage/|events-frontend/src/api/schema\.d\.ts|package-lock\.json'

usage() {
    sed -n '3,12p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# One line per file: "<area>\t<comment>\t<code>".
measure() {
    cd "$REPO_ROOT"
    # shellcheck disable=SC2016  # $1 and $2 are awk fields, not shell expansions.
    git ls-files -z \
        '*.kt' '*.kts' '*.ts' '*.tsx' '*.js' '*.mjs' '*.vue' \
        '*.tf' '*.tfvars' '*.sh' '*.py' '*.yaml' '*.yml' \
        | tr '\0' '\n' \
        | grep -vE "$EXCLUDE" \
        | awk 'NF' \
        | xargs awk '
        function style(f) {
            if (f ~ /\.(kt|kts|ts|tsx|js|mjs|vue)$/) return "slash"
            return "hash"
        }
        function area(f,   a) {
            split(f, a, "/")
            if (a[1] == "events-core" || a[1] == "events-bff" || a[1] == "events-importer") return a[1]
            if (a[1] == "events-frontend" || a[1] == "detekt-rules") return a[1]
            if (a[1] == "infra" || a[1] == "deploy" || a[1] == "scripts") return a[1]
            if (a[1] == ".github") return ".github"
            return "root"
        }
        FNR == 1 {
            if (file != "") print area(file) "\t" c "\t" k "\t" file
            file = FILENAME; st = style(file)
            c = 0; k = 0; block = 0; doc = 0
        }
        {
            line = $0
            sub(/^[ \t]+/, "", line)
            if (line == "") next

            if (st == "slash") {
                if (block) { c++; if (line ~ /\*\//) block = 0; next }
                if (line ~ /^<!--/) { c++; if (line !~ /-->/) block = 1; next }
                if (line ~ /^\/\//) { c++; next }
                if (line ~ /^\/\*/) { c++; if (line !~ /\*\//) block = 1; next }
                k++
                next
            }

            # Python docstrings are prose under another syntax, so they count.
            if (file ~ /\.py$/) {
                if (doc) { c++; if (line ~ /"""/) doc = 0; next }
                if (line ~ /^"""/) {
                    c++
                    if (gsub(/"""/, "&", line) < 2) doc = 1
                    next
                }
            }
            if (FNR == 1 && line ~ /^#!/) { k++; next }
            if (line ~ /^#/) { c++; next }
            k++
        }
        END { if (file != "") print area(file) "\t" c "\t" k "\t" file }
    '
}

report() {
    local top=0 json=0
    while [ $# -gt 0 ]; do
        case "$1" in
            --top) top="$2"; shift 2 ;;
            --json) json=1; shift ;;
            *) echo "unknown option: $1" >&2; exit 2 ;;
        esac
    done

    local data
    data="$(measure)"

    if [ "$json" -eq 1 ]; then
        echo "$data" | awk -F'\t' '
            { c[$1] += $2; k[$1] += $3; tc += $2; tk += $3 }
            END {
                printf "{\n  \"areas\": {\n"
                n = 0
                for (a in c) {
                    if (n++) printf ",\n"
                    printf "    \"%s\": { \"comment\": %d, \"code\": %d, \"ratio\": %.1f }", \
                        a, c[a], k[a], 100 * c[a] / (c[a] + k[a])
                }
                printf "\n  },\n  \"total\": { \"comment\": %d, \"code\": %d, \"ratio\": %.1f }\n}\n", \
                    tc, tk, 100 * tc / (tc + tk)
            }'
        return
    fi

    local rows
    rows="$(echo "$data" | awk -F'\t' '
        { c[$1] += $2; k[$1] += $3; tc += $2; tk += $3 }
        END {
            for (a in c) printf "%s\t%d\t%d\t%.1f\n", a, c[a], k[a], 100 * c[a] / (c[a] + k[a])
            printf "TOTAL\t%d\t%d\t%.1f\n", tc, tk, 100 * tc / (tc + tk)
        }')"

    printf '%-18s %9s %9s %8s\n' "AREA" "COMMENT" "CODE" "RATIO"
    {
        echo "$rows" | grep -v '^TOTAL' | sort -t"$(printf '\t')" -k2 -rn
        echo "$rows" | grep '^TOTAL'
    } | awk -F'\t' '{ printf "%-18s %9d %9d %7.1f%%\n", $1, $2, $3, $4 }'

    if [ "$top" -gt 0 ]; then
        printf '\nWorst %d files by comment lines:\n' "$top"
        echo "$data" | awk -F'\t' '$2 + $3 > 0 { printf "%6d %6.1f%%  %s\n", $2, 100 * $2 / ($2 + $3), $4 }' \
            | sort -rn | head -n "$top"
    fi
}

areas() {
    measure | awk -F'\t' '{ c[$1] += $2 } END { for (a in c) print a "\t" c[a] }' | sort
}

update_baseline() {
    {
        echo "# Comment lines per area — a ceiling, not a target. See #713."
        echo "# comment-density.sh check fails when any area rises above its number here."
        echo "# Lowering a number is the point. Raising one is a decision to argue for in the PR."
        areas
    } > "$BASELINE"
    echo "Wrote $BASELINE"
    grep -v '^#' "$BASELINE"
}

check() {
    if [ ! -f "$BASELINE" ]; then
        echo "No baseline at $BASELINE — run: scripts/comment-density.sh update-baseline" >&2
        exit 2
    fi

    local current failed=0
    current="$(areas)"

    while IFS=$'\t' read -r area limit; do
        [ -z "${area:-}" ] && continue
        case "$area" in \#*) continue ;; esac
        local now
        now="$(echo "$current" | awk -F'\t' -v a="$area" '$1 == a { print $2 }')"
        now="${now:-0}"
        if [ "$now" -gt "$limit" ]; then
            printf '  %-18s %6d > %-6d  (+%d)\n' "$area" "$now" "$limit" "$((now - limit))"
            failed=1
        elif [ "$now" -lt "$limit" ]; then
            printf '  %-18s %6d < %-6d  (-%d, update the baseline)\n' "$area" "$now" "$limit" "$((limit - now))"
        fi
    done < "$BASELINE"

    while IFS=$'\t' read -r area now; do
        if ! grep -q "^${area}	" "$BASELINE"; then
            printf '  %-18s %6d  (no baseline entry)\n' "$area" "$now"
            failed=1
        fi
    done <<< "$current"

    if [ "$failed" -eq 1 ]; then
        echo
        echo "Comment volume rose. Compress or delete, or argue for the raise in the PR: #713" >&2
        exit 1
    fi
    echo "Comment volume is at or below the baseline."
}

case "${1:-report}" in
    report) shift || true; report "$@" ;;
    check) check ;;
    update-baseline) update_baseline ;;
    -h | --help | help) usage ;;
    *) usage >&2; exit 2 ;;
esac
