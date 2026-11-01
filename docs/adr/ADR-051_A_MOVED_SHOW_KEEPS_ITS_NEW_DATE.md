# ADR-051: A show that moves house and date keeps the new date as its note states it

## Status

**Accepted (2026-10-19) — a `RELOCATED` row gains `relocated_on`, a nullable date beside `relocated_to`. It holds the new date as the venue's note states
it. A `RELOCATED` row whose note names a new date and no house is stored `POSTPONED`.**

**Amended by [ADR-056](ADR-056_A_POSTPONED_SHOW_KEEPS_ITS_NEW_DATE.md)**, which fills `relocated_on` on a `POSTPONED` row too. The Decision's sentence "A
`POSTPONED` row needs no column" no longer holds.

**Amends [ADR-030](ADR-030_RELOCATED_IS_THE_ORIGIN.md).** ADR-030 stores where a show went. This decision stores when, under the same rule: the text and the
date as the venue wrote them, and no match against another row. It does not change which end of a move is `RELOCATED`.

**Implemented with the decision.** [#3613](https://github.com/enorm-labs/event-junkie/issues/3613) is the change. [#3578](https://github.com/enorm-labs/event-junkie/issues/3578)
holds the analysis and the three options.

## Context

On 2026-10-18, Lido printed "VERSCHOBEN UND VERLEGT AUF DEN 13.04.27 INS HOLE 44" on a show dated 19 October 2026. The row became `RELOCATED`, with
`relocated_to` set to `Hole 44`. The page said "Moved to Hole 44" on a 19 October row and nothing about April. The importer read the date, because
`resolvePostponement` matches it. No column held it.

Kesselhaus showed the same gap without a house. Its badge said "verlegt" and its subtitle said `verlegt auf 17.08.27`. The row was a bare `RELOCATED`. The
new date's row exists at the same venue, but the BFF links a new date for a `POSTPONED` row only ([#3093](https://github.com/enorm-labs/event-junkie/issues/3093)).

Production's upcoming window held 28 `RELOCATED` rows that day. Two carried a new date in their own notes: the two above.

### The constraints a candidate had to satisfy

**ADR-030's rule holds.** The row keeps what the note says and matches nothing. The destination is often a house this site does not list.

**One status cannot say both.** `EventStatus.RELOCATED` covers "a different venue or date". A row that moved both needs a second field, not a second status.

**A move of the date alone is a postponement.** "verlegt auf 17.08.27" moves the date. `parseEventStatus` already reads it as `POSTPONED` when it is the
badge. Kesselhaus puts it in the subtitle, beside a separate "verlegt" badge.

## Candidate options

**Store the new date as the note states it.** A nullable `relocated_on` date beside `relocated_to`. Costs a migration, an importer change, a BFF field and a
frontend line. Fixes both rows.

**Link the destination row at read time.** Extend #3093 to another venue: match `relocated_to` to a venue, then a shared act. It links when it resolves. It
fails when the house is not a source, which ADR-030 lists as common.

**Keep the model and declare it.** The row names the house, and the page sends readers to the venue. Costs nothing, and fixes nothing.

## Decision

**Store the new date as the note states it.** `relocatedOn` in `Relocation.kt` reads the first date after "auf" that is not the row's own date. It fills
`relocated_on` on a `RELOCATED` row only. A `POSTPONED` row needs no column, because #3093 links its new date's row at read time.

**A `RELOCATED` row with no house and a new date is `POSTPONED`.** `resolveDatedRelocation` applies it after `resolveRelocation`. #3093 then links the new
date at the same venue. The row on the new date names its own date, and `resolvePostponement` stores it `SCHEDULED`.

**The page says both.** "Moved to Hole 44, 13 April 2027" and "Verlegt: Hole 44, 13. April 2027" where the house and the date are known. A row with the
house only keeps "Moved to Hole 44".

What settled it: the decision keeps ADR-030's rule and fixes both rows. Linking stays possible later, and nothing here stands in its way.

## Consequences

- One nullable column, `relocated_on`, on `event`. `relocatedOn` on both entities, on the BFF's list item and detail response, and in `schema.d.ts`. A message
  key `events.status.movedToOn` in both languages.
- `EventContentStamp` hashes the column. V178 sets every `content_hash` to NULL, so the next import stores the new hash and keeps `content_changed_at`.
- A pinned status keeps its `relocated_on`, as it keeps its `relocated_to`.
- Both rows change on their next import, with no re-seed. A row that left every listing keeps what it had.
- A `RELOCATED` row with no house becomes `POSTPONED` when any note on it holds "auf" and a date. A note that uses that phrase for something else moves the row
  too. The two-digit year reads as this century's.
- The German text writes "ins" before every house. That is correct for a neuter name such as Hole 44, and wrong for a feminine one.
- The row on the new date at another house stays unlinked. A later change may match `relocated_to` and `relocated_on` against that house's rows.

## References

- [#3578](https://github.com/enorm-labs/event-junkie/issues/3578) — the analysis and the three options
- [#3613](https://github.com/enorm-labs/event-junkie/issues/3613) — this change
- [#3093](https://github.com/enorm-labs/event-junkie/issues/3093) — the link from a postponed row to its new date
- [ADR-030](ADR-030_RELOCATED_IS_THE_ORIGIN.md) — `RELOCATED` marks the origin, and `relocated_to` names the destination
- `Relocation.kt` — `relocatedOn`, `resolveDatedRelocation`
- `docs/DATA_MODEL.md` — the column
