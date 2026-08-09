---
slug: near-me-radius-search
title: '"Near me" — filter events by distance from the user'
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "area:bff", "area:legal", "size:L", "blocked"]
priority: P1
status: Blocked
blocked-by: [fix-venue-geo-districts]
related: [venues-map]
---

**As** a visitor
**I want** to filter events by distance from where I actually am
**so that** "what's on near me" is answerable without knowing Berlin's districts.

One of the five questions the product test in
[VISION_ROADMAP_IDEAS.md](../../docs/VISION_ROADMAP_IDEAS.md) says the site should answer, and
currently the only one of the first three it cannot.

The browser Geolocation API supplies coordinates and the timezone, with **manual location entry for
when it is denied or unavailable** — which will be often, and should not feel like a degraded mode.

**Not a pure frontend change.** Geolocation is a consent prompt and a paragraph in the privacy
notice. Build the privacy side with it, not after it.

**Blocked on venue coordinates being trustworthy** — and note this failure mode is silent: a bad
coordinate does not show a wrong pin here, it just quietly omits the venue from results.
