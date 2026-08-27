# Vendored skill

Upstream: <https://github.com/cli/cli/tree/trunk/skills/gh>, MIT, © GitHub Inc. — `LICENSE` is the
one from the root of `cli/cli` and is kept verbatim beside this file.

Vendored at `cli/cli` commit `2ff7220ea0052af1fcb423536ddbdebd732ea89b` (2026-08-26), the last one
to touch `skills/gh`. `SKILL.md` is the whole skill; upstream ships no reference files with it.

**The skill assumes `gh` is on `$PATH` and authenticated.** It documents how to drive the binary,
not how to obtain it — `brew install gh && gh auth login`. Without the binary the skill is a page of
commands that all fail the same way.

**Why it is in the repository rather than left to each contributor's global install.**
The same reason as [`asd-ste100`](../asd-ste100/VENDORED.md): the prompts under
[`.github/prompts/`](../../../.github/prompts) drive `gh` constantly — `/open-pr`, `/new-issue`,
`/start-issue`, `/next-issue`, `/security-triage` — and a skill present on one machine only makes
those instructions silently weaker for everybody else, with nothing reporting the difference.

**It is upstream's half of the story, not the whole of it.** This file covers generic `gh`
mechanics: `--json`/`--jq`, pagination limits, `-R`, search versus list, when to drop to `gh api`.
The scars specific to this repository — stale `mergeable_state`, secondary rate limits, the
`--label` / `--add-label` split — stay in [AGENTS.md § Automating GitHub with `gh`](../../../AGENTS.md#automating-github-with-gh).
Do not move repository-specific findings into this directory; the next update would erase them.

**Updating** — re-fetch both files and record the new commit above. Nothing here is locally
modified except this file, so the diff is upstream's alone:

```bash
curl -fsSL -o .claude/skills/gh/SKILL.md https://raw.githubusercontent.com/cli/cli/trunk/skills/gh/SKILL.md
curl -fsSL -o .claude/skills/gh/LICENSE https://raw.githubusercontent.com/cli/cli/trunk/LICENSE
gh api 'repos/cli/cli/commits?path=skills/gh&sha=trunk&per_page=1' --jq '.[0].sha'
```

The skill is excluded from `scripts/format-markdown.sh` and from `scripts/skill-parity.sh` for the
same reason as `asd-ste100`: formatting it would fork the copy, and it is a directory skill invoked
by name rather than a slash command anybody types.
