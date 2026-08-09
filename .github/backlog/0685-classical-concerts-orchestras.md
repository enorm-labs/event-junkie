---
slug: classical-concerts-orchestras
title: Deferred — classical concerts and orchestras, blocked on the artist model
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["importer", "needs-decision", "blocked", "size:L"]
priority: P2
status: Blocked
---

**Wanted, and deliberately deferred.** Berliner Symphoniker, RBB Sendesaal, Konzerthaus,
Philharmonie.

It fits the existing `CONCERT` type, but **the data shape differs**: orchestra or ensemble, plus
conductor, plus soloists — rather than headliner plus support. So `ArtistRole` and the genre
vocabulary must be extended first, with an ADR.

> **Do not import an orchestral house by flattening it into headliner-plus-support.** The data would
> be wrong in a way that is expensive to unpick, and it would be wrong across hundreds of events
> before anyone noticed.

**RBB Sendesaal's scraping is already solved** — server-rendered ROC calendar,
`.ConcertListItem-location` is the only filter needed. It stays in Blocked purely on the model
question, not on parsing difficulty. That is worth knowing before someone re-investigates it.
