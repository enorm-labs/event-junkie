---
applyTo: "**/*.md"
paths:
    - "**/*.md"
---

# Markdown Formatting

`scripts/format-markdown.sh` is the only formatter that touches Markdown here, and its scope is pinned on purpose. The rules for what documents _say_ are in
[AGENTS.md](../../AGENTS.md) § Agent Instructions.

- **Markdown is formatted by oxfmt**, via `scripts/format-markdown.sh` (config: root `.oxfmtrc.json`, hook: `format-markdown`). Tables aligned, `_emphasis_`,
  `-` bullets, and **prose left exactly where it was wrapped** — `proseWrap: preserve`, so hard wrapping is still yours to place and a prose edit stays a
  one-line diff. Full rationale in [docs/DEVELOPMENT.md](../../docs/DEVELOPMENT.md) §Markdown formatting; the parts that matter when editing:
    - **Never widen it past Markdown.** oxfmt also formats YAML, JSON, CSS and TS, and it **cannot parse the Go-templated YAML** under
      `deploy/charts/*/templates/` at all — it errors and exits 2 on all 16 of them, the same reason `.pre-commit-config.yaml` refuses a `check-yaml` hook.
      Widening it across the repository's YAML and JSON was measured and rejected: 105,005 lines of churn, almost all of it captured scraper fixtures and
      Flux-generated manifests, for 371 useful lines. Scope is pinned in two independent places (the script's arguments and `ignorePatterns`); a change that
      loosens either is a change that breaks the chart build.
    - **Use the pinned binary**, `events-frontend/node_modules/.bin/oxfmt`, never one on `$PATH`. oxfmt is pre-1.0 and its Markdown output is not stable across
      versions, so the hook, CI and every contributor have to be on one version; `package-lock.json` is what makes that reproducible. The script already does
      this — do not "simplify" it to `oxfmt`.
    - **oxfmt reads `.editorconfig`.** The `[*] indent_size = 4` is what gives nested list items their four-space indent. A scratch directory does not
      reproduce this repository's formatting unless `.editorconfig` is copied into it — check that before concluding two oxfmt versions disagree.
    - **Write mode runs it twice**, because a table nested under a list item is skipped on the first pass. This file is the one that exhibits it. **Permanent
      and intended** — Prettier behaves identically and oxfmt tracks Prettier, so upstream closed it as _not planned_; do not try to drop the second run on a
      version bump. See [DEVELOPMENT.md](../../docs/DEVELOPMENT.md) §Markdown formatting.
