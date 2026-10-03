#!/usr/bin/env bash
#
# plugin-parity.sh — the local flux `schema` and helm `unittest` plugins, against the versions CI pins.
#
# Usage:
#   scripts/plugin-parity.sh        # exits 1 and prints the install command for each plugin out of step
#
# Requires: flux and helm. Reads .github/workflows/validate-chart.yml. Reaches no network and writes nothing.
#
# `/verify` runs the chart gate with whatever plugin is installed, and CI runs the pinned one. An
# old schema plugin reads every kustomization.yaml as a resource and reports it Invalid (#2412). A
# gate that is red on `main` hides the real finding, and an old plugin can also pass what CI fails.

set -euo pipefail

case "${1:-}" in
    -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WORKFLOW="$REPO_ROOT/.github/workflows/validate-chart.yml"

pin() {
    local value
    value="$(sed -n "s/^  $1: //p" "$WORKFLOW")"
    if [ -z "$value" ]; then
        echo "plugin-parity: $WORKFLOW no longer sets $1; update this check with the workflow" >&2
        exit 1
    fi
    printf '%s' "$value"
}

SCHEMA_PIN="$(pin FLUX_SCHEMA_VERSION)"
UNITTEST_PIN="$(pin HELM_UNITTEST_VERSION)"

problems=0

# Each argument after the name is one word of the install command, printed on a mismatch.
compare() {
    local name="$1" have="$2" want="$3"
    shift 3
    if [ "${have#v}" = "${want#v}" ]; then
        printf '  %-8s %s ✓\n' "$name" "$want"
        return
    fi
    printf '  %-8s %s, CI pins %s ✗\n' "$name" "${have:-not installed}" "$want" >&2
    printf '           fix: %s\n' "$*" >&2
    problems=1
}

schema_have=""
if command -v flux >/dev/null 2>&1; then
    schema_have="$(flux schema --version 2>/dev/null | awk '{ print $NF }' || true)"
fi
compare schema "$schema_have" "$SCHEMA_PIN" flux plugin install "schema@$SCHEMA_PIN"

unittest_have=""
if command -v helm >/dev/null 2>&1; then
    unittest_have="$(helm plugin list 2>/dev/null | awk '$1 == "unittest" { print $2 }' || true)"
fi
# Helm refuses to install over an existing plugin, so a wrong version is uninstalled first.
unittest_fix="helm plugin install https://github.com/helm-unittest/helm-unittest --version $UNITTEST_PIN --verify=false"
[ -n "$unittest_have" ] && unittest_fix="helm plugin uninstall unittest && $unittest_fix"
compare unittest "$unittest_have" "$UNITTEST_PIN" "$unittest_fix"

if [ "$problems" -ne 0 ]; then
    echo >&2
    echo "A chart plugin differs from validate-chart.yml. Install the pinned version, then rerun the chart gate." >&2
    exit 1
fi
