---
slug: cover-venues-without-importers
title: Cover venues that will never have an automatic import
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["importer", "documentation", "size:L"]
priority: P2
status: Backlog
related: [admin-manual-event-entry]
---

Some venues have no website at all, or publish a programme only via Instagram, Facebook or Resident
Advisor. They are not blocked on scraper effort — there is nothing to scrape.

**Three things are needed, and the third is the one that gets forgotten:**

1. **A recorded list** of those venues in EVENT_DATA_SOURCES.md, with a link to wherever their
   programme *is* visible
2. **A low-friction way to enter their events by hand** — see the admin manual-entry issue
3. **A reminder mechanism**, so checking them does not get forgotten

Without (3) this becomes a list of venues that were entered once, in one enthusiastic afternoon, and
have been stale ever since — which is worse than not listing them, because the site then shows
confidently outdated programmes.
