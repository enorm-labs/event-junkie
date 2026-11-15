#!/usr/bin/env bash
#
# refuse-gradle-stop-test.sh — assert which commands refuse-gradle-stop.sh refuses and which it lets through.
#
# Feeds fabricated PreToolUse JSON to the hook. A refusal must stop `gradlew --stop` in any position of a command line,
# and never a command that only quotes the words (#5140).
#
# Usage: scripts/refuse-gradle-stop-test.sh — reaches no network, writes nothing.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

HOOK="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/refuse-gradle-stop.sh"
FAILED=0

verdict() {
    local status=0
    python3 -c 'import json, sys; print(json.dumps({"tool_name": "Bash", "tool_input": {"command": sys.argv[1]}}))' "$1" |
        "$HOOK" 2>/dev/null || status=$?
    [ "$status" -eq 2 ] && echo refused || echo allowed
}

expect() {
    local want="$1" command="$2" got
    got="$(verdict "$command")"
    if [ "$got" != "$want" ]; then
        echo "FAIL: expected $want, got $got: $command"
        FAILED=1
    fi
}

expect refused './gradlew --stop'
expect refused 'gradle --stop'
expect refused 'cd ../event-junkie-5140 && ./gradlew --stop'
expect refused './gradlew --stop; ./gradlew build'
expect refused '/Users/someone/repo/gradlew -q --stop'
expect allowed './gradlew build detektMain detektTest -PwarningsAsErrors'
expect allowed './gradlew --no-daemon :events-importer:detekt'
expect allowed "git commit -m 'never run gradlew --stop'"
expect allowed 'grep -n "gradlew --stop" docs/ops/TROUBLESHOOTING.md'
expect allowed 'echo --stop'
echo '{"tool_name": "Bash"' | "$HOOK" || {
    echo "FAIL: unreadable JSON must be allowed"
    FAILED=1
}

[ "$FAILED" -eq 0 ] && echo "refuse-gradle-stop: all cases pass"
exit "$FAILED"
