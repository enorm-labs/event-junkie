---
slug: review-other-typed-events
title: Review events typed `OTHER` — should the event-type enum grow?
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "importer", "needs-decision", "size:M"]
priority: P2
status: Backlog
---

**Four formats have no type of their own today** and are filed under a neighbour:

| Format | Currently | Example |
|---|---|---|
| Comedy | `SHOW` | Cosmic Comedy's whole 57-event programme |
| Dance & theatre | mixed | Theater im Delphi's `Tanz`/`Theater`, the AEG venues' ballet |
| Lectures & panels | `READING` | Urania's programme |
| Sport | not imported | out of scope — see EVENT_SCOPE.md §5 |

**The question is whether a visitor filters on these.** A type that nobody filters by is a column
that costs a migration and buys nothing; a type that people expect and cannot find is a reason to
leave. Comedy is the strongest candidate on volume alone.

Note that adding a type is not free downstream: the type drives artist derivation
(`buildArtistsForEventType`), so a new type needs a decision about whether its titles name
performers.

**References** — [EVENT_SCOPE.md](../../docs/EVENT_SCOPE.md)
