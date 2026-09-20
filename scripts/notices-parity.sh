#!/usr/bin/env bash
#
# notices-parity.sh — the committed open-source notices, against what the dependencies actually say.
#
# Usage:
#   scripts/notices-parity.sh check   # exits 1 if the committed file is stale; leaves it untouched
#   scripts/notices-parity.sh         # regenerates it in place, for committing
#
# Reaches the network — both generators resolve dependencies — and writes only the notices file and
# the Gradle report under build/.
#
# `events-frontend/src/assets/notices.json` is generated, committed and rendered at /legal/notices;
# nothing joined it to its inputs and it drifted by 51 components (#1034). **A stale notices file
# understates what we distribute** (docs/LEGAL.md §9.2). The check is a plain diff because
# generate-notices.mjs writes no timestamp, so an unchanged dependency set regenerates byte-identically.
# Both ecosystems in one job, which is why this is its own workflow: the generator merges the Gradle
# licence report with npm's and needs a JDK and Node together.
#
# `check` restores the committed file before exiting, whatever happened — the split
# `format-markdown.sh` and `ste-lint.sh` use. **The output must not depend on the machine**: optional
# dependencies differ by OS, so the generator drops platform-named binaries and any package whose `os`
# excludes Linux (#1043). A package restricted to Linux would be absent from a macOS install; nothing
# in the tree is, and **CI is the authority** if the two ever disagree.

set -euo pipefail

case "${1:-}" in
    -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

NOTICES="events-frontend/src/assets/notices.json"
MODE="${1:-fix}"

case "$MODE" in
    check | fix) ;;
    *)
        printf 'notices-parity.sh: unknown mode %s — expected "check" or nothing\n' "$MODE" >&2
        exit 2
        ;;
esac

[[ -f "$NOTICES" ]] || {
    printf 'notices-parity.sh: no such file: %s\n' "$NOTICES" >&2
    exit 1
}

# The licence-report plugin is not configuration-cache compatible; passing the flag here is what makes
# this runnable from a clean checkout.
regenerate() {
    ./gradlew generateLicenseReport --no-configuration-cache -q
    npm --prefix events-frontend run generate:notices
}

if [[ "$MODE" == "fix" ]]; then
    regenerate
    exit 0
fi

BEFORE="$(mktemp)"
cp "$NOTICES" "$BEFORE"
# Unconditional, so an interrupted regeneration does not leave a half-written legal document. It must
# stay unconditional: the stale path below ends in `exit 1`, which fires this same trap.
trap 'cp "$BEFORE" "$NOTICES"; rm -f "$BEFORE"' EXIT

# Two generators, and a failure in either is not a staleness result; unguarded, the second failing
# surfaces as its own error, indistinguishable from the report below.
if ! regenerate >/dev/null; then
    printf 'notices-parity.sh: could not regenerate the notices — the Gradle or npm generator failed.\n' >&2
    printf 'This is not a staleness result. %s is unchanged.\n' "$NOTICES" >&2
    exit 1
fi

if diff -q "$BEFORE" "$NOTICES" >/dev/null; then
    printf 'The committed notices match the resolved dependencies.\n'
    exit 0
fi

added="$(comm -13 \
    <(jq -r '[.. | objects | select(.name and .version) | "\(.name)@\(.version)"] | .[]' "$BEFORE" | sort -u) \
    <(jq -r '[.. | objects | select(.name and .version) | "\(.name)@\(.version)"] | .[]' "$NOTICES" | sort -u) |
    wc -l | tr -d ' ')"
removed="$(comm -23 \
    <(jq -r '[.. | objects | select(.name and .version) | "\(.name)@\(.version)"] | .[]' "$BEFORE" | sort -u) \
    <(jq -r '[.. | objects | select(.name and .version) | "\(.name)@\(.version)"] | .[]' "$NOTICES" | sort -u) |
    wc -l | tr -d ' ')"

# The counts rather than the diff: four thousand lines of generated JSON say nothing actionable.
# **Keyed on `name@version`, not `name`**: a dependency that only moves version is present on both
# sides under a bare name, and the counts would read "0 missing" for a file that has drifted. The
# wording says "entries": `foo 1.0 -> 1.1` is one line gone and one arrived.
cat >&2 <<EOF
notices-parity.sh: $NOTICES is stale.

  ${added} entry(s) missing from it, ${removed} listed that no longer resolve.

Regenerate and commit the result:

  ./gradlew generateLicenseReport --no-configuration-cache
  npm --prefix events-frontend run generate:notices

or run scripts/notices-parity.sh with no argument, which does both.
EOF
exit 1
