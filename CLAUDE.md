# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

The full agent playbook — commands, architecture decisions, R2DBC/Modulith gotchas, and conventions — lives in @AGENTS.md. Read it before assuming defaults.

## Project skills

Slash commands available under `.claude/skills/`:

- `/code-review` — review the current diff
- `/codebase-audit` — comprehensive whole-repo review of code + architecture (size, duplication, conventions, simplification)
- `/commit-message` — generate a commit message from staged changes
- `/data-quality-audit` — read-only audit of the whole `events` database for data-quality issues
- `/importer-smoke` — runtime smoke test of a single importer: seed, import, inspect the rows, check for regressions
- `/k3d-rehearsal` — run the chart and all three images on a local k3d cluster and prove the stack works end to end, then tear it down
- `/improve-test-coverage` — find and fill coverage gaps
- `/new-issue` — draft and file an issue on the tracker (duplicate check first, then the right form, labels, milestone and board fields)
- `/next-importer` — take one venue from 🔨 Ready in `docs/EVENT_DATA_SOURCES.md` to an open PR (scaffold → smoke-test → fix → ship); repeat, or run under
  `/loop`, to work through the backlog
- `/next-issue` — recommend what to work on next, and say why
- `/open-pr` — branch, commit (Conventional Commits), push, and open a PR in one flow
- `/start-issue <n>` — pick up an issue: claim it, move the board, cut the branch, read its dependencies, and plan before writing code
- `/scaffold-importer` — scaffold a new venue event importer (scraper) end to end
- `/security-report` — read-only report on the latest OWASP Dependency-Check findings and GitHub Dependabot alerts, reconciled and triaged
- `/squash-commit-message` — write a squash commit message for the current branch
- `/update-dependencies` — bump backend and frontend dependencies safely
- `/verify` — run the full pre-PR sequence: backend `ktlintCheck detekt build koverLog` + frontend `type-check`, `lint`, `test:unit`, `test:e2e` (chromium), and
  `tofu fmt`/`validate` + ShellCheck when the diff touches `infra/`, and `helm lint` + the render assertions when it touches `deploy/`

## Multi-module note

This is a Gradle multi-project build (`events-core`, `events-bff`, `events-importer`) plus a standalone frontend (`events-frontend/`), the OpenTofu
configuration in `infra/` and the Helm chart in `deploy/`. Per-module `CLAUDE.md` files can be added in any of those directories if module-specific guidance is
needed — they're loaded automatically when working in that subtree.

`infra/` and `deploy/` already have one each, and neither is optional reading. `infra/` opens with the OpenTofu commands that must never be run there;
`deploy/` opens with the distinction between rendering the chart (always safe) and installing it (never on your own initiative).
