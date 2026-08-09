---
slug: bug-lineup-before-type-final
title: Derive the lineup after the event type is final, not before
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M"]
priority: P1
status: Ready
---

**What happens.** `ScrapedEvent.toEventEntity` promotes a `CONCERT` or `OTHER` title to `FESTIVAL`
via `isFestivalTitle` — but by then every scraper has **already** built its artists from its *own*
type inference. So a festival title still mints headliners.

**Example.** `ELLE & L's Festival` at Columbia Theater becomes an artist called `Elle`. The same
shape appears at Clash and Gretchen.

**Where** — `ScrapedEvent.toEventEntity`, `isFestivalTitle`, and every scraper's own artist
derivation.

**The fix.** Drop the artists at the boundary when the *resolved* type is `FESTIVAL` or `PARTY`,
once, rather than teaching each importer to guess the final type before it is known. The ordering
is the bug; the individual scrapers are not wrong so much as asked to decide too early.

**Needs a `--full` re-seed?** Yes — existing artist rows minted this way stay until re-imported.
