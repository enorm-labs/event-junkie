---
slug: browse-past-events-archive
title: Browse past events — an archive view
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "area:bff", "size:M", "needs-decision"]
priority: P2
status: Backlog
related: [housekeeping-delete-policy]
---

**As** someone who wants to know what an artist or venue has been doing
**I want** to see events that have already happened
**so that** an artist page is a history rather than only a forecast.

Today past events are dropped from every view, and eventually from the database.

**Two decisions come first, and they are linked:** retention — how long past events are kept at all
— and UX, since an archive that is merely the list view without the date filter is not useful. Both
tie into the housekeeping deletion policy; deciding to build an archive is deciding not to delete.
