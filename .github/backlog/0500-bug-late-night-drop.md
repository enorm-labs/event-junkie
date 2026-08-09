---
slug: bug-late-night-drop
title: A late-night club event is dropped at midnight while it is still running
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M"]
priority: P1
status: Ready
related: [bug-no-event-end-time]
---

**What the source publishes.** A night listed as `31/07 23:00` that actually runs until roughly
06:00 the following morning — the normal shape for every late-opening club.

**What we store.** The event, correctly. But `EventUpsertService.dropPastEvents` compares **dates
only**, so at 00:00 the event disappears from the app — hours before it ends, while people are
still deciding whether to go.

**Blast radius.** Every late-opening club: OHM, Berghain, Tresor, Renate and the rest. **OHM feels
it hardest** because its whole horizon is one to three nights, so losing tonight's is losing a
third of its programme.

**Where** — `EventUpsertService.dropPastEvents`.

**The fix.** A cutoff that accounts for the start time — keep an event until
`eventDate + 1 day 06:00` when it starts after roughly 22:00 — rather than a per-importer
workaround. Doing it per importer would mean every club scraper carrying the same special case.

**Needs a `--full` re-seed?** No. It changes a read-time filter, not stored data.

The clean version of this fix needs an event end time, which the model does not have. That is a
separate issue; this one is worth doing with a heuristic in the meantime, because the current
behaviour actively loses events people are looking for.
