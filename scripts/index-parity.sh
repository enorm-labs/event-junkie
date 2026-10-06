#!/usr/bin/env bash
#
# index-parity.sh — every script is in the index, every path named anywhere exists, and each answers --help.
#
# Usage:
#   scripts/index-parity.sh          # exits 1 listing whatever is out of step
#
# Reaches no network and writes nothing. Runs every script with `--help`, which each one answers
# before touching a tool, a file or the network — that is the rule this check enforces, so a
# script that reaches its tool check first fails here on a runner that lacks the tool.
#
# `scripts/README.md` is the index. It is hand-written, because the one-line purpose of a script is
# the one thing a generator cannot improve on, so the two can drift: a script added without a row,
# a row left behind after a delete. A table that lists half the scripts says nothing about which half.
#
# The third check is tree-wide. A reference to a script that no longer exists reads plausibly in a
# runbook or a prompt for months; a workflow finds out on its next run. `scripts/<name>` is matched
# only where nothing precedes the `scripts/`, so `deploy/scripts/render-assertions.sh` — a
# directory that #430 removed and two comments still name as history — is not a finding.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

INDEX=scripts/README.md
# Sourced into a shell, never run: it defines functions and has no `--help` to answer.
SOURCED_ONLY=shell-aliases.sh

problems=0

note() {
    printf '  %s\n' "$1" >&2
    problems=1
}

[ -f "$INDEX" ] || {
    note "$INDEX does not exist"
    exit 1
}

# --- 1. every file in the directory has a row -------------------------------------------------
#
# A row names the file in backticks. Grepping for the backticked name rather than parsing the table
# keeps the index free to grow prose around it.

files="$(find scripts -maxdepth 1 -type f ! -name README.md -exec basename {} \; | sort)"
indexed=0
while read -r name; do
    [ -z "$name" ] && continue
    if grep -qF "\`$name\`" "$INDEX"; then
        indexed=$((indexed + 1))
    else
        note "scripts/$name has no row in $INDEX"
    fi
done <<<"$files"

# --- 2. every path the index names exists ---------------------------------------------------------
#
# A bare name is a file in scripts/; anything with a slash is a path from the repository root, which
# is how the index names the three operator scripts that live under deploy/ and infra/.

# shellcheck disable=SC2016 # the backticks are the pattern, not a command substitution
named="$(grep -oE '`[A-Za-z0-9_./-]+\.(sh|py|txt)`' "$INDEX" | tr -d '`' | sort -u)"
while read -r ref; do
    [ -z "$ref" ] && continue
    case "$ref" in
        */*) path="$ref" ;;
        *) path="scripts/$ref" ;;
    esac
    [ -f "$path" ] || note "$INDEX names \`$ref\`, and $path does not exist"
done <<<"$named"

# --- 3. every scripts/<name> referenced in the tree exists ----------------------------------------
#
# `-I` skips binaries. The character class before `scripts/` is the anchor described in the header;
# a match at the start of a line has nothing before it and is kept by the `^` alternative.

while read -r ref; do
    [ -z "$ref" ] && continue
    [ -f "$ref" ] || note "the tree references $ref, which does not exist — run: git grep -n '$ref'"
done <<<"$(git ls-files -z | xargs -0 grep -IohsE '(^|[^A-Za-z0-9_/.-])scripts/[A-Za-z0-9_.-]+\.(sh|py)' |
    sed -E 's/^[^s]*scripts\//scripts\//' | sort -u)"

# --- 4. every script answers --help --------------------------------------------------------------
#
# Exit 0 and a first line that starts with `Usage:` (`usage:` from argparse). The first line is checked
# because exit 0 alone proves too little: a script that ignores `--help` and does its ordinary, cheap
# work passes on exit code, and the first version of this check let exactly that through.

answers=0
for script in scripts/*.sh; do
    name="$(basename "$script")"
    [ "$name" = "$SOURCED_ONLY" ] && continue
    if [ ! -x "$script" ]; then
        note "$script is not executable — run: chmod +x $script"
        continue
    fi
    if out="$("$script" --help 2>/dev/null)" && [[ "$out" == [Uu]sage:* ]]; then
        answers=$((answers + 1))
    else
        note "$script does not answer --help with exit 0 and a line starting Usage:"
    fi
done

for script in scripts/*.py; do
    if out="$(python3 "$script" --help 2>/dev/null)" && [[ "$out" == [Uu]sage:* ]]; then
        answers=$((answers + 1))
    else
        note "$script does not answer --help with exit 0 and a line starting Usage:"
    fi
done

if [ "$problems" -ne 0 ]; then
    echo >&2
    echo "The scripts index, the directory and the tree disagree. See scripts/README.md." >&2
    exit 1
fi

printf 'Scripts index agrees: %s files indexed, every referenced path resolves, %s scripts answer --help.\n' \
    "$indexed" "$answers"
