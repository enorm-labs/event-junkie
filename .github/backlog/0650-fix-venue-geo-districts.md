---
slug: fix-venue-geo-districts
title: Check and fix venue districts, addresses and geo-coordinates
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "size:M"]
priority: P1
status: Ready
related: [venues-map, near-me-radius-search]
---

District, address and coordinates were populated as venues were added, with varying care, and
nothing has ever audited them.

**Two features depend on this being right, and both fail in ways that look like feature bugs:**

- the **venues map** — a wrong coordinate is a pin in the wrong place, which reads as a broken map
- **"near me" radius search** — a wrong coordinate silently excludes a venue from results, which
  nobody notices at all

The second failure mode is the reason this is worth doing *before* those features rather than after.
A missing pin gets reported; a missing search result does not.

**Done when**

- [ ] Every venue's coordinates verified against its real address
- [ ] Districts checked against Berlin's actual boundaries, not the venue's self-description
- [ ] A check that would catch a future bad coordinate — a plausibility bound at minimum
