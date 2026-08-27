#!/usr/bin/env bash
#
# comment-density.sh — how much of this repository is comments, and where that volume sits.
#
# Usage:
#   scripts/comment-density.sh report [--top N] [--json]   # measure, print
#
# Reaches no network and writes nothing. A diagnostic, not a gate: it reports where the comments
# are so a sweep knows which files to open, and fails only when it cannot measure. See #713.
#
# A line carrying both code and a comment counts as code, as cloc does. Generated files are
# excluded (see EXCLUDE): their comment count is not a thing anyone can act on.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

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
                if (block) { c++; if (line ~ closer) block = 0; next }
                if (line ~ /^<!--/) { c++; if (line !~ /-->/) { block = 1; closer = "-->" } next }
                if (line ~ /^\/\//) { c++; next }
                if (line ~ /^\/\*/) { c++; if (line !~ /\*\//) { block = 1; closer = "\\*/" } next }
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

case "${1:-report}" in
    report) shift || true; report "$@" ;;
    -h | --help | help) usage ;;
    *) usage >&2; exit 2 ;;
esac
