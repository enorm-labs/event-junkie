---
slug: resolve-venue-per-event
title: Resolve a venue per event, so promoter sources become importable
type: Feature
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:XL", "needs-decision"]
priority: P1
status: Backlog
related: [list-promoters-own-table]
---

**What is wrong today.** An event's venue comes from its `event_source` row —
`EventUpsertService.upsertAndCleanup(events, venueId, …)`, **one venue for the whole source**. So a
promoter that books across houses cannot be imported at all.

**What it costs.** Puschen, Trinity Music and Landstreicher Booking are deferred on exactly this
(see EVENT_DATA_SOURCES.md § Blocked). Their listings are clean and they name the venue per event —
the data is there, the model cannot hold it.

**What it needs**

1. A venue resolved **per event** — matched by name against existing venues, auto-created otherwise
2. **De-duplication against the venue-level sources.** Roughly 30 of Puschen's 35 shows are at
   venues already imported, so importing it naively would double them.

ADR-sized. It is also the general answer to a show that moves between houses — Huxleys'
relocations — which currently has no answer at all.
