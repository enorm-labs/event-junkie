---
slug: curated-vocabulary-storage
title: Decision — curated-vocabulary storage, code versus data
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "needs-decision", "size:M"]
priority: P1
status: Backlog
---

**The question.** Should the denylists, synonym maps and corrections — `NON_ARTIST_NAMES`,
`NAME_CORRECTIONS`, genre synonyms, `ACRONYMS` — move from hardcoded Kotlin into steward-editable
database tables?

**Options**

1. **Keep as code.** Tested, reviewed, versioned, diffable. Every fix needs a redeploy.
2. **Move to DB tables.** A steward fixes a name without a release. Loses test coverage, review, and
   the ability to see in a diff why a name is on a list.

**What it blocks.** Pillar 4's human-in-the-loop step needs live editing to be worth building. It
also decides the shape of at least three parser bugs that are currently stuck on "this needs a
curated vocabulary first" — the en-dash series names, the `CONCERT` format words, and the
non-artist name family.

**Nothing in Pillars 1–3 is blocked by it**, which is why it can be decided deliberately rather than
urgently. Spike plus ADR before Pillar 4 starts.

**References** — [DATA_QUALITY_STRATEGY.md §6](../../docs/DATA_QUALITY_STRATEGY.md)
