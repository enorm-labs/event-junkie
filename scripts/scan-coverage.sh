#!/usr/bin/env bash
#
# scan-coverage.sh — how much did the scanner look at, not just what did it find.
#
# A scanner gate asserts an exit code; without a denominator a tool that quietly covers less passes
# just as cleanly (#1087) — a version bump narrows a rule set, a configuration change narrows the input.
#
# Usage:
#   scan-coverage.sh baseline <key> <file>   # a denominator against the committed floor
#   scan-coverage.sh render <file>           # a `flux schema validate` render: everything valid, nothing skipped
#   scan-coverage.sh owasp <file>            # a Dependency-Check JSON report enumerated something
#   scan-coverage.sh list                    # the committed floors
#   scan-coverage.sh update <key> <file>     # move one floor, for a reduction that is meant
#
# Exit 0 the scanner covered what it should, 1 it covered less, 2 the question could not be asked.
#
# **The output formats are known here and nowhere else**, and an extraction that matches nothing is
# an error rather than a pass. **A floor rather than an exact match**: a rise is normal, only a drop
# fails; the floor is only as tight as its last update, so raise it whenever a change adds coverage.
# **Trivy and OWASP get a floor of zero**: their counts move with an upstream advisory database and
# would be lowered until they meant nothing, while zero still separates "found nothing" from "looked
# at nothing" — the `Dependencies Scanned: 0` incident.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The override exists for scan-coverage-test.sh, which asserts against fixed numbers.
BASELINE="${SCAN_COVERAGE_BASELINE:-$REPO_ROOT/scripts/scan-coverage-baseline.txt}"

die() {
    printf 'scan-coverage.sh: %s\n' "$1" >&2
    exit 2
}

fail() {
    printf 'scan-coverage.sh: %s\n' "$1" >&2
    [[ -n "${GITHUB_ACTIONS:-}" ]] && printf '::error::%s\n' "$1"
    exit 1
}

usage() {
    awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

# count <pattern> <file> — the number in front of a word, from the last line that carries one. zizmor
# writes the pair two ways: `No findings to report. Good job! (11 ignored, 64 suppressed)` and
# `75 findings (4 ignored, 10 suppressed, …)`.
count() {
    grep -oE "[0-9]+ $1" "$2" | tail -1 | grep -oE '^[0-9]+' || true
}

# The two ZAP denominators (#1421): `Total of N URLs` is what the spider reached — a Traefik that
# never came up shows as a drop — and the per-rule tally's sum is how many rules the pinned image ran.
zap_urls() {
    sed -nE 's/^Total of ([0-9]+) URLs$/\1/p' "$1" | tail -1
}

zap_rules() {
    grep -E '^FAIL-NEW: [0-9]+' "$1" | tail -1 | grep -oE '[0-9]+' | awk '{ s += $1 } END { if (NR) print s }' || true
}

# Nuclei's denominator (#1423): how many templates the filters admitted from the pinned release. Read
# from the log, where the line carries an `[INF]` prefix.
nuclei_templates() {
    grep -oE 'Templates loaded for current scan: [0-9]+' "$1" | tail -1 | grep -oE '[0-9]+$' || true
}

extract() {
    local key="$1" file="$2"
    case "$key" in
        zizmor-ignored) count ignored "$file" ;;
        zizmor-suppressed) count suppressed "$file" ;;
        flux-clusters-resources) sed -nE 's/^Summary: ([0-9]+) resources found.*/\1/p' "$file" | tail -1 ;;
        flux-clusters-files) sed -nE 's/^Summary: [0-9]+ resources found in ([0-9]+) files.*/\1/p' "$file" | tail -1 ;;
        zap-*-urls) zap_urls "$file" ;;
        zap-*-rules) zap_rules "$file" ;;
        nuclei-*-templates) nuclei_templates "$file" ;;
        *) die "unknown key '$key' — see $(basename "$BASELINE")" ;;
    esac
}

baseline_of() {
    [[ -f "$BASELINE" ]] || die "$BASELINE does not exist"
    awk -v k="$1" '$1 == k { print $2; found = 1 } END { exit !found }' "$BASELINE" ||
        die "no floor recorded for '$1' — add a row to $(basename "$BASELINE")"
}

