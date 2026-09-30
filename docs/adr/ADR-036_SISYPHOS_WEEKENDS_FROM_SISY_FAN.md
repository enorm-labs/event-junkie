# ADR-036: Sisyphos weekends come from the fan-run sisy.fan, with its developer's permission

## Status

**Accepted (2026-09-30) — the Sisyphos importer also reads the weekend programme from sisy.fan. It stores one event per weekend with the line-up, the
floor and the set times. Every event page that shows this data links to sisy.fan as the source. The Sisyphos source runs every two hours. It reads sisy.fan
only from Friday 22:00 to Sunday 04:00, Berlin time. A weekend that the ticket shop also sells is one event, not two.**

**Implemented in [#2187](https://github.com/enorm-labs/event-junkie/issues/2187) on 2026-09-30.**

**Does not supersede anything.** [ADR-007](ADR-007_WEB_SCRAPING_STRATEGY.md) decided how a page is fetched and parsed. It did not decide which kinds of
site may be a source. [ADR-008](ADR-008_IMPORT_JOB_SCHEDULING.md) decided that a source runs on a fixed interval. This ADR keeps the scheduler as it
is. The window is in the Sisyphos importer, not in the scheduler.

## Context

Sisyphos publishes no programme of its own. Its only website is a Shopify shop. The shop sells tickets for one series, `generationS`, about one night a month.
The club runs a programme every weekend. `docs/EVENT_DATA_SOURCES.md` imports Sisyphos anyway, by decision, and says that the count does not show the real
programme. `SISYPHOS_LIMITATIONS` records that a ticket names no artist, no time and no floor.

[#2002](https://github.com/enorm-labs/event-junkie/issues/2002) added set times to the model. It left Sisyphos out, because the only running order is on a
fan-run site. sisy.fan ("Sisyphos Berlin Timetable (Unofficial)") publishes one for every weekend. It gives the floor, the slot, the start and the end of each
set. [#2003](https://github.com/enorm-labs/event-junkie/issues/2003) asked whether to use it.

The first analysis on #2003 rejected a scraper, for two reasons:

- **Legal.** A hand-maintained weekly collection is plausibly a database under § 87a UrhG. Extraction of a substantial part of it every week is what § 87b
  forbids. The site names no operator, so nobody could give a licence. This is not legal advice.
- **Editorial.** The data is a second-hand transcription. Sisyphos keeps a small online presence on purpose.

On 2026-09-30 the developer of sisy.fan answered a direct question. The written answer is kept privately as the record. It says three things:

1. The data is collected by hand. DJs and club staff pass it on, or someone writes it down in the club. Photos are not allowed there.
2. We may crawl sisy.fan for the line-up if we name sisy.fan as the source.
3. The crawling must stay at a minimum. The line-up usually goes up on Friday night or on Saturday.

### The constraints a candidate had to satisfy

- **The two conditions of the permission**: credit, and a minimum of requests.
- **No new third-party request in a visitor's browser.** sisy.fan loads an analytics script (`app.analyzati.com`). An iframe or an embed would send the
  visitor's IP address to it, and that changes the privacy notice (AGENTS.md § Privacy & GDPR).
- **One event per night.** The ticket shop and sisy.fan describe the same `generationS` weekend.
- **ADR-008 has no time of day.** A source runs every `import_interval_minutes`, whatever the day.
- **`LEGAL.md` §7.2.** Art. 14 (2) (f) requires the privacy notice to name the source of personal data. Artist names are personal data (§7.3).

## Candidate options

1. **sisy.fan as a second site of the Sisyphos source.** The importer reads both sites. It merges them before it stores anything.
   sisy.fan supplies one event per weekend with its line-up and set times. A weekend the shop sells becomes one event.
1. **sisy.fan as a second source row** for the same venue, with the window in the scheduler.
1. **Set times on the ticketed nights only.** sisy.fan adds times to the `generationS` events and creates nothing. About three weekends in four have no event,
   so most fetches find nothing to attach to.
1. **A link only.** Each Sisyphos event links to sisy.fan and stores none of its data. Most weekends have no event to carry the link.
1. **Do nothing.** Sisyphos stays at one ticketed night a month, with no artists.

## Decision

**Option 1.** It is the only option that shows what the club does on most weekends. The permission removes the legal objection that rejected it first.

- **One source row, not two.** Option 2 fails on three points of the existing import path:
    - `EventUpsertService.resolveBySlug` refuses an event whose slug a row of another source holds. So one night from two sources is two rows or a
      refused row.
    - `AssociationSyncService` replaces an event's line-up with the line-up of the scrape. A shop run would empty a merged line-up.
    - A window in the scheduler silences the source for about 114 hours a week. The `ej-importer-stale` alert fires after 36 hours.
- **The window is in the importer.** So it binds every import path: the scheduler, a manual import with or without `force`, the import of all sources and a
  retry. ADR-008 does not change.
- **The editorial objection is accepted, not solved.** The data stays a transcription by fans. The credit link tells the visitor where it comes from. The
  product owner decided that a running order with its source named helps visitors more than no running order.
- **The fetch window is Friday 22:00 to Sunday 04:00, every two hours.** That is 16 requests a weekend at most. The window starts before the line-up usually goes up and
  ends early on Sunday morning. A change posted on Sunday morning is not seen, and that is the price of the minimum.
- **The credit is a plain link.** It carries no data from sisy.fan to the visitor and loads nothing from it.
- **The ticket shop keeps what it knows.** On a `generationS` weekend the event keeps the shop's title, price and ticket link. sisy.fan adds the line-up, the
  floors and the set times.

## Consequences

### What this obliges

- **The Sisyphos source runs every two hours, all week.** The shop is read 12 times a day instead of once. The interval of the source row changes on each
  cluster by hand, because the seed script does not update a row that exists.
- **A merge of two sites in one importer.** A shop night inside the date range of a sisy.fan weekend takes the line-up and the
  end. It keeps its own date, because the slug contains the date.
- **Outside the window the importer leaves out shop nights dated today or earlier.** Otherwise a shop run early on Sunday stores such a night without its
  line-up.
- **A new event column holds the credit link.** A merged night keeps the shop's page as its source page, so the credit cannot come from the source row.
- **The credit on the event page.** Structured data leaves out the performers of an event with this credit. The artist names on the event cards show
  without it. The product owner accepted that.
- **The privacy notice names sisy.fan as a source**, in both languages, with `LEGAL.md` §7 in the same change. sisy.fan is a source, not a processor.
  Nothing goes to it but the request.
- **`docs/EVENT_DATA_SOURCES.md` changes its Sisyphos row and the passage "A handful of tickets is not a programme".**
- **A weekend event appears on Friday night at the earliest.** Nobody can plan a weekend ahead with it. It helps on the night itself.
- **The accuracy is sisy.fan's.** A wrong set time on our page is a transcription error at the source. We cannot check it, and a correction reaches us
  only at the next fetch in the window.

### What it does not do

- It adds no other fan site. SisyDuck and `sisyphos.vercel.app` gave no permission.
- It does not make fan sites a kind of source in general. A new one needs its own permission and its own decision.
- It does not fetch outside the window, for a retry or for anything else.

## When to revisit

- **If the developer withdraws the permission or changes a condition.** Then disable the source at once and remove its line-ups.
- **If Sisyphos publishes its own programme or asks us to stop.** The club's own source wins, and its wish not to be listed wins too.
- **If sisy.fan stops updating** for several weekends. Then the source shows old nights as current.

## References

- [#2003](https://github.com/enorm-labs/event-junkie/issues/2003) — the question, the options and the developer's answer
- [#2002](https://github.com/enorm-labs/event-junkie/issues/2002) — set times in the model
- [ADR-007](ADR-007_WEB_SCRAPING_STRATEGY.md) — how a source is fetched and parsed
- [ADR-008](ADR-008_IMPORT_JOB_SCHEDULING.md) — the fixed-interval scheduler
- [`docs/EVENT_DATA_SOURCES.md`](../EVENT_DATA_SOURCES.md) — the Sisyphos row and its rule
- [`docs/LEGAL.md`](../LEGAL.md) §7.2 and §7.3
- [sisy.fan](https://sisy.fan/)
