# Copilot Instructions

This repository keeps its conventions in `AGENTS.md` files. Always read and follow these before generating code:

- **Backend** (Kotlin/Spring Boot): [`/AGENTS.md`](../AGENTS.md)
- **Frontend** (Vue 3/TypeScript): [`/events-frontend/AGENTS.md`](../events-frontend/AGENTS.md)
- **Infrastructure** (OpenTofu/Hetzner): [`/infra/AGENTS.md`](../infra/AGENTS.md) — opens with the commands that must never be run unasked
- **Deployment** (Helm/Flux): [`/deploy/AGENTS.md`](../deploy/AGENTS.md) — opens with the difference between rendering the chart and installing it

The last two matter most, because both open with a safety rule and neither was listed here before.

Conventions that apply to one kind of file rather than to everything live in [`.github/instructions/`](instructions), one file per topic, each declaring its
own `applyTo` globs. Copilot loads them for matching files by itself; the same files are what Claude Code reads through `.claude/rules/`, so there is one copy
of each rule and not two.

Two third-party skills are available at [`.github/skills/`](skills), each a symlink into `.claude/skills/` — again one copy, reachable by both names:

- `gh` — how to drive the GitHub CLI: `--json`/`--jq`, the limits that truncate a list silently, `-R`, search versus list, when to drop to `gh api`. What it
  does **not** cover is this repository's own `gh` scars; those are in [`AGENTS.md`](../AGENTS.md) § Automating GitHub with `gh`.
- `asd-ste100` — Simplified Technical English, which is how everything under `docs/` is written.

Both are vendored and kept byte-identical to upstream. Each carries a `VENDORED.md`; do not edit anything else inside those directories, because the next
update overwrites it.
