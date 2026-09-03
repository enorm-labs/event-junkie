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
# `events-frontend/src/assets/notices.json` is generated, committed, and rendered at /legal/notices.
# Nothing joined the file to its inputs, and it drifted: #1034 regenerated it while moving two
# packages and picked up 51 components that should already have been there — 50 JVM and one npm.
# Nobody was looking, because looking was not anybody's job.
#
# **A stale notices file understates what we distribute**, which is the direction that matters (see
# docs/LEGAL.md §9.2). AGENTS.md already says to regenerate whenever dependencies change on either
# side; this is the thing that notices when that did not happen.
#
# **The check works because the generator was built for it.** generate-notices.mjs deliberately
# writes no timestamp, so an unchanged dependency set produces a byte-identical file and this is a
# plain diff rather than a semantic comparison. Verified rather than assumed — regenerating twice
# over the same inputs leaves an empty `git diff`.
#
# **Both ecosystems, in one job, and that is why this is not folded into an existing workflow.** The
# generator reads the Gradle licence report off disk and merges it with npm's, so a full
# regeneration needs a JDK and Node together. No other pull-request workflow has both.
#
# `check` restores the committed file before exiting, whatever happened, so a failing run leaves the
# tree exactly as it found it. That is the difference from the bare form, and it is the same split
# `format-markdown.sh` and `ste-lint.sh` use.
#
# **The output must not depend on the machine that produces it**, which is what makes a diff a
# signal at all. `license-checker` walks `node_modules`, where optional dependencies differ by
# operating system, so the generator drops both the platform-named binaries and any package whose
# `os` field excludes Linux — `fsevents` being the one that has an ordinary name and declares the
# restriction in the field (#1043). A laptop and CI produce byte-identical files.
#
# One asymmetry is unguarded: a package restricted to Linux would be absent from a macOS install
# entirely, so a Mac would omit what CI lists. Nothing in the tree is Linux-only — its three
# `os`-restricted packages are all `darwin`. **CI is the authority** if the two ever disagree.

set -euo pipefail

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

# The Gradle half is not configuration-cache compatible — the licence-report plugin is not, and
# gradle.properties says so. Passing the flag here rather than relying on the caller is what makes
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
# Unconditional, so an interrupted or failing regeneration does not leave a half-written legal
# document in the tree. It must stay unconditional: the stale path below ends in `exit 1`, which
# fires this same trap, so making the restore depend on success is what would leave a regenerated
# file behind on exactly the run that matters.
trap 'cp "$BEFORE" "$NOTICES"; rm -f "$BEFORE"' EXIT

# `regenerate` is two generators, and a failure in either is not a staleness result. Unguarded, the
# second one failing after the first succeeds surfaces as that command's own error, which a reader
# cannot tell apart from the report below.
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

# The counts rather than the diff, because the diff is four thousand lines of generated JSON and
# says nothing a reader can act on. What matters is whether components appeared or disappeared.
#
# **Keyed on `name@version`, not `name`.** A dependency that only moves version is present on both
# sides under a bare name, so the counts come out "0 missing, 0 no longer resolve" for a file that
# has genuinely drifted — a staleness message naming nothing. The versioned key costs one count on
# each side per upgrade, which is why the wording below says "entries" rather than "components":
# `foo 1.0 -> 1.1` is one line gone and one arrived, not two components.
cat >&2 <<EOF
notices-parity.sh: $NOTICES is stale.

  ${added} entry(s) missing from it, ${removed} listed that no longer resolve.

Regenerate and commit the result:

  ./gradlew generateLicenseReport --no-configuration-cache
  npm --prefix events-frontend run generate:notices

or run scripts/notices-parity.sh with no argument, which does both.
EOF
exit 1
