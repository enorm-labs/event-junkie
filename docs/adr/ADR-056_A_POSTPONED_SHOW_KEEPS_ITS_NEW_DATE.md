# ADR-056: A postponed show keeps the new date its note states

## Status

**Accepted (2026-11-01) — `relocated_on` holds the new date of a moved show: of house, of date, or both. A `POSTPONED` row stores the date its note
names. The BFF links the row on that date, and the page shows the date alone when no row matches.**

**Amends [ADR-051](ADR-051_A_MOVED_SHOW_KEEPS_ITS_NEW_DATE.md).** ADR-051 fills `relocated_on` on a `RELOCATED` row only. It says that a `POSTPONED` row needs
no column, because [#3093](https://github.com/enorm-labs/event-junkie/issues/3093) links the new date's row at read time. This decision replaces that sentence.
It does not change which end of a move is `RELOCATED` ([ADR-030](ADR-030_RELOCATED_IS_THE_ORIGIN.md)).

**Implemented with the decision.** [#4334](https://github.com/enorm-labs/event-junkie/issues/4334) is the change. The operator chose to reuse the column on
2026-11-01.

## Context

The 2026-10-31 plausibility report found six `POSTPONED` rows whose notes named the new date. Each showed a bare "Postponed".

- Die Wühlmäuse, "Alle Kassen, auch privat": "VERLEGT auf den 24.04.2027". Two later rows share the title.
- Die Wühlmäuse, Michael Hatzius, four nights: "Der 06.11.2026, 20 Uhr verschiebt sich auf den 05.11.2027", one sentence per night. Twelve later rows share
  the title.
- ufaFabrik, Sebastian Krumbiegel: "Verlegt auf den 02.04.2027." No row exists on that date, because the importer reads three months.

The #3093 link takes the one later `SCHEDULED` row at the venue that shares an act or the title. Two matches or none give no link. The importer read the
date and discarded it.

### The constraints a candidate had to satisfy

**ADR-030's rule holds.** The row keeps what the note says. A match against another row is a link at read time, never a stored fact.

**A guess between two rows is worse than no link.** #3093 refuses to choose between a resident act's nights. Only the note can choose.

## Candidate options

**Reuse `relocated_on`.** Fill it on a `POSTPONED` row from the same "auf <date>" match. No migration and no new API field, because `relocatedOn` is already on
the list item and the detail response.

**A new `postponed_on` column.** It holds the same fact as `relocated_on`. It costs a migration, an API field, a regenerated `schema.d.ts` and a fixture row.

**Widen the BFF heuristic alone.** It cannot choose between the two "Alle Kassen" rows without the date. It cannot show a date that has no row.

## Decision

**Reuse `relocated_on`.** The column means "the new date of a moved show", whatever moved. The two statuses never meet on one row, so one column is enough.

**A note that pairs this row's date with a new one wins.** The nearest date before "auf" must be the row's own date. Without a pair, the first date after the
row's own date counts.

**The BFF prefers the stated date.** With `relocated_on`, only a matching row on that date links. Two shows on that night link the first. No match on that
date gives no link, even when one row matches on another date.

**The page shows the date alone when nothing links.** The card and the pill say "Postponed to Fri, 2 Apr 2027". The When block says "New date: Fri, 2 Apr
2027" under the struck date.

What settled it: the date settles both the ambiguous and the empty case, and the column already exists.

## Consequences

- The `relocated_on` KDoc, the OpenAPI description and `docs/DATA_MODEL.md` say both statuses. `schema.d.ts` changes only in that description.
- A `POSTPONED` row gains the date on its next import. The content hash changes with it, so `content_changed_at` moves once for each such row.
- A note that uses "auf <date>" for something else gives a wrong date. ADR-051 accepts the same risk for a `RELOCATED` row.
- The stated date overrides the #3093 guess. A note with a wrong date loses a link that the guess would have made. On 2026-11-01, every production row
  with a link had the same date in its note.
- A pinned status keeps its `relocated_on`, as ADR-051 says.

## References

- [#4334](https://github.com/enorm-labs/event-junkie/issues/4334) — this change
- [#3093](https://github.com/enorm-labs/event-junkie/issues/3093) — the link from a postponed row to its new date
- [ADR-051](ADR-051_A_MOVED_SHOW_KEEPS_ITS_NEW_DATE.md) — `relocated_on` on a `RELOCATED` row
- `Relocation.kt` — `relocatedOn`
- `PostponementRepository.kt` — `newDate`
