#!/usr/bin/env bash
#
# refuse-gradle-stop.sh — a Claude Code PreToolUse hook that refuses `gradlew --stop` (#5140).
#
# `--stop` stops every Gradle daemon of that version on the machine, so it kills the build of every other session in
# another worktree. `./gradlew --no-daemon <tasks>` gives one run a fresh JVM and stops nothing else. Quoted text is
# set aside first, so a commit message or a grep that names the command passes.
#
# Usage: scripts/refuse-gradle-stop.sh < hook.json — reads the hook's JSON on stdin; exit 2 refuses, exit 0 allows.
#        .claude/settings.json runs it before every Bash call.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

python3 -I -c '
import json, re, sys

try:
    command = json.load(sys.stdin).get("tool_input", {}).get("command", "")
except ValueError:
    sys.exit(0)
unquoted = re.sub(r"\x27[^\x27]*\x27|\"(?:[^\"\\\\]|\\\\.)*\"", " ", command)
if re.search(r"(?:^|[\s;&|(])(?:\S*/)?gradlew?(?:\s+[^;&|]*?)?\s--stop(?:\s|$|[;&|)])", unquoted):
    print(
        "Refused: gradlew --stop stops every Gradle daemon on this machine, including the builds of other sessions (#5140). "
        "Run ./gradlew --no-daemon <tasks> for a fresh JVM instead.",
        file=sys.stderr,
    )
    sys.exit(2)
'
