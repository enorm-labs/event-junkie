#!/usr/bin/env bash
#
# duplication.sh — the copied code in this repository, ranked by file pair.
#
# Usage:
#   scripts/duplication.sh report [--tests] [--top N] [--json] [<path>...]
#     --tests   scan test code too; the default is production code only
#     --top N   print the N file pairs with the most cloned lines (default 20)
#     --json    print the pairs as JSON instead of a table
#     <path>    scan only these paths, relative to the repository root
#
# Requires jq and the pinned jscpd in tools/jscpd: run `npm ci` there first. Reaches no network.
# Writes the jscpd report, JSON and HTML, to build/duplication/. A diagnostic, not a gate.
#
# The scan starts at the repository root, so two same-named files in two modules keep both paths.
# EXCLUDE holds what is not a finding: the per-module copies architecture.instructions.md
# § Pagination asks for, shadcn-vue's components, one message file per locale, generated types.

set -euo pipefail

usage() {
    awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

case "${1:-}" in
    -h | --help)
        usage
        exit 0
        ;;
    report) shift ;;
    *)
        usage >&2
        exit 2
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JSCPD="$REPO_ROOT/tools/jscpd/node_modules/.bin/jscpd"
OUT="$REPO_ROOT/build/duplication"

EXCLUDE='**/node_modules/**,tools/**,**/PageResponse.kt,**/StableSortPageableArgumentResolver.kt,**/components/ui/**,**/i18n/messages/**,**/*.d.ts'
TEST_CODE='**/src/test/**,**/src/testFixtures/**,**/__tests__/**,**/e2e/**,scripts/test_*,scripts/*-test.sh'

tests=false
top=20
json=false
paths=()
while (($#)); do
    case "$1" in
        --tests) tests=true ;;
        --top)
            top="${2:?--top needs a number}"
            shift
            ;;
        --json) json=true ;;
        -*)
            usage >&2
            exit 2
            ;;
        *) paths+=("$1") ;;
    esac
    shift
done
((${#paths[@]})) || paths=(.)

command -v jq >/dev/null || {
    echo 'duplication.sh: jq is required' >&2
    exit 1
}
[[ -x "$JSCPD" ]] || {
    echo 'duplication.sh: no jscpd at tools/jscpd — run "npm ci" in tools/jscpd first' >&2
    exit 1
}

ignore="$EXCLUDE"
$tests || ignore="$ignore,$TEST_CODE"

cd "$REPO_ROOT"
rm -rf "$OUT"
# The thresholds keep a shared import block or a five-line guard out of the list.
"$JSCPD" "${paths[@]}" \
    --min-lines 8 --min-tokens 70 \
    --format kotlin,typescript,javascript,vue,python \
    --ignore "$ignore" \
    --reporters json,html --output "$OUT" \
    --silent --no-tips --no-colors >/dev/null

report="$OUT/jscpd-report.json"
pairs='
    [.duplicates[]
     | {lines, a: .firstFile, b: .secondFile}
     | if .a.name > .b.name then {lines, a: .b, b: .a} else . end]
    | group_by([.a.name, .b.name])
    | map({lines: (map(.lines) | add), clones: length,
           a: "\(.[0].a.name):\(.[0].a.start)", b: "\(.[0].b.name):\(.[0].b.start)"})
    | sort_by(-.lines)'

if $json; then
    jq --argjson top "$top" "$pairs | .[:\$top]" "$report"
    exit 0
fi

jq -r '.statistics.total | "\(.clones) clones, \(.duplicatedLines) duplicated lines (\(.percentage * 10 | round / 10)%) in \(.sources) files"' "$report"
echo
jq -r --argjson top "$top" "$pairs | .[:\$top][] | [.lines, .clones, .a, .b] | @tsv" "$report" |
    awk -F'\t' 'BEGIN { printf "%5s %6s  %s\n", "lines", "clones", "first clone of the pair" }
        { printf "%5d %6d  %s\n%13s  %s\n", $1, $2, $3, "", $4 }'
echo
echo "Every clone: $OUT/jscpd-report.html"
