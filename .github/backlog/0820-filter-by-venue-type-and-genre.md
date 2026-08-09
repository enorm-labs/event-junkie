---
slug: filter-by-venue-type-and-genre
title: Filter events by venue type, and venues by type and genre
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "area:bff", "size:M", "blocked"]
priority: P1
status: Blocked
blocked-by: [enrich-venues]
---

Two filters over the same missing data, which is why they are one issue:

- **events** filtered by venue type — "clubs only", "concert halls only"
- **venues** filtered by type and by genre

"A club night" and "a seated concert" are different evenings, and the site currently cannot tell
them apart in a filter even though the distinction is the first thing anyone makes when deciding
where to go.

**Blocked on venue enrichment**: neither filter can exist until venue type and per-venue genres are
populated. That dependency is the whole reason these have sat unbuilt.

*(Merged from two backlog items — they are one data dependency and one UI pass.)*
