---
slug: list-promoters-own-table
title: List promoters in their own table in EVENT_DATA_SOURCES.md
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["documentation", "importer", "size:S"]
priority: P2
status: Ready
related: [resolve-venue-per-event]
---

Promoters are currently mixed into the venue tables in
[EVENT_DATA_SOURCES.md](../../docs/EVENT_DATA_SOURCES.md), which hides that they are a **different
kind of source**: cross-venue listings, no house of their own.

Separating them also gives the duplicate-events question somewhere to live. A promoter's listing
largely repeats shows the venues already publish, so importing one needs de-duplication against the
venue-level sources — a fact that is currently recorded nowhere and rediscovered each time a
promoter is considered.

**Done when**

- [ ] A promoter table separate from the venue tables
- [ ] The duplicate-events constraint recorded there, pointing at the per-event venue resolution
      issue
