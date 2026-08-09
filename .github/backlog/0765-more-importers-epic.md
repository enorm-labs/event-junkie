---
slug: more-importers-epic
title: Expand importer coverage toward the full Berlin venue list
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["importer", "size:XL"]
priority: P1
status: Ready
---

**The outcome.** Enough of Berlin's music programme in one place that "what's on tonight" is
answerable without checking anywhere else. Coverage is the product; everything else is polish on top
of it.

**Why now.** Post-launch this is the highest-leverage work there is — each venue added makes every
existing feature more useful, and the `/scaffold-importer` and `/next-importer` skills already make
one venue a bounded, repeatable job.

**Definition of done.** Not "all venues" — that is unbounded. Done when the Ready queue in
[EVENT_DATA_SOURCES.md](../../docs/EVENT_DATA_SOURCES.md) is empty and the Blocked list contains
only venues blocked on something real.

**Also folded in here, as a checklist rather than sub-issues:**

- [ ] A strategy for implementing the remaining importers fast — but still clean, robust and fully
      tested
- [ ] Check promoters already in the database and scan their sites for events
- [ ] Cover events at special or one-off locations (Durchlüften Festival @ Humboldtforum,
      Tempelhofer Feld, Olympiastadion)