cmd_baseline() {
    local key="${1:?key}" file="${2:?file}" actual floor
    [[ -f "$file" ]] || die "$file does not exist"

    actual="$(extract "$key" "$file")"
    [[ -n "$actual" ]] ||
        die "found no '$key' in $file — the extraction matched nothing, which is not the same as a scan that covered everything"

    floor="$(baseline_of "$key")"

    if ((actual < floor)); then
        fail "$key dropped from $floor to $actual. Something was scanned before and is not now. If that is meant, take it in this commit: scripts/scan-coverage.sh update $key $file"
    fi
    # A spider's URL count moves from run to run, and Nuclei's floor has headroom on purpose; a rise
    # there is not a floor to raise.
    if ((actual > floor)) && [[ "$key" != zap-*-urls && "$key" != nuclei-*-templates ]]; then
        printf '%s: %s rose from %s to %s — raise the floor: scripts/scan-coverage.sh update %s %s\n' \
            "$(basename "$BASELINE")" "$key" "$floor" "$actual" "$key" "$file"
        [[ -n "${GITHUB_ACTIONS:-}" ]] &&
            printf '::notice::%s rose from %s to %s. Raise the floor in %s.\n' "$key" "$floor" "$actual" "$(basename "$BASELINE")"
    fi
    printf '%-26s %s (floor %s)\n' "$key" "$actual" "$floor"
}

# A property rather than a number, so no floor and nothing to rot: every rendered resource checked,
# none skipped. `Skipped: 0` says nothing on its own (#691) — an empty stream reports it too — so the
# count has to be positive.
cmd_render() {
    local file="${1:?file}" found valid invalid skipped summary
    [[ -f "$file" ]] || die "$file does not exist"

    summary="$(grep -E '^Summary: [0-9]+ resources found' "$file" | tail -1)" ||
        die "no 'Summary:' line in $file — flux schema validate wrote nothing, or its output changed"

    found="$(sed -nE 's/^Summary: ([0-9]+) resources found.*/\1/p' <<<"$summary")"
    valid="$(sed -nE 's/.*Valid: ([0-9]+).*/\1/p' <<<"$summary")"
    invalid="$(sed -nE 's/.*Invalid: ([0-9]+).*/\1/p' <<<"$summary")"
    skipped="$(sed -nE 's/.*Skipped: ([0-9]+).*/\1/p' <<<"$summary")"
    [[ -n "$found" && -n "$valid" && -n "$invalid" && -n "$skipped" ]] ||
        die "could not read all four counts from: $summary"

    ((found > 0)) || fail "0 resources rendered — the chart produced nothing and validating nothing reports success"
    ((invalid == 0)) || fail "$invalid invalid resources"
    ((skipped == 0)) ||
        fail "$skipped resources skipped — a resource nothing can check is a resource nothing checks. Add its schema rather than skipping it"
    ((valid == found)) || fail "$found resources found but only $valid validated"
    printf 'render: %s resources, all valid, none skipped\n' "$found"
}

# The floor the `Dependencies Scanned: 0` incident asked for, on the tool it happened to.
cmd_owasp() {
    local file="${1:?file}" deps
    command -v jq >/dev/null || die 'jq is required but not on PATH'
    [[ -f "$file" ]] || die "$file does not exist — Dependency-Check wrote no JSON report"

    deps="$(jq '.dependencies | length' "$file")" || die "$file is not a Dependency-Check JSON report"
    ((deps > 0)) ||
        fail 'Dependency-Check enumerated 0 dependencies — it looked at nothing, which is not the same as finding nothing'
    printf 'owasp: %s dependencies examined\n' "$deps"
}

cmd_update() {
    local key="${1:?key}" file="${2:?file}" actual tmp
    actual="$(extract "$key" "$file")"
    [[ -n "$actual" ]] || die "found no '$key' in $file"
    baseline_of "$key" >/dev/null

    tmp="$(mktemp)"
    awk -v k="$key" -v v="$actual" '$1 == k { printf "%s\t%s\n", k, v; next } { print }' "$BASELINE" >"$tmp"
    mv "$tmp" "$BASELINE"
    printf 'set %s to %s\n' "$key" "$actual"
}

case "${1:-}" in
    baseline)
        shift
        cmd_baseline "$@"
        ;;
    render)
        shift
        cmd_render "$@"
        ;;
    owasp)
        shift
        cmd_owasp "$@"
        ;;
    update)
        shift
        cmd_update "$@"
        ;;
    list) grep -v '^#' "$BASELINE" | awk 'NF' ;;
    -h | --help | '')
        usage
        exit 0
        ;;
    *) die "unknown command '$1'" ;;
esac
