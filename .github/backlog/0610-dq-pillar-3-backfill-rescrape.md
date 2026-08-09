---
slug: dq-pillar-3-backfill-rescrape
title: Pillar 3 (Fix) — one deliberate backfill re-scrape
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "importer", "size:M"]
priority: P1
status: Backlog
---

Title-as-headliner extraction is **built and shipping** — `buildArtistsForEventType` and
`headlinersFromTitle`, with Cassiopeia's ambiguous titles guarded by the widened `isNonArtistName`
festival filter.

But **existing rows keep no artist until they are re-imported.** So the roughly 40% of concerts
stored artist-less are only recovered for sources that have since been re-scraped for some other
reason.

**Why this has not happened yet, stated plainly:** the extraction rules have kept changing
underneath it — the `club` keyword, the label showcase — each needing its own re-seed. One
deliberate pass has never been the next thing to do.

**Which means the sequencing matters.** Doing this before the cross-cutting artist bugs land
(`--full` re-seed items on the bugs list) wastes the pass. Worth batching them: fix the
cross-cutting parsers, then re-seed once, then diff.

**Done when**

- [ ] A `--full` re-seed run deliberately, not as a side effect
- [ ] A before/after diff recorded, so the recovery is a number
