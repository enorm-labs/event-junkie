---
slug: sortable-list-views
title: Let list views be sorted, not only filtered
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "size:S", "needs-decision"]
priority: P2
status: Backlog
---

Date is the only order today. **The BFF already takes a `sort` parameter on `GET /events`; nothing
in the UI sets it** — so this is a frontend control over an existing capability, not new plumbing.

Candidates: price, and later popularity.

**Decide whether it earns the control before adding one.** A sort dropdown is permanent visual
weight on every list view, and for a date-driven product "sort by date" may be the only order anyone
wants. Popularity does not exist yet, and price is missing on enough events that sorting by it would
be misleading — which is itself an argument for waiting.
