---
slug: elfsight-monthly-recurrence
title: Expand Elfsight monthly recurrence rules
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "size:M"]
priority: P1
status: Ready
---

**What happens.** Humboldthain expands the **weekly** rules its resident night uses. Neue Zukunft's
recurring entries are **monthly** (`repeatPeriod: nthDayInMonth`, `repeatFrequency: monthly`) and
are still imported once, at their start date only.

**Blast radius.** 4 of 44 Neue Zukunft entries.

**The trap that makes this one change, not two.** Fixing it also needs `NeueZukunftApiScraper`'s
`sourceId` to carry the occurrence date — Humboldthain already does this. Without that, every
expanded occurrence collides on one id. **With** it, every existing Neue Zukunft event is re-minted
under a new id.

So: do it as a single change with one re-seed, rather than shipping the expansion and the id change
separately and re-minting twice.

**Needs a `--full` re-seed?** Yes, for Neue Zukunft.
