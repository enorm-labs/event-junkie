---
slug: radio-station-listings
title: Radio-station event listings
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["importer", "size:M"]
priority: P2
status: Backlog
parent: more-importers-epic
related: [resolve-venue-per-event]
---

RadioEins, FluxFM, StarFM and similar. Stations promote and co-host events across many venues.

**Note the dependency:** like promoter sources, a station's listing spans venues, so it is not
importable until a venue can be resolved **per event**. Worth recording that here rather than
discovering it partway into a scraper.

De-duplication against the venue-level sources applies for the same reason.
