---
slug: bug-event-name-as-headliner-concert
title: An event name is minted as a headliner because the venue typed the night `CONCERT`
type: Bug
milestone: Phase 2 — Coverage & polish
labels: ["importer", "area:data-quality", "size:M", "needs-decision"]
priority: P1
status: Backlog
related: [curated-vocabulary-storage]
---

**What happens.** `buildArtistsForEventType` trusts a `CONCERT` category to mean the title names the
act. But a venue with no better bucket files non-musical shows there too.

**Examples.** Gärten der Welt labels `Drone Art Show: Harry Potter` and
`Taschenlampenweihnachtskonzert` as "Konzerte", and both become artist rows.

**Where** — `buildArtistsForEventType`, `isNonArtistName`.

**Why it is not a one-liner.** `isNonArtistName` already rejects the festival family. Catching these
needs the same **curated vocabulary** the `NON_ARTIST_NAMES` decision calls for: a format-word
denylist (`… show`, `…konzert` with no person in the title) is the right shape, but it must not
swallow an act genuinely named that way — and acts genuinely named that way exist.

**Needs a `--full` re-seed?** Yes.
