#!/usr/bin/env bash
#
# scan-coverage.sh — how much did the scanner look at, not just what did it find.
#
# A scanner gate asserts an exit code. Without a denominator a tool that quietly covers less passes
# just as cleanly, and the gate goes on looking green over less ground (#1087). Two ways that
# happens: a version bump narrows a rule set, or a configuration change narrows the input. Neither
# produces a red tick.
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
# **The output formats are known here and nowhere else.** A `grep -o` in six workflow `run:` blocks
# is six places to update and six places to get it silently wrong. An extraction that matches nothing
# is an error rather than a pass, which is what makes a tool changing its output loud.
#
# **A floor rather than an exact match, which is what #1087 asks for.** A rise is normal — the tree
# grows — so only a drop fails. The cost is honest and worth stating: this floor is only ever as tight
# as its last update, so deleting manifests after a stale baseline goes unnoticed. Raise it whenever
# a change adds coverage, which the notice below asks for on every run that finds more.
#
# **Trivy and OWASP get a floor of zero instead of a baseline**, and that is a decision rather than an
# omission. Their counts move with an upstream advisory database and with image contents, so a number
# would rot and get lowered until it meant nothing — `image-scan-scheduled.yml` argues the same case
# for the same reason. Zero cannot rot: it separates "found nothing" from "looked at nothing", which
# is the failure this repository has already had, when OWASP reported `Dependencies Scanned: 0` and
# passed its CVSS gate trivially.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
# The override exists for scan-coverage-test.sh, which must assert against fixed numbers rather
# than against a file the next manifest moves.
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
    sed -n '3,17p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

# count <pattern> <file> — the number in front of a word, from the last line that carries one.
#
# zizmor writes the pair two ways, and both have to work: `No findings to report. Good job! (11
# ignored, 64 suppressed)` when it is clean, and `75 findings (4 ignored, 10 suppressed, 8 unsafe
# fixes): …` when it is not.
count() {
    grep -oE "[0-9]+ $1" "$2" | tail -1 | grep -oE '^[0-9]+' || true
}

extract() {
    local key="$1" file="$2"
    case "$key" in
        zizmor-ignored) count ignored "$file" ;;
        zizmor-suppressed) count suppressed "$file" ;;
        flux-clusters-resources) sed -nE 's/^Summary: ([0-9]+) resources found.*/\1/p' "$file" | tail -1 ;;
        flux-clusters-files) sed -nE 's/^Summary: [0-9]+ resources found in ([0-9]+) files.*/\1/p' "$file" | tail -1 ;;
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
    if ((actual > floor)); then
        printf '%s: %s rose from %s to %s — raise the floor: scripts/scan-coverage.sh update %s %s\n' \
            "$(basename "$BASELINE")" "$key" "$floor" "$actual" "$key" "$file"
        [[ -n "${GITHUB_ACTIONS:-}" ]] &&
            printf '::notice::%s rose from %s to %s. Raise the floor in %s.\n' "$key" "$floor" "$actual" "$(basename "$BASELINE")"
    fi
    printf '%-26s %s (floor %s)\n' "$key" "$actual" "$floor"
}

# A property rather than a number, so it needs no floor and cannot rot: every rendered resource is
# checked, and none is skipped.
#
# `Skipped: 0` says nothing on its own, which is what #691 was — an empty stream reports it too. So
# the resource count has to be positive for the rest to mean anything.
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

# The floor that the `Dependencies Scanned: 0` incident asked for, on the tool it happened to. Trivy
# gained one in image-scan-scheduled.yml; Dependency-Check never did.
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
