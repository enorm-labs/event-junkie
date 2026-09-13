# ADR-029: An event has an optional end, stored only when the source states one

## Status

**Accepted (2026-09-13) — the event row gains `end_date` and `end_time`, both nullable. A scraper stores them only
when the venue publishes them. The site lists an event until its end, and shows the end when it has one.**

**Does not supersede anything.** [ADR-003](ADR-003_ENTITY_DOMAIN_SEPARATION.md) decided how a scraped event reaches
the row, and this decision adds two fields to that path. [ADR-004](ADR-004_DEDICATED_DATABASE_SCHEMA.md) decided where
the table lives. Neither decided the shape of an event's time, which was one date and one start from the first
migration.

**Not implemented yet.** [#317](https://github.com/enorm-labs/event-junkie/issues/317) holds the checklist. The
migration and the model land first, the three scrapers that already parse an end second, and the read side third.

## Context

A party at Sisyphos opens on Friday night and closes on Monday morning. The site cannot say so. The row has
`event_date` and `start_time`, and nothing after the start.

What that does to a late or long event today:

- `EventSearchRepository.appendDateRange` lists an event while `e.event_date >= today`. The event leaves `/events`
  and the home feed at 00:00 of its second day.
- `EventService.today()` reads `event_date = today`. The Tonight feed never shows an event on its second night.
- `EventDetailView.vue` marks an event as past when `eventDate` is before today.
- `dropPastEvents` in the importer compares dates only. The next import does not bring the event back. This is
  [#299](https://github.com/enorm-labs/event-junkie/issues/299) for every club night that ends at 06:00.

Three scrapers already have the end in hand and discard it. Each records that as an accepted limitation of the model:

| Venue       | What the venue publishes                 | Where it is dropped                                  |
| ----------- | ---------------------------------------- | ---------------------------------------------------- |
| Kater       | `Sa. 01.08 22:00 — So. 02.08 10:00`      | `KaterOverviewPageScraper.parseSchedule`, start half |
| Heideglühen | `12 Uhr (bis Sonntag, 6 Uhr)`            | `HeidegluehenMonthPageScraper`, kept as description  |
| Club OST    | `11 p.m.` to `8 a.m.` on the detail page | `ClubOstDetailPageScraper`, "deliberately dropped"   |

Sisyphos has no importer. Its site sells tickets and lists no programme, so Resident Advisor is the route
(`docs/EVENT_DATA_SOURCES.md`). Its nights are the Friday-to-Monday case that started this.

### The constraints a candidate had to satisfy

**Most events have no end, and never will.** On staging on 2026-09-13, one event in ten had no start at all
([#1384](https://github.com/enorm-labs/event-junkie/issues/1384)). An end is rarer. The model must treat "no end" as
the normal state, not as missing data.

**The start is a date and a time.** `event_date DATE` and `start_time TIME` are separate columns, and every scraper,
DTO and test builds on that split. A candidate that changes the start changes 85 importers.

**A guess must look like a guess.** [#1384](https://github.com/enorm-labs/event-junkie/issues/1384) shows an assumed
start as `~23:00` with the words behind it. A second kind of guess on the same line would blur that mark.

**Exhibitions need a range without a time.** [#337](https://github.com/enorm-labs/event-junkie/issues/337) waits on a
way to say "on for three months". A candidate that only works for hours fails it.

## Candidate options

**`end_date` and `end_time`, both nullable.** Mirrors the start. Every existing shape stays. An end with a date and no
time is a run, which serves #337. Nothing is stored that the venue did not say.

**One `end_at TIMESTAMPTZ`.** A single fact is cleaner in isolation. Beside a `DATE` and a `TIME` it makes the row
half timestamp and half split, and a reader has to convert before comparing. Converting the start to match touches
every importer for no visible gain.

**A `duration`.** No source publishes one. A 90-minute film fits, a weekender does not, and a run of months does not
at all.

**Keep the heuristic only.** #299 proposes to keep a late start until 06:00 the next day. That fixes the club night
and nothing else. Three scrapers would go on deleting a fact they hold, and Sisyphos stays unrepresentable.

## Comparison

| Candidate               | Fits the split start | Runs (#337) | Stores only facts | Sisyphos | Cost               |
| ----------------------- | -------------------- | ----------- | ----------------- | -------- | ------------------ |
| `end_date` + `end_time` | yes                  | yes         | yes               | yes      | two columns        |
| `end_at TIMESTAMPTZ`    | no                   | yes         | yes               | yes      | two columns, mixed |
| `duration`              | yes                  | no          | no                | no       | one column         |
| heuristic only          | yes                  | no          | n/a               | no       | none               |

## Decision

**`end_date` and `end_time`, both nullable.** The reason that settled it is the first constraint. Most events have no
end and never will, and two nullable columns beside two existing ones make "no end" the plain default. Every other
candidate either invents a value or moves the start.

**The read side uses the end when it exists and the date when it does not.** An event is listed while
`COALESCE(end_date, event_date) >= today`. Tonight includes an event whose span covers today. "Past" starts after the
end. Sorting stays by `event_date` and the effective start, so a weekender sorts on its opening night.

**The page shows the end only when the venue stated it.** `Fri 22:00 – Mon 10:00` when the end is on another day,
`22:00 – 06:00` when it is the next morning, and the start alone otherwise. No guessed end, for the third constraint.

**#299's heuristic stays as the fallback.** A late start without an end keeps the event until the next morning.
That rule now lives beside the end columns, and it yields to a stated end.

## Consequences

- Two `TrackedField`s, so field coverage reports which sources publish an end. Change detection in
  `EventFieldMapping` compares them, so a venue that adds an end updates the row.
- Kater, Heideglühen and Club OST store what they parse. Their KDocs stop calling the end an accepted limitation.
  They need a `--full` re-seed to backfill.
- A running event needs a state of its own on card, row and detail. The label `Running since Fri` is a new string in
  `en` and `de`. It is also a new branch in the state that today chooses between past, sold out and free.
- The calendar gets an event that spans days and has to draw it once, on its opening day, or on every day. That is a
  frontend decision this document leaves open.
- `EXHIBITION` can now be a run. Whether it should be is still #337's decision.
- One more nullable pair that most rows leave empty. Every reader of the row has to remember the `COALESCE`.

## References

- [#317](https://github.com/enorm-labs/event-junkie/issues/317) — the decision issue, with the checklist
- [#299](https://github.com/enorm-labs/event-junkie/issues/299) — the late-night drop, which this makes a one-line comparison
- [#337](https://github.com/enorm-labs/event-junkie/issues/337) — exhibitions as runs, which this unblocks
- [#1384](https://github.com/enorm-labs/event-junkie/issues/1384) — the assumed start, and its `~` mark
- `docs/EVENT_DATA_SOURCES.md` — Sisyphos and the Resident Advisor route
