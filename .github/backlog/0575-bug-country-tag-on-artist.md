---
slug: bug-country-tag-on-artist
title: A country/origin tag stays attached to the act name
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M"]
priority: P1
status: Ready
---

**What the source publishes.** `Ipkiss (NL)`, `ANEMONE (NL)`, `NIGHT NAIL (Dark Wave US/DE)`,
`Apichat Pakwan (Thailand-Live)`.

**What we store.** All of them verbatim, so they never resolve to the bare spelling of the same act
imported from another venue. **7 artist rows affected today, 5 of them VOID Club's.**

**The fix.** `arkaoda` already strips a trailing all-caps code group — locally. Lift that into the
shared `stripArtistSuffix` and extend it to spelled-out countries.

**Keep the existing carve-out** for a parenthesised *alias*: `Sickboyrari (Black Kray)` is one act
with two names, not an act with an origin tag, and a blanket strip would lose the alias.

**Needs a `--full` re-seed?** Yes — cross-cutting, so `--full` and a diff.
