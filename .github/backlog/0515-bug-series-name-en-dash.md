---
slug: bug-series-name-en-dash
title: A concert-series name appended with an en dash stays on the act
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:L", "needs-decision"]
priority: P1
status: Backlog
related: [event-series-first-class, curated-vocabulary-storage]
---

**What the source publishes.** silent green bills three autumn shows as `Current 93 – Sonic
Morgue`, `Current 93 – Sonic Morgue – Zusatzshow` and `Anja Huwe / Xmal Deutschland – Sonic
Morgue`. `Sonic Morgue` is the **series**; `Zusatzshow` marks the extra date.

**What we store.** Both tails as part of the performer, so these will never resolve to the plain
`Current 93` / `Xmal Deutschland` rows imported from another house.

**Where.** `splitHeadlinerTitle` cuts only on `/`, `+` and conjunctions; `stripArtistSuffix`
recognises a ` - ` tail only when it names a tour, a year or a release.

**Why neither obvious fix is safe.** An act may legitimately contain an en dash, and an act may
legitimately carry a descriptive tail. So this needs **the series names themselves** — the same
curated-vocabulary question as `NON_ARTIST_NAMES`.

**Morphine Raum shows it at its worst.** It bills its whole programme this way:
`Raphael Rogiński – Qırım` and `Alister Spence – Within Without` fuse the album onto the act (3 of
11 events). Where the tail is a *member list*, the conjunction split then fires inside it —
`PICI - Clémence Manachère & Polina Pohozha` becomes `Pici - Clémence Manachère` plus
`Polina Pohozha`, so the first artist row is **neither the duo nor either member**.

**Needs a `--full` re-seed?** Yes.
