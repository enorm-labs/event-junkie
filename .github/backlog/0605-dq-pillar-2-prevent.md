---
slug: dq-pillar-2-prevent
title: Pillar 2 (Prevent) — golden fixture tests and a boundary validation gate
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "size:L", "blocked"]
priority: P1
status: Blocked
blocked-by: [dq-pillar-1-measure]
---

Two halves, both about stopping bad output rather than finding it later.

**Golden fixture tests** from real scraped HTML, for all four normalizers. Real captured pages, not
synthesised ones — the failures on the bugs list are all shapes nobody would have invented.

**A boundary validation gate** that flags obviously-bad output into the curation queue instead of
persisting it silently:

- an empty artist after stripping
- an artist matching a non-artist pattern
- a genre equal to the event title

The word doing the work is **instead**. Today these land in the database and are discovered by
someone reading the site. The gate's value is that it converts a silent wrong answer into a visible
queue item.

Note the dependency: the gate needs somewhere to put what it rejects, and the thresholds are only
defensible once Pillar 1 has measured what "obviously bad" costs today.

**References** — [DATA_QUALITY_STRATEGY.md](../../docs/DATA_QUALITY_STRATEGY.md)
