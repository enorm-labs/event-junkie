#!/usr/bin/env bash
#
# scan-coverage-test.sh — assert what `scan-coverage.sh` decides, not merely that it runs.
#
# The failure it guards against is the one the gate exists to catch, one level up: an extraction that
# stops matching reports a scanner as fully covered rather than as unreadable. A passing run says
# nothing about which of the two happened, so the cases below drive the script with fixtures whose
# right answer is known (#1087).
#
# Fixtures are written to a temp directory rather than pointed at real tool output, which is a moving
# target — the first manifest added turns a passing assertion into one that passes by accident.
#
# Usage: scripts/scan-coverage-test.sh — reaches no network, writes only under a temp dir it removes.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COVERAGE="$REPO_ROOT/scripts/scan-coverage.sh"

failures=0

fail() {
    printf '  FAIL  %s\n' "$1" >&2
    [[ -n "${2:-}" ]] && printf '%s\n' "$2" | sed 's/^/          /' >&2
    failures=$((failures + 1))
}

pass() { printf '  ok    %s\n' "$1"; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# A baseline of its own, so the assertions do not move when the repository's floors do.
BASELINE="$WORK/scan-coverage-baseline.txt"
printf 'zizmor-ignored\t11\nzizmor-suppressed\t64\nflux-clusters-resources\t141\nflux-clusters-files\t41\n' >"$BASELINE"

# run <expected-exit> <name> <args...> — the script against the fixture baseline above.
run() {
    local want="$1" name="$2" out got
    shift 2
    set +e
    out="$(SCAN_COVERAGE_BASELINE="$BASELINE" "$COVERAGE" "$@" 2>&1)"
    got=$?
    set -e
    if [[ "$got" == "$want" ]]; then
        pass "$name"
    else
        fail "$name — wanted exit $want, got $got" "$out"
    fi
}

cat >"$WORK/zizmor-clean.txt" <<'EOF'
No findings to report. Good job! (11 ignored, 64 suppressed)
EOF
cat >"$WORK/zizmor-findings.txt" <<'EOF'
75 findings (11 ignored, 64 suppressed, 8 unsafe fixes): 7 informational, 35 low, 3 medium, 16 high
EOF
cat >"$WORK/zizmor-dropped.txt" <<'EOF'
No findings to report. Good job! (11 ignored, 40 suppressed)
EOF
cat >"$WORK/zizmor-grown.txt" <<'EOF'
No findings to report. Good job! (11 ignored, 70 suppressed)
EOF
printf 'zizmor 1.31.0 audited 27 workflows and is happy\n' >"$WORK/zizmor-reworded.txt"

echo "zizmor"
run 0 "clean output reads both counts" baseline zizmor-suppressed "$WORK/zizmor-clean.txt"
run 0 "the with-findings wording reads the same counts" baseline zizmor-suppressed "$WORK/zizmor-findings.txt"
run 1 "a drop fails" baseline zizmor-suppressed "$WORK/zizmor-dropped.txt"
run 0 "a rise passes" baseline zizmor-suppressed "$WORK/zizmor-grown.txt"
run 2 "output the extraction cannot read is an error, not a pass" baseline zizmor-suppressed "$WORK/zizmor-reworded.txt"
run 2 "an unknown key is an error" baseline zizmor-nonsense "$WORK/zizmor-clean.txt"
run 2 "a missing file is an error" baseline zizmor-suppressed "$WORK/nope.txt"

cat >"$WORK/flux-ok.txt" <<'EOF'
Summary: 141 resources found in 41 files - Valid: 135, Invalid: 0, Skipped: 6
EOF
cat >"$WORK/flux-fewer-files.txt" <<'EOF'
Summary: 141 resources found in 30 files - Valid: 135, Invalid: 0, Skipped: 6
EOF

echo "flux schema, cluster manifests"
run 0 "today's counts pass" baseline flux-clusters-resources "$WORK/flux-ok.txt"
run 1 "fewer files fails even when the resource count holds" baseline flux-clusters-files "$WORK/flux-fewer-files.txt"

cat >"$WORK/render-ok.txt" <<'EOF'
Summary: 25 resources found parsing stdin - Valid: 25, Invalid: 0, Skipped: 0
EOF
cat >"$WORK/render-empty.txt" <<'EOF'
Summary: 0 resources found parsing stdin - Valid: 0, Invalid: 0, Skipped: 0
EOF
cat >"$WORK/render-skipped.txt" <<'EOF'
Summary: 25 resources found parsing stdin - Valid: 24, Invalid: 0, Skipped: 1
EOF
cat >"$WORK/render-invalid.txt" <<'EOF'
Summary: 25 resources found parsing stdin - Valid: 24, Invalid: 1, Skipped: 0
EOF
printf 'nothing that looks like a summary\n' >"$WORK/render-garbage.txt"

echo "flux schema, rendered chart"
run 0 "a full render passes" render "$WORK/render-ok.txt"
run 1 "an empty stream fails rather than validating nothing" render "$WORK/render-empty.txt"
run 1 "a skipped resource fails" render "$WORK/render-skipped.txt"
run 1 "an invalid resource fails" render "$WORK/render-invalid.txt"
run 2 "output with no summary is an error" render "$WORK/render-garbage.txt"

printf '{"dependencies": [{"fileName": "a.jar"}, {"fileName": "b.jar"}]}\n' >"$WORK/owasp-ok.json"
printf '{"dependencies": []}\n' >"$WORK/owasp-empty.json"
printf 'not json\n' >"$WORK/owasp-garbage.json"

echo "Dependency-Check"
run 0 "a report that enumerated something passes" owasp "$WORK/owasp-ok.json"
run 1 "zero dependencies fails" owasp "$WORK/owasp-empty.json"
run 2 "a report that will not parse is an error" owasp "$WORK/owasp-garbage.json"
run 2 "a missing report is an error" owasp "$WORK/owasp-absent.json"

echo
if ((failures > 0)); then
    printf '%d failing assertion(s)\n' "$failures" >&2
    exit 1
fi
echo "All assertions pass."
