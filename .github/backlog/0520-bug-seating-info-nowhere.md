---
slug: bug-seating-info-nowhere
title: A venue's seating information has nowhere to go
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M", "needs-decision"]
priority: P2
status: Backlog
---

**What the source publishes.** Kulturhaus Peter Edel badges every one of its 39 events with two
facts a ticket buyer actually decides on:

- whether the room is seated — `Bestuhlt` / `Teilbestuhlt` / `Unbestuhlt`
- whether a seat is guaranteed — `Freie Platzwahl` / `Keine Sitzplatzgarantie` /
  `Mit Sitzplatzreservierung`

**What we store.** Neither. `PeterEdelOverviewPageScraper` drops both because `Event` has no field
for them.

**Not a Peter-Edel-only signal.** Bar jeder Vernunft, Admiralspalast, Theater im Delphi and the
arena-scale rooms all seat some shows and not others, and the same distinction shows up in their
prose rather than in a badge.

**The decision first.** A `seating` enum on `event` plus a boolean, or a single free-text column?
The badge vocabulary is closed and small, which argues for the enum; the prose in other venues is
not, which argues for text. Settle it **before** any scraper starts filling it, or the second
venue's shape will not fit the first venue's column.

**Needs a `--full` re-seed?** No — new field, populated going forward.
