# ADR-030: `RELOCATED` means the show moved away from this venue, and the row names where to

## Status

**Accepted (2026-09-17) — a `RELOCATED` row is the show's trace at the house it left. The row gains `relocated_to`,
nullable text, holding the destination as the venue's own note names it. The row at the house the show moved to is a
plain `SCHEDULED` event.**

**Does not supersede anything.** [ADR-003](ADR-003_ENTITY_DOMAIN_SEPARATION.md) decided that a scraped event becomes a
row at one boundary, `ScrapedEvent.toEventEntity`. This decision puts one more rule there. `EventStatus` carries
`RELOCATED` since the first migration, without saying which end of a move it marks.

**Implemented with the decision.** [#1551](https://github.com/enorm-labs/event-junkie/issues/1551) is the change.
[#1550](https://github.com/enorm-labs/event-junkie/issues/1550) put the status on the list components first.

## Context

A show that moves house is announced by both houses, in the same words. On 2026-09-17 Huxleys printed "Achtung: Die
Show wird vom Huxleys ins Hole44 verlegt", and Hole 44 printed the same sentence. `parseEventStatus` reads "verlegt"
and returns `RELOCATED`, so both rows carried it. Kate Ryan was `RELOCATED` at Huxleys and `RELOCATED` at Hole 44,
where she plays. Kitty, Daisy & Lewis the same, at Metropol and Huxleys. Production's future window held 24
`RELOCATED` rows that day, and an unknown share of them were the destination — the row that is right.

What that did on the site: the list components rendered no status at all (#1550). A moved show was listed twice as a
concert, once at a venue where nothing happens. The nightly plausibility check flagged every pair, and could not tell
the two apart either.

The notes name the houses in three shapes:

| Shape                 | Example                                                    | Where                            |
| --------------------- | ---------------------------------------------------------- | -------------------------------- |
| both, in one sentence | `Die Show wird vom Huxleys ins Hole44 verlegt`             | Huxleys, Hole 44, Metropol, Lido |
| destination only      | `Verlegt ins Mikropol`, `Zoh Amba - Verlegt ins Bi Nuu`    | Badehaus, Kantine, Frannz, Lido  |
| origin only           | `verlegt vom Frannz`, `von der Uber Eats Music Hall ins …` | Gretchen, Huxleys                |
| neither               | `Verlegt / Relocated`, `GENESIS OWUSU VERLEGT`             | Columbia Theater, Metropol       |

### The constraints a candidate had to satisfy

**Both rows must stay.** The origin row is the one a ticket holder finds, and it is the venue's own notice. The
destination row is the concert. Hiding either loses something a visitor needs.

**The direction is only known against the row's own venue.** The sentence is symmetric. Only a reader that knows
"this row is Huxleys' " can say which end it is. In this codebase that reader is the persistence boundary, where
`venueSlug` arrives.

**The destination is often a house this site does not list.** Kesselhaus, Prachtwerk, Uber Eats Music Hall. A pointer
to a venue row cannot hold those.

**A note may name nothing.** Columbia Theater's badge is the two words. The model must keep a bare `RELOCATED` for it.

## Candidate options

**Hide every `RELOCATED` row in the BFF.** One line. On every mirrored pair it hides the concert too, because the
destination row carries the same status. Fails the first constraint on today's data.

**A pointer to the destination event, `relocated_to_event_id`.** Exact when it resolves, and it links. It resolves only
when the destination is imported, on the same date, under a similar title. That is a heuristic match on every import,
wrong when a title differs and empty when the house is not a source. Fails the third constraint outright.

**The destination as text, `relocated_to`.** What the venue wrote, kept as written. Holds a house this site does not
list. Cannot link on its own. A page can match it against the venue names it has when it wants to.

**Read the direction, store nothing.** Sets the destination row `SCHEDULED` and leaves the origin row a bare
`RELOCATED`. Fixes the double listing. Throws away the one fact the origin row exists to carry.

## Comparison

| Candidate               | Keeps both rows | Holds an unlisted house | Links | Cost                        |
| ----------------------- | --------------- | ----------------------- | ----- | --------------------------- |
| hide `RELOCATED`        | no              | n/a                     | n/a   | one line                    |
| `relocated_to_event_id` | yes             | no                      | yes   | column, matcher, ADR        |
| `relocated_to` text     | yes             | yes                     | later | column, direction rule, ADR |
| direction only          | yes             | n/a                     | no    | direction rule              |

## Decision

**`RELOCATED` marks the origin.** A `verlegt` badge on a row means "read the note". `parseRelocation` takes the two
houses off the sentence, and `resolveRelocation` at `toEventEntity` judges them against the row's `venueSlug`. A
destination that is another house makes the row the origin: `RELOCATED`, `relocated_to` set. A destination that is
this house, or an origin that is another, makes the row the destination: `SCHEDULED`, `relocated_to` null. A note
that names nothing keeps `RELOCATED` and null, as before.

**`relocated_to` is text, as the venue wrote it.** `Hole44`, `Columbia Theater`, `Kesselhaus`. No matching, no id. The
frontend renders "Moved to Hole44" and hides the ticket link on the origin row. The house it points at sells the
tickets.

**Two names are one house when the shorter is a prefix of the longer, at four letters or more.** Both are reduced to
letters and digits first: `Huxleys` and `huxleys-neue-welt`, `Hole` and `hole-44`, `Frannz` and `frannz-club`. The
threshold keeps `Lido` and `Loft` apart and rejects a two-letter fragment.

**The note travels on `ScrapedEvent.statusNote`, never stored.** A scraper sets it where the note is neither the title
nor the description. That is a change badge, or the raw title before `cleanEventTitle` strips the tail. The boundary
reads the note, then the title, the subtitle and the description. The first hit wins.

## Consequences

- One nullable column, `relocated_to`, on `event`. `relocatedTo` on both entities and both BFF responses. A message
  key `events.status.movedTo` in both languages.
- The status of a destination row flips to `SCHEDULED` on its next import, with no re-seed. An origin row that left
  every listing keeps its bare `RELOCATED`.
- `parseEventStatus` reads "auf den 30.05.2027 verlegt" as `POSTPONED`. The relocation verb on a date is a date move,
  found in Hole 44's notes while reading them for this decision.
- The plausibility check treats a `RELOCATED` row as the show's trace, not a concert. It is skipped in the duplicate
  check, and reported only when its destination has no row.
- A house the site does list gets no link from the origin row. A later change may match `relocated_to` against the
  venue names a page already has. Nothing in the model stands in its way.

## References

- [#1531](https://github.com/enorm-labs/event-junkie/issues/1531) — the decision issue, with the three options as first written
- [#1550](https://github.com/enorm-labs/event-junkie/issues/1550) — the status on the list components
- [#1551](https://github.com/enorm-labs/event-junkie/issues/1551) — this change
- `EventFieldMapping.kt` — `parseRelocation`, `resolveRelocation`
- `docs/DATA_MODEL.md` — the column
