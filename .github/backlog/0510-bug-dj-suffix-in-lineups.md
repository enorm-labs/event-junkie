---
slug: bug-dj-suffix-in-lineups
title: DJ lineup entries keep their performance-format suffix
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M", "needs-decision"]
priority: P1
status: Backlog
---

**What the source publishes.** Lineup entries like `C3D-E (live)` and `Avangelic (DJ-Set)`.

**What we store.** Both verbatim, so they resolve to *different* artist rows than the same act's
plain name imported from anywhere else.

**Where.** `stripArtistSuffix` already handles exactly this tail — but it is only applied to
headliners derived from a *title*
([`headlinersFromTitle`](../../events-importer/src/main/kotlin/de/norm/events/scraper/ArtistNameMapping.kt)),
never to lineups. Consistently across AMT, ÆDEN, Renate, Duncker and OHM.

**The fix.** Apply it to lineups too — one line per scraper.

**The decision that has to come first.** Is the `(live)` distinction worth preserving somewhere?
The model has no `LIVE` `ArtistRole`, so today the choice is between keeping the suffix glued to the
name and discarding the information entirely. Answering that is what makes this a one-line change
rather than a lossy one.

**Needs a `--full` re-seed?** Yes — cross-cutting, and it merges existing rows.
