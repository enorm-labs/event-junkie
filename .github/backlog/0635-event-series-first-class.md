---
slug: event-series-first-class
title: Decision — should an event series be a first-class entity?
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "area:frontend", "needs-decision", "size:M"]
priority: P1
status: Backlog
related: [curated-vocabulary-storage, bug-series-name-en-dash]
---

**The question.** Series names are everywhere in the source data — silent green's `Sonic Morgue`, a
club's monthly resident night, a promoter's recurring party. Today they exist only as text fused
onto an event title, or as a party name repeated verbatim every month.

**Three things a real `event_series` would give**

1. Something for people to **browse, filter and follow**
2. The **curated vocabulary the parsers need** to strip a series name off an act — which is what the
   en-dash bug is stuck on
3. A sensible target for the follow/notification work in Phase 3

**Why it is worth deciding early.** Phase 3 has to pick its subscribable entities, and adding a new
one after people have subscriptions is materially harder than including it from the start.

Model and UI decision, closely related to the curated-vocabulary question — they may well be one
decision.
