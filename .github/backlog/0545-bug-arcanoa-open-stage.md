---
slug: bug-arcanoa-open-stage
title: Arcanoa's recurring open stage becomes two artists and two slugs
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:S"]
priority: P1
status: Ready
---

**What the source publishes.** The venue hand-types its Monday night both ways: `ARCANOA-Open Stage`
and `ARCANOA- Open Stage`.

**What we store.** Two artists and two slugs — only the second spelling has a dash the parser pads,
so the two normalize differently.

**The fix.** Collapse the whitespace around the dash before normalizing.

The smallest bug on this list, and a good first issue: one normalization step, an obvious test, and
no decision to make first.

**Needs a `--full` re-seed?** Yes, to merge the two existing rows — but the blast radius is one
venue.
