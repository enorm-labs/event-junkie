---
slug: venues-map
title: Add a map to the venues overview, plotting tonight's events
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "size:L", "blocked"]
priority: P1
status: Blocked
blocked-by: [fix-venue-geo-districts]
related: [near-me-radius-search]
---

**As** someone deciding where to go tonight
**I want** to see venues on a map, with pins showing what is on
**so that** "what is near me and worth going to" is one glance instead of a list and a mental map of
Berlin.

The venues overview is currently a list, and a list is the wrong shape for a question about
geography.

**Worth building the second half at the same time:** plotting **today's events** rather than only
venue locations. A map of venues is a directory; a map of tonight is the product. The data is the
same query the events list already makes.

**Blocked on coordinates being right** — a wrong pin reads as a broken map, and the geo audit exists
for exactly this.
