---
slug: exhibitions-as-runs
title: Deferred — exhibitions as first-class runs, blocked on the time model
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["needs-decision", "blocked", "size:L"]
priority: P2
status: Blocked
related: [bug-no-event-end-time]
---

A run of weeks or months, rather than a start time on one evening, needs a **date range in the
schema** plus a display decision — a list sorted by date does not know what to do with something
that is on for three months.

**The related honesty gap worth fixing either way:** `EXHIBITION` today means an *opening*, not a
run. A `vernissage` has a start time, which is why it fits the current model — but the type name
promises something the data does not deliver, and a visitor filtering for exhibitions gets openings.

**References** — [EVENT_SCOPE.md §2](../../docs/EVENT_SCOPE.md)
