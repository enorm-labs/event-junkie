---
slug: evaluate-agent-tooling-ideas
title: Evaluate external agent-tooling ideas worth stealing
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:agents", "size:M"]
priority: P2
status: Backlog
---

Evaluate — **don't necessarily install**:

- [awesome-copilot](https://github.com/github/awesome-copilot)
- [superpowers](https://github.com/obra/superpowers)
- [get-shit-done](https://github.com/gsd-build/get-shit-done)
- [Repomix](https://repomix.com/), including its [GitHub Action](https://repomix.com/guide/github-actions)
- the [context-engineering `BACKLOG.md` approach](https://www.codecentric.de/wissens-hub/blog/strukturierte-migration-mit-claude-code-context-engineering-statt-prompt-engineering)

**Recommendation already on record, and it is the thing to test these against:** keep AGENTS.md as
the source of truth; add optional prompt files; **do not adopt always-on ceremony.** Most of these
frameworks work by adding mandatory process to every interaction, which is a real cost paid on every
task for a benefit that shows up on a few.

The `BACKLOG.md` idea is partly overtaken by the issue-tracker migration and the generated backlog
snapshot — worth re-reading it with that in mind rather than as originally filed.

*(Merged from several separate backlog entries — they are one afternoon of reading, not five.)*
