#!/usr/bin/env bash
#
# skill-parity.sh — every skill is a command, every command is a skill, and both point somewhere.
#
# Usage:
#   scripts/skill-parity.sh          # exits 1 listing whatever is out of step
#
# Reaches no network and writes nothing.
#
# `.claude/skills/` and `.claude/commands/` are parallel trees of one-line `@` pointers into
# `.github/prompts/`. Nothing connects them, so a skill added to one and not the other is simply
# absent from the other — no error, no warning, the command just is not there. That is how
# `/importer-smoke`, `/new-issue`, `/next-issue` and `/start-issue` went four releases as skills
# with no command.
#
# The CLAUDE.md list is checked in the same pass because it is the third copy, and the one a
# contributor reads first.
#
# `.claude/skills/asd-ste100/` is deliberately not counted: it is a vendored directory skill
# invoked by name, not a slash command anybody types.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

problems=0

note() {
    printf '  %s\n' "$1" >&2
    problems=1
}

names_in() { find "$1" -maxdepth 1 -name '*.md' -exec basename {} .md \; | sort; }

skills="$(names_in .claude/skills)"
commands="$(names_in .claude/commands)"

while read -r name; do
    [ -z "$name" ] && continue
    [ -f ".claude/commands/$name.md" ] || note "skill without a command: /$name — add .claude/commands/$name.md"
done <<< "$skills"

while read -r name; do
    [ -z "$name" ] && continue
    [ -f ".claude/skills/$name.md" ] || note "command without a skill: /$name — add .claude/skills/$name.md"
done <<< "$commands"

# A pointer is one `@relative/path` line and nothing else; anything else silently resolves to nothing.
for pointer in .claude/skills/*.md .claude/commands/*.md; do
    lines="$(grep -c . "$pointer" || true)"
    target="$(head -1 "$pointer")"
    case "$target" in
        @*) ;;
        *) note "$pointer does not start with an @ pointer"; continue ;;
    esac
    [ "$lines" = "1" ] || note "$pointer holds $lines non-blank lines; a pointer is exactly one"
    resolved="$(dirname "$pointer")/${target#@}"
    [ -f "$resolved" ] || note "$pointer points at ${target#@}, which does not exist"
done

# CLAUDE.md lists each one as a `- `/name`` bullet, optionally followed by arguments.
while read -r name; do
    [ -z "$name" ] && continue
    grep -qE "^- \`/${name}[\` ]" CLAUDE.md || note "/$name is not listed in CLAUDE.md § Project skills"
done <<< "$skills"

# A here-string rather than a pipe: `note` has to reach `problems`, and a pipeline is a subshell.
listed="$(grep -oE '^- `/[a-z][a-z0-9-]*' CLAUDE.md | sed 's/^- `\///' | sort -u)"
while read -r name; do
    [ -z "$name" ] && continue
    [ -f ".claude/skills/$name.md" ] || note "CLAUDE.md lists /$name, which is not a skill"
done <<< "$listed"

if [ "$problems" -ne 0 ]; then
    echo >&2
    echo "Skills, commands and CLAUDE.md disagree. See AGENTS.md § Agent Instructions." >&2
    exit 1
fi

printf 'Skills and commands agree: %s each, every pointer resolves, all listed in CLAUDE.md.\n' \
    "$(echo "$skills" | grep -c .)"
