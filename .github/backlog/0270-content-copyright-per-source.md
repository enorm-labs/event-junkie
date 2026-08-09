---
slug: content-copyright-per-source
title: Track copyright and licence status per event source
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:legal", "area:data-quality", "size:M"]
priority: P1
status: Backlog
---

Two things are scraped that the project currently does not display, precisely because their status
is unclear: event **descriptions** and event **images**.

The question is per-source, not global — a venue publishing under a permissive notice and a venue
publishing agency-licensed press photos are different answers. So the answer needs somewhere to
live: a copyright/licence status on the `event_source` row.

That field then **drives the display decision** rather than the display decision being made once,
globally, and wrongly.

**Done when**

- [ ] A licence/copyright status field exists per source, with a defined vocabulary
- [ ] It is populated for the existing sources
- [ ] The frontend display decision reads it instead of hardcoding "never show descriptions"

**Related** — the frontend issue on whether to display descriptions and source images is downstream
of this one.
