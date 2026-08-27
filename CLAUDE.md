# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The full agent playbook — commands, architecture decisions, R2DBC/Modulith gotchas, and conventions — lives in @AGENTS.md. Read it before assuming defaults.

## Project rules

Conventions that only matter for one kind of file are not in AGENTS.md. They live in [`.github/instructions/`](.github/instructions) and reach this session
through [`.claude/rules/`](.claude/rules), which holds one symlink per topic — `architecture`, `kotlin`, `comments`, `documentation`, `markdown`, `testing`, `ci-cd`. Each
declares `paths:`, so Claude Code pulls it into context when you read a file it matches and leaves it out otherwise.

The same file also carries an `applyTo:` line, which is what GitHub Copilot reads from `.github/instructions/` directly. One copy serves both agents, and
`scripts/rules-parity.sh` fails when the two glob lists drift apart.

**A rule body has to be inline.** An `@` pointer inside a rule file is expanded at launch whatever its `paths:` says, so a pointer-style rule loads its target
into every session and the scoping buys nothing — silently, because the content is there, merely always there.

## Project skills

Slash commands available under `.claude/skills/`:

- `/code-review` — review the current diff
- `/codebase-audit` — comprehensive whole-repo review of code + architecture (size, duplication, conventions, simplification)
- `/commit-message` — generate a commit message from staged changes
- `/compact-comments` — pay down comment volume: classify each block DELETE → RENAME → EXTRACT → RELOCATE → KEEP, apply in that order, and prove the drop
- `/data-quality-audit` — read-only audit of the whole `events` database for data-quality issues
- `/importer-smoke` — runtime smoke test of a single importer: seed, import, inspect the rows, check for regressions
- `/k3d-rehearsal` — run the chart and all three images on a local k3d cluster and prove the stack works end to end, then tear it down
- `/improve-test-coverage` — find and fill coverage gaps
- `/new-issue` — draft and file an issue on the tracker (duplicate check first, then the right form, labels, milestone and board fields)
- `/next-importer` — take one venue from 🔨 Ready in `docs/EVENT_DATA_SOURCES.md` to an open PR (scaffold → smoke-test → fix → ship); repeat, or run under
  `/loop`, to work through the backlog
- `/next-issue` — recommend what to work on next, and say why
- `/open-pr` — branch, commit (Conventional Commits), push, and open a PR in one flow
- `/refactor` — change the shape of the code without changing what it does; the acting counterpart to `/codebase-audit`
- `/start-issue <n>` — pick up an issue: claim it, move the board, cut the branch, read its dependencies, and plan before writing code
- `/scaffold-importer` — scaffold a new venue event importer (scraper) end to end
- `/security-report` — read-only report on the latest OWASP Dependency-Check findings and GitHub Dependabot alerts, reconciled and triaged
- `/security-triage` — work the Security tab down to zero: fix what is cheap, file what is not, dismiss what does not apply (Dependabot + code scanning). The
  mutating counterpart to `/security-report`
- `/squash-commit-message` — write a squash commit message for the current branch
- `/update-dependencies` — bump backend and frontend dependencies safely
- `/update-docs` — find documentation that has stopped being true and correct, delete or leave it, with the check that proves each one
- `/verify` — run the full pre-PR sequence: backend `ktlintCheck detekt build koverLog` + frontend `type-check`, `lint`, `test:unit`, `test:e2e` (chromium),
  `scripts/comment-density.sh check` + `scripts/comment-lint.sh check` + `scripts/skill-parity.sh` + `scripts/rules-parity.sh` always,
  `scripts/format-markdown.sh check` + `scripts/ste-lint.sh check` when the diff touches any `.md`, and `tofu fmt`/`validate` + ShellCheck when it touches
  `infra/`, and `helm lint` + `helm unittest` + `scripts/cluster-assertions.sh` when it touches `deploy/`
- `/write-adr` — turn a decision that has been made into the record of why; claims the next ADR number by writing the file

`scripts/skill-parity.sh` fails when this list, `.claude/skills/` and `.claude/commands/` disagree.

**Two skills in `.claude/skills/` are not slash commands and are not in that list.** They are third-party directories, vendored so they are present for every
contributor rather than only whoever installed them globally, and invoked by name instead of typed:

- [`asd-ste100`](.claude/skills/asd-ste100/SKILL.md) — Simplified Technical English; `/compact-comments` and `/update-docs` call it by name.
- [`gh`](.claude/skills/gh/SKILL.md) — how to drive the GitHub CLI from an agent, from
  [`cli/cli`](https://github.com/cli/cli/tree/trunk/skills/gh). It assumes `gh` is installed (`brew install gh`) and authenticated (`gh auth login`).

Each directory has a `VENDORED.md` recording its upstream commit and the command that refreshes it. **Do not edit anything else inside them** — an edit is
silently reverted by the next update, and repository-specific `gh` findings belong in
[AGENTS.md § Automating GitHub with `gh`](AGENTS.md#automating-github-with-gh) instead.

## Multi-module note

This is a Gradle multi-project build (`events-core`, `events-bff`, `events-importer`, plus `detekt-rules` for the repository's own static-analysis rules) plus a
standalone frontend (`events-frontend/`), the OpenTofu configuration in `infra/` and the Helm chart in `deploy/`. Per-module `CLAUDE.md` files can be added in any of those directories if module-specific guidance is
needed — they're loaded automatically when working in that subtree.

`infra/` and `deploy/` already have one each, and neither is optional reading. `infra/` opens with the OpenTofu commands that must never be run there;
`deploy/` opens with the distinction between rendering the chart (always safe) and installing it (never on your own initiative).
