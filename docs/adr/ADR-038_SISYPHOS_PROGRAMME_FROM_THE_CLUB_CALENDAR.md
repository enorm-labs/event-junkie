# ADR-038: The Sisyphos programme comes from the club's own calendar

## Status

**Accepted (2026-10-01) — the Sisyphos importer reads the club's calendar feed first. Each night in it becomes an event with its opening and its
closing. The ticket shop adds its price, its sold-out state and its product link. sisy.fan adds the weekend line-up, as ADR-036 decided.**

**Implemented in [#2302](https://github.com/enorm-labs/event-junkie/issues/2302).**

**Amends [ADR-036](ADR-036_SISYPHOS_WEEKENDS_FROM_SISY_FAN.md).** Its premise was that Sisyphos publishes no programme of its own. That premise is false.
The sisy.fan permission, the fetch window and the credit stay as ADR-036 decided them.

## Context

ADR-036 said that the only Sisyphos website is a Shopify shop. The shop sells tickets for about one night a month. So the importer stored the shop's nights
and, from Friday 22:00, the sisy.fan weekends.

On 2026-10-01 the site showed four Sisyphos events. Each had no start time, so the site showed the estimated party start of 23:00. A user saw
`SISY 54 - NICHTGEBURTSTAG` on the club's homepage, and the site did not show it.

The homepage `https://www.sisyphos-berlin.net/` has the sections "Upcoming" and "Following Events". Each section is an iframe from
`dashboard.sisyphos-berlin.net/kalender-media/kalender.php`. A script in the iframe loads `kalender-media/events.json`. A plain fetch of the homepage shows
none of this, so the first analysis missed it.

The feed is a JSON array. On 2026-10-01 it held 39 entries, from 2026-05-08 to 2027-01-04. Each entry has these fields:

- `title`, `subtitle` and `content`, which is an HTML text.
- `image`, on the club's own host. One past entry had a stock image from `images.unsplash.com`.
- `startRaw` and `endRaw`, the opening and the closing as UTC instants.
- `start` and `end`, the same values as display text without a year.
- `ticketLink`, the shop product of a ticketed night. Some entries link the shop root or a shop page instead.
- `isSpecial`, `timestamp` and `ticketEndRaw`.

The feed has no entry id. It keeps past entries. The server sends a strong `ETag` and a `Last-Modified`. The host has no `robots.txt`, so nothing is
disallowed.

### The constraints a candidate had to satisfy

- **One event per night.** The calendar, the shop and sisy.fan can describe the same night.
- **The sisy.fan conditions stay.** Credit, and a minimum of requests inside the window of ADR-036.
- **`AssociationSyncService` replaces a line-up with the line-up of the scrape.** A run without sisy.fan must not store a weekend again without its
  line-up.
- **The slug contains the date.** A merged night must keep one date.

## Candidate options

1. **The calendar as the primary site of the Sisyphos source.** The shop and sisy.fan add their fields to the calendar's nights.
1. **The calendar as a second source row** for the same venue.
1. **The times from each product page of the shop.** Each product page shows a box with the date and the time.
1. **Do nothing.**

## Decision

**Option 1.** It is the only option that shows every night with the club's own times.

- **One source row, not two.** The reasons of ADR-036 apply again. `EventUpsertService.resolveBySlug` refuses an event whose slug a row of another source
  holds. A shop run would also empty a merged line-up.
- **The `sourceId` is the opening date in Berlin**, `sisyphos:<yyyy-MM-dd>`. The calendar has no id, and the date is the only key that the three sites
  share. The shop night and the sisy.fan weekend use the same key.
- **The calendar's title and times win.** A shop night joins the calendar night whose `ticketLink` names the same product. If no link names it, it joins the
  calendar night on the same date. It gives the price, the sold-out state and the product link.
- **A sisy.fan weekend joins the night with its key, or the night that contains its first set.** It gives the line-up and the credit link. The night keeps
  its own times.
- **A shop night that the calendar does not list stays an event.** The calendar ends about three months ahead. The shop sells `generationS` nights a year
  ahead.
- **The feed's validators are not used.** A 304 on the calendar would skip the shop and sisy.fan too. Each run reads about 185 KB every two hours.
- **Every night is a party.** The calendar has no category. The market and the open day are parties too. The product owner decided this on #2302.

Option 3 was rejected. It adds about ten requests to each run. The box on a copied product page keeps the date of the original. `generationS 09.10.2027`
shows "05. August 2027".

## Consequences

### What this obliges

- **Every existing Sisyphos row changes its `sourceId`.** The stale cleanup removes the old rows from tomorrow on, and the next run inserts new ones. A row
  dated today keeps its slug and blocks its replacement. So deploy the change on a day with no Sisyphos event dated today.
- **A run that loses the calendar or the shop is incomplete.** The stale cleanup does not run, so it cannot remove the nights that the run did not read.
- **A run that does not read sisy.fan leaves out the nights dated today or earlier.** This applies to the calendar nights too.
- **`SISYPHOS_LIMITATIONS` changes.** The start time is a limitation only for a shop night that the calendar does not list.
- **`docs/EVENT_DATA_SOURCES.md` changes its Sisyphos row and the passage "A handful of tickets is not a programme".**

### What it does not do

- It does not change the sisy.fan window, the credit or the request count.
- It does not read the shop's product pages.
- It does not use `availability.json`, the stock feed beside the calendar. The shop feed already gives the sold-out state.

## When to revisit

- **If the calendar gets an entry id.** Then the `sourceId` can use it, and two nights on one day become possible.
- **If the club asks us to stop.** Its wish not to be listed wins.
- **If the feed moves or changes its shape.** The run then falls back to the shop and sisy.fan, and a `WARN` says so.

## References

- [#2302](https://github.com/enorm-labs/event-junkie/issues/2302) — the finding and the plan
- [ADR-036](ADR-036_SISYPHOS_WEEKENDS_FROM_SISY_FAN.md) — sisy.fan, its permission and its window
- [ADR-007](ADR-007_WEB_SCRAPING_STRATEGY.md) — how a source is fetched and parsed
- `events-importer/src/main/kotlin/de/norm/events/scraper/sisyphos/`
