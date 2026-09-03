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
# **The output depends on the platform, and Linux is the authority.** `license-checker` walks
# `node_modules`, and optional dependencies differ by operating system: a macOS install carries
# `fsevents`, a Linux one does not. Regenerating on a laptop therefore produces a file that fails in
# CI, with no hint as to why — the diff is one component in four thousand lines. That is not
# theoretical; it is how the first run of this check failed. The warning below is the only defence,
# because the honest fix belongs in the generator rather than here: `fsevents` is a native
# file-watcher binding that reaches no user, and it should not be in a production notice on any
# platform.

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

# Separate from `regenerate` because `check` prints it before doing anything: on a macOS laptop the
# failure that follows is almost certainly this rather than a real drift.
warn_platform() {
    [[ "$(uname -s)" == "Linux" ]] && return 0
    cat >&2 <<'PLATFORM'
notices-parity.sh: this is not Linux, and the notices differ by platform.

A macOS install carries optional dependencies a Linux one does not (fsevents), so a file
regenerated here will fail in CI. Generate it the way CI does instead:

  docker run --rm -v "$PWD":/w -w /w/events-frontend node:24-bookworm sh -c 'npm ci && npm run generate:notices'

The Gradle half is platform-independent and can be produced locally.

PLATFORM
}

if [[ "$MODE" == "fix" ]]; then
    warn_platform
    regenerate
    exit 0
fi

warn_platform

BEFORE="$(mktemp)"
cp "$NOTICES" "$BEFORE"
# Unconditional, so an interrupted or failing regeneration does not leave a half-written legal
# document in the tree.
trap 'cp "$BEFORE" "$NOTICES"; rm -f "$BEFORE"' EXIT

regenerate >/dev/null

if diff -q "$BEFORE" "$NOTICES" >/dev/null; then
    printf 'The committed notices match the resolved dependencies.\n'
    exit 0
fi

added="$(comm -13 \
    <(jq -r '[.. | objects | select(.name and .version) | .name] | .[]' "$BEFORE" | sort -u) \
    <(jq -r '[.. | objects | select(.name and .version) | .name] | .[]' "$NOTICES" | sort -u) |
    wc -l | tr -d ' ')"
removed="$(comm -23 \
    <(jq -r '[.. | objects | select(.name and .version) | .name] | .[]' "$BEFORE" | sort -u) \
    <(jq -r '[.. | objects | select(.name and .version) | .name] | .[]' "$NOTICES" | sort -u) |
    wc -l | tr -d ' ')"

# The counts rather than the diff, because the diff is four thousand lines of generated JSON and
# says nothing a reader can act on. What matters is whether components appeared or disappeared.
cat >&2 <<EOF
notices-parity.sh: $NOTICES is stale.

  ${added} component(s) missing from it, ${removed} listed that no longer resolve.

Regenerate and commit the result:

  ./gradlew generateLicenseReport --no-configuration-cache
  npm --prefix events-frontend run generate:notices

or run scripts/notices-parity.sh with no argument, which does both.
EOF
exit 1
