---
slug: cleanup-kdoc-comments
title: Clean up KDoc comments across the codebase
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["documentation", "refactor", "size:M"]
priority: P2
status: Ready
---

Drop boilerplate and restating-the-obvious comments; keep the rest meaningful.

**Handle with care, because this codebase's KDoc is load-bearing.** Scraper KDoc is the designated
home for *accepted limitations* — a field a venue does not publish, a trade-off a parser makes
deliberately. AGENTS.md and `/scaffold-importer` both direct that reasoning there specifically so it
sits next to the code it constrains.

So the rule for this pass: **delete comments that describe what the code does; keep every comment
that explains why it does it that way.** A comment recording a deliberate trade-off is not
boilerplate no matter how long it is.
