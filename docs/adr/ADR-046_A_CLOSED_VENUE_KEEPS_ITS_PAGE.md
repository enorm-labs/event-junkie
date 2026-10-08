# ADR-046: A venue that closes for good keeps its row and its page

## Status

**Accepted (2026-10-08) — a venue that closes for good gets a `closed_on` date, the last day it is open. The venue row, its page and its past events stay.
From the day after `closed_on`, the venue lists, the type and character filters, the search and the venues map leave it out. Its page says "Closed for good
in October 2026". Before that day, the page says "Open until …" and the venue stays in every list.**

Decided in [#2731](https://github.com/enorm-labs/event-junkie/issues/2731), option 2. **Implemented in #2731**, with Jonny Knüppel as the first venue
(`closed_on` 2026-10-31, `V119`).

**Supersedes the practice in #1788**, where a data migration (`V051`) deleted arkaoda, its source and its past event. That practice was never an ADR. A
"Lost venues" page and a closed-venues view on the map (option 3) are not part of this decision.

## Context

Venues close. arkaoda closed on 2026-08-30, and `V051` removed the venue, its source and its event from both clusters. Each removal of that kind loses the
venue's history for good, so the cost grows with each closure. Jonny Knüppel leaves its site after October 2026. Issue #2766 added about 120 venues that we know
but do not import. Issue #2812 plans the check that finds a closed venue among them.

The constraints:

- **A venue URL that worked must keep working.** Search engines and visitors hold links to venue pages, and the sitemap lists every venue.
- **A closed venue must not look open.** A visitor who picks a venue from a list or the map expects to find nights there.
- **A closure is a fact a person confirms.** A dead site is not a closed venue (#2812). Nothing automatic may set the date.
- **The data the page shows is already allowed.** The privacy notice keeps past events for as long as the calendar operates (LEGAL.md §7, #769). The
  licence of a description or an image does not depend on whether the venue still operates.

## Candidate options

1. **Delete the venue (the practice until now).** One migration per closure. The page becomes a 404 and the history is gone.
2. **Keep it as closed.** A `closed_on` column. The page stays and says the venue closed. The lists and the map leave it out by default.
3. **Option 2 plus a "Lost venues" page**, a map and a list of every closed venue we tracked.

## Decision

**Option 2.** The reason that settled it: it keeps every venue URL and every past event, at the cost of one nullable column and one filter. Option 3
builds on it later and needs no other decision.

The rules:

1. **`closed_on` is the last day the venue is open.** The venue counts as closed from the day after, in Berlin time. A closure announced in advance is
   entered at once, and the page names the last day until it passes.
2. **Only a person sets it**, through the admin API or a migration. No import and no automatic check writes it.
3. **The BFF venue list leaves a closed venue out by default.** `closed=true` lists only the closed ones. The list endpoint also feeds the search and the
   venues map, so they follow.
4. **The venue page and its past events stay.** The sitemap keeps the venue. The page replaces "Upcoming events" with "No more nights here".
5. **A closed venue keeps its event source until someone removes it.** An importer that still runs against a dead site fails visibly, which is the
   signal to remove the source.

## Consequences

- **Every venue PUT must carry `closedOn`.** The admin API has no PATCH, so a client that leaves the field out reopens the venue. `venue-images.py` copies
  every field the GET returns for this reason (#2891).
- **A reopened venue needs only `closedOn` set back to null.** No data comes back from a migration.
- **Past events of a closed venue stay reachable** through the venue page and the event archive. An artist can object to an event under LEGAL.md §7.3, as
  for any other event.
- **The fixture carries a closed venue**, and `FixtureTest` asserts that some fixture row sets `closed_on`.

## When to revisit

When the number of closed venues makes a "Lost venues" page worth building (option 3), or when a venue reopens at a new address. A move is not a closure.
It is an address change on the same row.
