# Event Junkie — Product Overview

> _"Can't get enough of Berlin."_

**A feature inventory: what Event Junkie does today, in present tense.**

## The short version

```sh
scripts/features-parity.sh   # fails when a route, a public API path, a public file or an e2e spec is not in § Surfaces
```

- A `feat` that adds a page, an endpoint or an e2e spec adds a row to [§ Surfaces](#surfaces) in the same pull request.
- A `feat` without a new surface still updates [§ What a visitor can do](#what-a-visitor-can-do). The check cannot see it.
- Do not copy counts into this file. Link to the document that owns the number.

This document does **not** argue the _why_. The [README](../README.md#background) and the site's About page do that.
The README's _What it does_ is the short form of the section below.

Elsewhere:

- which _kinds_ of event are in scope — [EVENT_SCOPE.md](EVENT_SCOPE.md)
- where the product goes next — [VISION_ROADMAP_IDEAS.md](VISION_ROADMAP_IDEAS.md)
- the backlog — [GitHub Issues](https://github.com/enorm-labs/event-junkie/issues)
- voice and visual direction — [BRANDING.md](BRANDING.md)

---

## In one line

Event Junkie is an **event discovery site for Berlin**, live at <https://event-junkie.de> as a public beta. It collects
events automatically from venue and promoter websites into one filterable feed. Each event links back to its source for
tickets and details.

Scope rule: **if a Berlin venue puts it on a stage in the evening, it is in scope.** [EVENT_SCOPE.md](EVENT_SCOPE.md)
lists what that includes and what it excludes.

## What a visitor can do

**Find events**

- See **Tonight** and **Upcoming** on the home page. A run longer than 7 days moves into an "Also running" block.
- Filter the events list, the calendar and the map with one filter bar. The filters are date range, time of night, event type,
  venue, venue type, district, genre, price range, free-only and exclude-sold-out.
- Use the **Tonight**, **This weekend** and **Next 7 days** shortcuts, or **On now** for what runs at this moment.
- Search from the header with `/` or Ctrl/Cmd+K. The search finds events, venues, artists and promoters as you type.
- The full results page also lists past events, latest first, in a last group.
- Every search ignores accents, umlaut spellings and spaces, and tolerates typos. `berghian` finds Berghain.
- Sort the events list by date, or by **newest added** to see the events we found most recently.
- Plan a month ahead in the **calendar**.
- Share or bookmark **a week** at `/week/2026-41`: each day's first 12 events, then a link to the rest.
  **This week in Berlin** on the home page and in the footer opens the current week.
- Find what is **near you** on the map. Pick a position, a 1, 2 or 5 km radius, and get the venues inside it,
  nearest first. The position stays in the browser.

**Read an event**

- See the date, doors, start and end in one **When** block, with prices and the source link.
- See the **running order and set times** of a club night, where the venue publishes them.
- Open an act on **Bandcamp or SoundCloud** from the line-up, where the artist has a link. Nothing loads from either site before a click.
- See the **spoken language** of comedy, readings and screenings, where the venue states it.
- See **upcoming events like this one**.
- **Share** the event, copy its link, or **add it to a calendar** as an `.ics` file or a Google Calendar link.
- Report wrong data by email, without a GitHub account.

**Read a venue, artist or promoter**

- Every venue, artist and promoter has its own page with everything coming up there. The pages link to each other.
- A venue shows its type, capacity, district, a map link and the genres and event types it programmes.
- A venue shows **character tags** that its own page states, for example _awareness team_ or _wheelchair accessible_.
- Filter venues by name, district, type, genre, event type and character tag. Each tag option shows how many venues
  it leaves.
- Sort venues and promoters by name or by **events in the next 30 days**.
- An artist page links to MusicBrainz or Discogs where a match exists.

**Everywhere**

- Read the site in **German or English**, in a light or dark theme, with posters or a compact list.
- An event with no flyer gets a drawn poster for its genre or type.
- Every filter lives in the URL, so a filtered view is a link you can share.
- Subscribe to the **RSS feed** of new events. A filtered feed URL is a saved search with no account.
- Subscribe a calendar app to the **next 90 days** of events, filtered the same way. New and cancelled events reach the calendar by themselves.
- The site loads nothing from a third party and does not track you. The map tiles are self-hosted.
- A venue can opt out on the _For venues_ page.

## Surfaces

The table lists every surface the parity check knows about. `scripts/features-parity.sh` reads the code spans in
the first column. It fails in two cases:

1. A route, a public API path, a public file or an e2e spec exists and has no row.
2. A row names a surface that does not exist.

| Surface                            | What it is                                                                  |
| ---------------------------------- | --------------------------------------------------------------------------- |
| `/`                                | Home page: Tonight and Upcoming                                             |
| `/events`                          | The events list and its filter bar                                          |
| `/events/:slug`                    | Event page: When block, line-up, set times, share, calendar, related events |
| `/calendar`                        | Month calendar with the same filters                                        |
| `/week`                            | Redirects to this week's page; linked from the home page and the footer     |
| `/week/:isoWeek`                   | One week day by day, up to 12 events a day, to share or bookmark            |
| `/map`                             | Events map, near me and On now                                              |
| `/venues`                          | Venues list and venues map, with character-tag filters                      |
| `/venues/:slug`                    | Venue page                                                                  |
| `/artists/:slug`                   | Artist page                                                                 |
| `/promoters`                       | Promoters list                                                              |
| `/promoters/:slug`                 | Promoter page                                                               |
| `/search`                          | All results for a header search, not indexed                                |
| `/about`                           | About page                                                                  |
| `/legal/imprint`                   | Imprint                                                                     |
| `/legal/privacy`                   | Privacy notice                                                              |
| `/legal/notices`                   | Open-source notices                                                         |
| `/legal/for-venues`                | The opt-out route for venues                                                |
| `/feed.xml`                        | RSS feed of newly imported events, with every events-list filter            |
| `/calendar.ics`                    | iCalendar subscription to the next 90 days, with every events-list filter   |
| `/robots.txt`                      | Allows search and answer crawlers, refuses AI training crawlers             |
| `/api/events`                      | Event search with the full filter set, paged and sorted                     |
| `/api/events/{slug}`               | One event                                                                   |
| `/api/events/{slug}/related`       | Upcoming events like this one                                               |
| `/api/events/today`                | Today's events                                                              |
| `/api/events/calendar`             | Events in a date range, for the calendar and the map                        |
| `/api/events/feed`                 | The RSS feed behind `/feed.xml`                                             |
| `/api/events/calendar.ics`         | The calendar subscription behind `/calendar.ics`                            |
| `/api/venues`                      | Venue list with its filters                                                 |
| `/api/venues/{slug}`               | One venue                                                                   |
| `/api/venues/feature-counts`       | How many venues each character tag leaves                                   |
| `/api/artists`                     | Artist list                                                                 |
| `/api/artists/{slug}`              | One artist                                                                  |
| `/api/promoters`                   | Promoter list                                                               |
| `/api/promoters/{slug}`            | One promoter                                                                |
| `/api/genres`                      | Genre tags and their families                                               |
| `/api/search`                      | The header search across all four kinds                                     |
| `/api/sitemaps/{kind}.xml`         | The detail sitemaps                                                         |
| `/api/images/{contentHash}/{file}` | Cached venue images                                                         |
| `/api/meta`                        | Version and commit of the running backend, for the footer                   |
| `home-feeds.spec.ts`               | e2e: Tonight and Upcoming                                                   |
| `events-filters.spec.ts`           | e2e: the filter bar                                                         |
| `filter-sheet.spec.ts`             | e2e: the filter bar's sheet on a phone                                      |
| `calendar.spec.ts`                 | e2e: the calendar                                                           |
| `map-near.spec.ts`                 | e2e: near me on the map                                                     |
| `venues.spec.ts`                   | e2e: venues list, filters and map                                           |
| `promoters.spec.ts`                | e2e: promoters list                                                         |
| `detail-routes.spec.ts`            | e2e: the four detail pages                                                  |
| `search.spec.ts`                   | e2e: header search                                                          |
| `share-calendar.spec.ts`           | e2e: share and add to calendar                                              |
| `card-poster.spec.ts`              | e2e: drawn posters                                                          |
| `compact-view.spec.ts`             | e2e: compact list                                                           |
| `dark-mode.spec.ts`                | e2e: theme                                                                  |
| `i18n.spec.ts`                     | e2e: German and English                                                     |
| `footer.spec.ts`                   | e2e: footer, feed and feedback links                                        |
| `legal.spec.ts`                    | e2e: legal pages                                                            |
| `seo.spec.ts`                      | e2e: head tags, sitemaps, structured data                                   |
| `a11y.spec.ts`                     | e2e quality check: accessibility, not a feature                             |
| `keyboard-flows.spec.ts`           | e2e quality check: main flows by keyboard alone, not a feature              |
| `layout-shift.spec.ts`             | e2e quality check: layout shift, not a feature                              |
| `page-title.spec.ts`               | e2e quality check: page titles, not a feature                               |
| `scroll-restoration.spec.ts`       | e2e quality check: scroll position, not a feature                           |
| `smoke.spec.ts`                    | e2e quality check: every page renders, not a feature                        |

## Behind the site

**Importer**

- Imports Berlin sources on a schedule. [EVENT_DATA_SOURCES.md](EVENT_DATA_SOURCES.md) has the per-venue inventory
  and the counts.
- Skips an unchanged page (ETag / Last-Modified), throttles per host and honours `Crawl-delay`.
- Removes duplicates and stale events.
- Detects free entry, sold out, cancelled and postponed, the spoken language and the room at a multi-room venue.
- Links artists with their role and billing order, promoters and genre tags. Artist lookups against MusicBrainz and
  Discogs run on their own schedule.
- Keeps a field that an operator edited by hand. The next import does not overwrite it.
- Records values it refuses in a data-quality worklist.
- An admin API creates, enables, triggers and retries sources. The local `events-admin` app is its UI.

**Search engines and crawlers**

- A sitemap lists every upcoming event, venue, artist and promoter.
- Each page has its own head tags, `hreflang` and canonical URL. Event and venue pages carry JSON-LD in the served
  HTML, so crawlers that run no JavaScript can read them.

**Engineering**

- A reactive **Kotlin + Spring Boot 4** backend (WebFlux, R2DBC, Flyway) in two services: the importer and the public
  read API. Both use **PostgreSQL**. The frontend is **Vue 3**.
- Production and staging run on Hetzner. Flux reconciles both clusters.
- Decisions are in [ADRs](adr/). Unit, integration and e2e tests cover the importer, the API and the frontend.

## Not there yet

Accounts, follows, saved searches with notifications and calendar subscriptions that stay in sync. These are the
roadmap — see [VISION_ROADMAP_IDEAS.md](VISION_ROADMAP_IDEAS.md).
