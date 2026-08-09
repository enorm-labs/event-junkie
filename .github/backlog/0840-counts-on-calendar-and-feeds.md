---
slug: counts-on-calendar-and-feeds
title: Show the number of displayed events on the calendar and detail-page feeds
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "size:S", "needs-decision"]
priority: P2
status: Backlog
---

**Verified 2026-08-08.** `/events` and `/venues` already show a count, and it is localised with a
real plural rule per language.

What is left is **two surfaces that do not**: the calendar, and the "Upcoming events" feed on every
detail page.

So this issue is not "build the first count" — it is **deciding whether those two surfaces want
one**. A calendar arguably shows its own count visually, and a detail-page feed of three events does
not need to be told it has three. Worth a quick decision and then either doing it or closing this as
declined; leaving it open as an implied gap is the wrong outcome either way.
