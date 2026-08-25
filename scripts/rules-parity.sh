#!/usr/bin/env bash
#
# rules-parity.sh — one rule file, two agents, and no second copy of anything.
#
# Usage:
#   scripts/rules-parity.sh          # exits 1 listing whatever is out of step
#
# Reaches no network and writes nothing.
#
# A rule lives once in `.github/instructions/`, carrying `applyTo` for Copilot and `paths` for
# Claude Code, which reaches it through a symlink in `.claude/rules/`. Each agent reads only its own
# key, so neither can report the two describing different globs. The AGENTS.md table is the third
# copy, and the one an agent that reads no rules at all follows.
#
# An `@` pointer in a rule body is expanded at launch whatever `paths` says, so it loads every
# session while appearing to be scoped. That is why a body must be inline.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

problems=0

note() {
    printf '  %s\n' "$1" >&2
    problems=1
}

# The globs each agent reads, normalised to one per line so the two are comparable.
copilot_globs() { sed -n 's/^applyTo: *"\(.*\)" *$/\1/p' "$1" | tr ',' '\n' | sed 's/^ *//;s/ *$//' | sort; }
claude_globs() { sed -n '/^paths:/,/^[^ -]/p' "$1" | sed -n 's/^ *- *"\(.*\)" *$/\1/p' | sort; }

names="$(find .github/instructions -maxdepth 1 -name '*.instructions.md' -exec basename {} .instructions.md \; | sort)"
[ -n "$names" ] || note "no rules found under .github/instructions/"

while read -r name; do
    [ -z "$name" ] && continue
    rule=".github/instructions/$name.instructions.md"
    link=".claude/rules/$name.md"

    if [ ! -L "$link" ]; then
        note "$rule has no symlink at $link — Claude Code will not see it"
    elif [ ! -f "$link" ]; then
        note "$link is a broken symlink"
    elif [ "$(cd "$(dirname "$link")" && readlink "$(basename "$link")")" != "../../$rule" ]; then
        note "$link points at $(readlink "$link"), not ../../$rule"
    fi

    grep -q '^applyTo: ' "$rule" || note "$rule has no applyTo: line — Copilot will not scope it"
    grep -q '^paths:' "$rule" || note "$rule has no paths: list — Claude Code will load it every session"

    if ! diff -q <(copilot_globs "$rule") <(claude_globs "$rule") >/dev/null 2>&1; then
        note "$rule: applyTo and paths describe different globs"
        diff <(copilot_globs "$rule") <(claude_globs "$rule") | sed 's/^/      /' >&2 || true
    fi

    # `@path` outside a code span is an import, and imports defeat path scoping.
    if grep -nE '^[[:space:]]*@[A-Za-z0-9_./~-]+[[:space:]]*$' "$rule" >/dev/null; then
        note "$rule contains an @ import; a rule body must be inline or it loads every session"
    fi

    grep -q "(.github/instructions/$name.instructions.md)" AGENTS.md ||
        note "$name is not linked from the AGENTS.md rules table"
done <<< "$names"

# Nothing in .claude/rules/ may be anything but a symlink into the tree above.
for link in .claude/rules/*.md; do
    [ -e "$link" ] || continue
    name="$(basename "$link" .md)"
    [ -L "$link" ] || note "$link is a real file; it must be a symlink into .github/instructions/"
    [ -f ".github/instructions/$name.instructions.md" ] ||
        note "$link has no rule at .github/instructions/$name.instructions.md"
done

if [ "$problems" -ne 0 ]; then
    echo >&2
    echo "Rules are out of step. See AGENTS.md § The short version." >&2
    exit 1
fi

printf 'Rules agree: %s of them, every symlink resolves, applyTo matches paths, all linked from AGENTS.md.\n' \
    "$(echo "$names" | grep -c .)"
