---
slug: curated-by-invites-hosted-by
title: Recover the act from a "curated by / invites / hosted by" title
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M"]
priority: P1
status: Ready
---

**The one genuinely recoverable seam** found by the 2026-08-08 measurement of the `PARTY`/`FESTIVAL`
artist guard — a measurement which otherwise said to *keep* the guard (see
`buildArtistsForEventType`'s KDoc).

**What the sources publish**

- `FOREVER 25 curated by Mila Stern & Esther Silex` and `FOREVER 25 curated by Enorm in Form` (Kater)
- `Sesh Clara Cuve invites` (Club OST)
- `Moritz Biebl Invites` (AMT)
- `Tresor New Faces hosted by Secret Keywords` (Tresor)
- `Antina's Spookhouse by Antina Christ` (Renate)

Each names a booked DJ that is stored nowhere.

**Roughly 8 events at capture — small.** But unlike the party titles around them, **these are
unambiguous**, and the marker words are a closed set. That is what separates this from the guard it
was found next to.

**Where it goes.** None of those five venues route through `buildArtistsForEventType`, so this is a
**shared title-parsing rule** in `ArtistNameMapping` plus a call from each scraper — not a change to
the `PARTY` guard.

**Needs a `--full` re-seed?** Yes, with a diff.
