#!/usr/bin/env bash
#
# schedule-guard-parity.sh — every scheduled job runs only in enorm-labs/event-junkie.
#
# Usage:
#   scripts/schedule-guard-parity.sh   # exits 1 listing each job that would run a schedule on a fork
#
# Reaches no network and writes nothing.
#
# A fork with Actions enabled runs every cron in the copy it took, against production (#3090). So
# every job a `schedule:` starts carries `github.repository == 'enorm-labs/event-junkie'` in its `if:`.
#
# A job with `needs:` skips with the job it needs. An `if:` with `always()`, `failure()` or
# `cancelled()` runs it anyway, so that job needs the guard too.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

python3 - .github/workflows/*.yml <<'EOF'
import re
import sys

GUARD = "github.repository == 'enorm-labs/event-junkie'"
problems = []
checked = 0

for path in sys.argv[1:]:
    text = open(path).read()
    if not re.search(r"^  schedule:\s*$", text, re.M):
        continue
    jobs = text.split("\njobs:\n", 1)[1]
    for block in re.split(r"\n(?=  [A-Za-z0-9_-]+:\s*\n)", "\n" + jobs):
        head = re.match(r"\s*([A-Za-z0-9_-]+):", block)
        if not head:
            continue
        checked += 1
        # The `if:` runs from its own line to the next key at the job's indent.
        cond = re.search(r"^    if:(.*(?:\n      .*)*)", block, re.M)
        cond = cond.group(1) if cond else ""
        needs = re.search(r"^    needs:", block, re.M)
        if needs and not re.search(r"always\(\)|failure\(\)|cancelled\(\)", cond):
            continue
        if GUARD not in cond:
            problems.append("%s: job %s" % (path, head.group(1)))

if problems:
    print("A schedule starts these jobs, and their if: lacks %s:" % GUARD, file=sys.stderr)
    for p in problems:
        print("  " + p, file=sys.stderr)
    sys.exit(1)
print("schedule-guard-parity: %d jobs in scheduled workflows, every one guarded or downstream" % checked)
EOF
