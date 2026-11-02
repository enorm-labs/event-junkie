#!/usr/bin/env bash
#
# jdk-env.sh — JAVA_HOME and PATH for the JDK that .sdkmanrc pins, for a shell that never ran `sdk env`.
#
# An agent's shell is not interactive, so SDKMAN's auto-env never fires, and it inherits whatever
# JAVA_HOME the launching terminal had. That can name a JDK SDKMAN has since removed, and then
# `./gradlew` stops with "JAVA_HOME is set to an invalid directory". The Gradle toolchain pins only
# the major; this pins the build that `.sdkmanrc` names, the one CI and every contributor use.
#
# Usage:
#   eval "$(scripts/jdk-env.sh)"     # before ./gradlew, in any agent or script shell
#   scripts/jdk-env.sh --claude-hook # Claude Code's SessionStart hook: writes the exports to $CLAUDE_ENV_FILE
#
# Finds SDKMAN through $SDKMAN_DIR, ~/.sdkman or the Homebrew sdkman-cli prefix. Reaches no network.
# A JDK that is not installed is reported, not installed: `sdk env install` does that. The hook
# mode always exits 0, so a missing JDK never blocks a session; its stdout reaches the agent.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
hook=false
[ "${1:-}" = "--claude-hook" ] && hook=true

fail() {
    if $hook; then
        echo "jdk-env.sh: $1 — Gradle will use whatever JAVA_HOME the shell inherited."
        exit 0
    fi
    echo "jdk-env.sh: $1" >&2
    exit 1
}

want="$(sed -n 's/^java=//p' "$REPO_ROOT/.sdkmanrc")"
[ -n "$want" ] || fail "no java= line in .sdkmanrc"

sdkman=""
for dir in "${SDKMAN_DIR:-}" "$HOME/.sdkman" /opt/homebrew/opt/sdkman-cli/libexec /usr/local/opt/sdkman-cli/libexec; do
    if [ -n "$dir" ] && [ -d "$dir/candidates/java" ]; then
        sdkman="$dir"
        break
    fi
done
[ -n "$sdkman" ] || fail "SDKMAN not found; run scripts/setup-mac.sh contributor"

java_home="$sdkman/candidates/java/$want"
[ -x "$java_home/bin/java" ] || fail "JDK $want is not installed; run \`sdk env install\` in the checkout"

exports="export JAVA_HOME='$java_home'
export PATH=\"\$JAVA_HOME/bin:\$PATH\""

if $hook; then
    [ -n "${CLAUDE_ENV_FILE:-}" ] || fail "CLAUDE_ENV_FILE is not set"
    printf '%s\n' "$exports" >>"$CLAUDE_ENV_FILE"
else
    printf '%s\n' "$exports"
fi
