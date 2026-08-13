# ADR-007: Web Scraping Strategy

## Status

Accepted

## Context

The application needs to import music event data from ~40+ Berlin venue and promoter websites (see [EVENT_DATA_SOURCES.md](../EVENT_DATA_SOURCES.md)). These
websites vary widely in technology:

- **~80% are static/server-rendered HTML** — WordPress, PHP, Laravel, hand-coded HTML (e.g. Privatclub, Madame Claude, Supamolly, Junction Bar, Roadrunner's
  Paradise).
- **~15% are JavaScript-rendered SPAs** — Angular (Fluxbau), Nuxt.js (Festsaal Kreuzberg), Wix (Loge), or sites behind cookie walls (SO36).
- **A few offer structured feeds** — Supamolly has an RSS feed; Astra exposes `schema.org MusicEvent`
  JSON-LD markup.

Key requirements:

1. Non-blocking integration with the existing coroutine/WebFlux stack.
2. Reliable parsing of messy, real-world HTML (retro sites, malformed markup).
3. Conditional requests (ETag / Last-Modified) to avoid unnecessary re-scraping.
4. Extensible design — adding a new venue scraper should be straightforward.

## Decision

Use a **layered scraping strategy** with three tools, each covering a different tier of complexity:

### 1. Spring WebClient — HTTP Fetching (already in the project)

`spring-boot-starter-webclient` is already a dependency. Use it as the HTTP client for all scraping requests instead of Jsoup's built-in `Jsoup.connect()`. This
keeps HTTP fetching reactive and integrates naturally with the coroutine stack via `awaitBody<String>()`.

WebClient also supports conditional requests out of the box — send `If-None-Match` (ETag) and
`If-Modified-Since` headers to detect whether a page has changed before downloading the full body.

### 2. Jsoup — HTML Parsing (new dependency)

**`org.jsoup:jsoup`** is the de-facto standard HTML parser on the JVM. It provides:

- A CSS selector API for extracting elements (similar to jQuery / `document.querySelector`).
- Robust handling of malformed HTML — critical for retro/hand-coded sites.
- `schema.org` JSON-LD extraction for sites like Astra that embed structured data.

Jsoup is used **only for parsing** (not HTTP fetching). Since `Jsoup.parse()` is a CPU-bound blocking call, it is wrapped in `withContext(Dispatchers.IO)` to
avoid blocking the coroutine event loop.

This covers ~80% of the target venues.

### 3. Playwright (future — not added yet)

For the ~5 venues that require JavaScript execution (Angular SPAs, cookie walls, JS-rendered content), **Playwright for Java**
(`com.microsoft.playwright:playwright`) is the recommended future addition. It provides a modern headless browser API with Chromium/Firefox/WebKit support.

Playwright is **not added in this initial implementation** to keep the footprint minimal. It will be introduced when the first JS-rendered venue is targeted for
import.

### Prefer a JSON / API Source over HTML

Before writing any HTML scraper, **check whether the venue exposes its events as structured JSON** — either a public/internal REST or GraphQL API, or an
embedded third-party calendar widget with its own data endpoint. A structured feed is the single most stable source
(see [Selector Strategy](#selector-strategy--prefer-semantic-selectors), priority 1): it is an explicit machine-readable contract that survives site redesigns,
needs no CSS selectors, and often requires no headless browser even for otherwise JS-rendered SPAs. Many "JS-only"
venues turn out to be a thin front-end over a JSON API discoverable in the browser Network tab.

Two implemented importers already take this path and skip HTML entirely:

- **Festsaal Kreuzberg** — a Nuxt.js SPA renders nothing server-side, but it is backed by a **Wagtail headless CMS** whose public JSON REST API returns every
  upcoming event as clean structured data.
- **Neue Zukunft** — a static landing page whose concert programme lives only in an embedded **Elfsight
  "Event Calendar" widget**; the widget's public JSON boot API (`core.service.elfsight.com/p/boot/?w=<id>`)
  returns every event, no client-side rendering or OCR of the PDF flyer required.

JSON/API importers use **`ApiClient`** (below) instead of `HtmlFetcher`, but are otherwise identical:
they implement `EventImporter` directly and delegate parsing to a pure `*OverviewPageScraper` that takes the raw JSON string (rather than a Jsoup `Document`)
and returns `List<ScrapedEvent>`. Keeping the parser I/O-free preserves snapshot-based testability exactly as for HTML scrapers.

**`ApiClient` reuses the reactive `WebClient`, not Spring's blocking `RestClient`.** `RestClient` (the synchronous client from
the [Spring REST clients](https://docs.spring.io/spring-framework/reference/integration/rest-clients.html)
docs) was considered because its imperative `.body(T::class)` API is ergonomic, but it is **blocking** — dropping it into a coroutine importer would stall the
WebFlux event loop and violate the non-blocking stack mandated by [ADR-001](ADR-001_REACTIVE_STACK.md). `WebClient` integrates natively with coroutines via
`awaitBody()`, so it stays the client for both HTML and JSON. `ApiClient` returns the raw body as a
`String` (parsing happens in the pure scraper) rather than deserializing in the fetch layer, which keeps it decoupled from the global WebClient codec
configuration and lets each scraper use its own Jackson mapper.

Only when there is genuinely **no** usable structured source does an HTML scraper (Jsoup, priority 2+) apply.

### Architecture

A new `scraper` Spring Modulith module (`de.norm.events.scraper`) encapsulates all scraping infrastructure:

```mermaid
classDiagram
    direction TB

    class EventSourceEnum["«enum» EventSource"] {
        VENUE_X
        VENUE_Y
    }

    class EventImporterInterface["«interface» EventImporter"] {
        +eventSource: EventSource
        +importEvents(url, etag?, lastModified?): ImportResult
    }

    class ImportResult["«sealed» ImportResult"] {
        NotModified
        Success(events, etag?, lastModified?)
    }

    class ConcreteWebsiteImporter["VenueXWebsiteImporter"] {
        +eventSource = VENUE_X
        +importEvents(url, etag?, lastModified?): ImportResult
    }

    class ConcreteOverviewScraper["VenueXOverviewPageScraper"] {
        +scrape(document: Document, sourceUrl: String): List~ScrapedEvent~
    }

    class ConcreteDetailScraper["VenueXDetailPageScraper"] {
        +scrape(document: Document, sourceUrl: String): ScrapedEvent?
    }

    class PerHostThrottlingFilter["«ExchangeFilterFunction» PerHostThrottlingFilter"] {
        -politeDelayMillis: Long
        -hostThrottles: ConcurrentHashMap
        +filter(request, next): Mono~ClientResponse~
    }

    class HtmlFetcher {
        +fetch(url, etag?, lastModified?): FetchResult
    }

    class EventImportService {
        -importersBySource: Map~EventSource, EventImporter~
        +importAll(): List~ImportResultResponse~
        +importBySlug(slug): ImportResultResponse
        -importFromSource(source): ImportResultResponse
        -claimForImport/markSuccess/markFailed/markMisconfigured()
    }

    class EventUpsertService {
        +upsertAndCleanup(events, venueId, sourceId): Int
        -upsertEvents(events, venueId, sourceId): List
        -deduplicateScrapedEvents(events): List
        -removeStaleEvents(events, sourceId)
        -partitionByChanged(entities, existing): Pair
    }

    class AssociationSyncService {
        +resolveAndSyncAssociations(saved, scraped)
        -resolveAllArtists(events): Map
        -syncArtistAssociations(saved, scraped, cache)
        -resolveAllPromoters(events): Map
        -syncPromoterAssociations(saved, scraped, cache)
        -resolveOrCreate(name, cache, save, findBySlug): T
    }

    class EventSourceService {
        +findAll(pageable): Flow~EventSourceResponse~
        +findBySlug(slug): EventSourceResponse
        +create(request): EventSourceResponse
        +update(slug, request): EventSourceResponse
        +retry(slug): EventSourceResponse
        +retryAll(): Int
        +delete(slug)
    }

    class ScheduledImportService {
        +tick()
        -importDueSources()
        -resetStuckSources()
    }

    class EventSourceEntity {
        +sourceType: String
        +venueId: Long
        +url: String
        +etag: String?
        +lastModified: String?
        +status: String
        ...
    }

    class EventSourceController {
        REST: /api/admin/event-sources
    }

    EventImporterInterface <|.. ConcreteWebsiteImporter: implements
    EventImporterInterface --> EventSourceEnum: returns
    EventImporterInterface --> ImportResult: returns
    ConcreteWebsiteImporter --> HtmlFetcher: fetches HTML
    HtmlFetcher --> PerHostThrottlingFilter: WebClient filter
    ConcreteWebsiteImporter --> ConcreteOverviewScraper: parses overview
    ConcreteWebsiteImporter --> ConcreteDetailScraper: parses details
    EventImportService --> EventImporterInterface: dispatches by EventSource
    EventImportService --> EventSourceEntity: reads config & updates status
    EventImportService --> EventUpsertService: delegates persistence
    EventUpsertService --> AssociationSyncService: delegates associations
    EventSourceService --> EventSourceEntity: CRUD
    EventSourceController --> EventImportService: triggers imports (async, via ImportJobLauncher)
    EventSourceController --> EventSourceService: manages sources
    ScheduledImportService --> EventImportService: triggers imports
    ScheduledImportService --> EventSourceEntity: finds due sources
    style EventSourceEnum fill: #ffa, stroke: #333
    style EventImporterInterface fill: #f9f, stroke: #333
    style ImportResult fill: #f9f, stroke: #333
    style EventImportService fill: #bbf, stroke: #333
    style EventUpsertService fill: #bbf, stroke: #333
    style AssociationSyncService fill: #bbf, stroke: #333
    style ConcreteWebsiteImporter fill: #bfb, stroke: #333
    style ConcreteOverviewScraper fill: #bfb, stroke: #333
    style ConcreteDetailScraper fill: #bfb, stroke: #333
    style PerHostThrottlingFilter fill: #fdb, stroke: #333
```

Key components:

- **`EventSource`** — enum of known import sources (e.g. `CASSIOPEIA`), providing compile-time safe dispatch instead of fragile string-based keys.
- **`EventImporter`** — interface that each venue-specific importer implements. Contains an
  `eventSource` property for dispatch and a `suspend fun importEvents(url, etag?, lastModified?)`
  method returning an `ImportResult` (either `NotModified` or `Success` with scraped events). Each importer owns all HTTP fetching for its venue (overview +
  detail pages).
- **`*OverviewPageScraper` / `*DetailPageScraper`** — pure HTML parsers (no I/O) that operate on pre-fetched Jsoup Documents. Separated from importers for
  testability.
- **Shared scraper utilities** — three focused extension files in `de.norm.events.scraper`, shared across all venue scrapers. `ScrapingExtensions.kt` provides
  Jsoup HTML element extraction helpers and URL resolution. `DateParsingExtensions.kt` covers time/date parsing for standalone `HH:mm`
  strings and ISO 8601 datetime strings from JSON-LD. `EventTypeMapping.kt`, `ArtistNameMapping.kt`, and
  `EventFieldMapping.kt` handle domain-level mapping of scraped text to model constants (event-type classification, placeholder/non-artist detection, artist
  lists, status/title/free-entry fields). See [Shared Scraping Utilities](#shared-scraping-utilities) below. New venue scrapers should use these utilities to
  avoid reinventing boilerplate and ensure consistent handling of blank/missing values.
- **`HtmlFetcher`** — WebClient wrapper for **HTML** venues handling conditional requests (ETag / Last-Modified) and returning either the parsed HTML document
  or a "not modified" signal. Per-host politeness throttling is applied transparently via a `PerHostThrottlingFilter` registered as a WebClient
  `ExchangeFilterFunction` (see [Per-Host Politeness Throttling](#per-host-politeness-throttling) below). Response bodies are handed to Jsoup as **bytes**, not
  as a decoded `String`: a retro host that answers `Content-Type: text/html` with no `charset` parameter while serving a Latin-1 page would otherwise be decoded
  with Spring's UTF-8 fallback, destroying every umlaut before a scraper sees it. Passing the raw bytes lets Jsoup run its standard detection chain (BOM → HTTP
  `charset` → `<meta charset>` → UTF-8), so a page's declared encoding wins wherever it is stated.
- **`ApiClient`** — the JSON/API counterpart to `HtmlFetcher` (see
  [Prefer a JSON / API Source over HTML](#prefer-a-json--api-source-over-html)). `fetchJson(url)` returns the raw response body verbatim for venues whose events
  come from a REST/GraphQL API or a calendar-widget boot endpoint. Both fetchers inject the **same** shared `WebClient` bean (`ScraperHttpClientConfig`), so
  they share one `PerHostThrottlingFilter` instance — HTML and API requests to the same host are throttled together, and JSON importers get the identifying
  User-Agent for free.
- **`ScraperHttpClientConfig`** — builds the single shared, throttled scraper `WebClient` bean (`SCRAPER_WEB_CLIENT`) injected by both `HtmlFetcher` and
  `ApiClient`.
- **`PerHostThrottlingFilter`** — WebClient `ExchangeFilterFunction` that enforces a configurable minimum delay between consecutive HTTP requests to the same
  host. Throttling is transparent to callers — scraper implementations do not need to manage delays themselves.
- **`EventImportService`** — orchestrator that loads event source configuration from the database, delegates to the correct `EventImporter` bean, wraps
  persistence in a transaction via
  `EventUpsertService`, and manages event source status transitions (RUNNING → SUCCESS/FAILED/MISCONFIGURED).
- **`EventUpsertService`** — handles the event persistence pipeline within a transactional boundary:
  deduplicates scraped events, upserts events (insert new / update existing by `sourceId`), and removes stale future events no longer listed on the source
  website. Delegates artist/promoter resolution and association syncing to `AssociationSyncService`.
- **`AssociationSyncService`** — resolves artists and promoters by slug (auto-creating unknown ones with concurrent-safe fallback) and synchronizes many-to-many
  join-table associations via a diff strategy (insert new, update changed role/billing order, delete stale).
- **`EventSourceService`** — CRUD service for managing event source configuration.
- **`event_source` table** — tracks per-venue import metadata: URL, ETag, Last-Modified, last import timestamp, event count, status, and error messages.

### Event Source Dispatch

The **`EventSource` enum** is the compile-time safe dispatch mechanism that links a database-configured event source to the correct `EventImporter` bean at
runtime:

1. Each `EventImporter` implementation declares an `eventSource` property returning an `EventSource`
   enum value (e.g. `EventSource.CASSIOPEIA`).
2. The `event_source` table has a `source_type` column that must match an `EventSource` enum name.
3. On startup, `EventImportService` indexes all `EventImporter` beans into a
   `Map<EventSource, EventImporter>` keyed by `EventImporter.eventSource`.
4. When an import runs, the service resolves the enum from the stored key and looks up
   `importersBySource[eventSourceEnum]` for O (1) dispatch to the correct importer. If no bean matches or the key is invalid, the source is marked as
   `MISCONFIGURED` — a permanent status that the scheduler skips entirely (no retry budget consumed), requiring manual intervention to fix.

This design decouples **what** to scrape (URL, schedule — stored in the database) from **how** to parse it (HTML selectors — implemented in code), so new venues
can be added by implementing a single
`EventImporter` class, adding an enum value, and seeding an `event_source` row.

The relationship between `event_source` rows and `EventSource` enum values is intentionally **many-to-one**: multiple event source rows can share the same
`source_type` (enum value). This enables two important scenarios:

1. **Same importer, different venues**: If two venues use identical website templates (e.g. both built on the same Webflow CMS theme), they can share a single
   `EventImporter` implementation and `EventSource` enum value. Each venue gets its own `event_source` row (with its own URL, schedule, and ETag) but reuses the
   same parsing logic.
2. **Same venue, multiple pages**: A single venue may expose events across multiple pages — e.g. Cassiopeia could have separate listings for `/club` (indoor
   concerts) and `/garden` (outdoor events). Each page gets its own `event_source` row with a distinct URL and independent change detection, but both rows
   reference `CASSIOPEIA` as their `source_type` and are handled by the same `CassiopeiaWebsiteImporter`.

Using the enum as a primary key for the `event_source` table was considered and rejected precisely because it would enforce a one-to-one constraint, preventing
both of these scenarios.

### Event Source Management

Event sources are managed across three layers, each handling a different kind of change:

| What changes                                       | Where                                                         | When                               |
| -------------------------------------------------- | ------------------------------------------------------------- | ---------------------------------- |
| HTML parsing logic (CSS selectors, data mapping)   | `EventImporter` class (code)                                  | Website structure changes → deploy |
| Source registration (URL, venue, event source key) | REST API (`POST /event-sources`) or Flyway migration          | New venue added                    |
| Operational config (enabled, interval, retries)    | REST API (`PATCH /event-sources/{slug}`)                      | Anytime, no deploy needed          |
| Source removal                                     | REST API (`DELETE /event-sources/{slug}`)                     | Anytime, no deploy needed          |
| Job control (trigger, retry, monitor)              | REST API (`POST /event-sources/import`, `GET /event-sources`) | Anytime, no deploy needed          |

**Structural changes require deployment**: a new venue needs an `EventImporter` class implementing the HTML parser. This is always a code change because every
venue's HTML structure is different.

**Source registration is runtime**: `EventSourceController` provides a `POST /event-sources` endpoint to create new event sources and a
`DELETE /event-sources/{slug}` endpoint to remove them. This avoids using Flyway for data seeding (Flyway is reserved for schema-only DDL changes). Sources can
also be seeded via the IntelliJ HTTP Client scripts in `http/event-sources.http` for local development.

**Operational changes are runtime**: `EventSourceController` also exposes endpoints to enable/disable sources, adjust import intervals, change retry limits,
trigger manual imports, and reset failed sources — all without redeployment.

### Single Entry URL with Internal Detail-Page Fetching

The `event_source` table stores a single `url` per source — the **entry point** (listing/overview page). This is intentional. Venue websites follow three
patterns:

1. **Single listing page (~70%)** — all event data on one page (e.g. Privatclub, Supamolly, Monarch). The single `url` covers everything.
2. **List + detail pages (~25%)** — summaries on a listing page, full data on individual event pages (e.g. Loge, Hole 44, Madame Claude). The scraper extracts
   detail URLs from the listing HTML and fetches them internally via `HtmlFetcher`.
3. **Paginated / multi-page listings (~5%)** — monthly program pages (e.g. Junction Bar:
   `/program/05_2026/05_26.html`). The scraper constructs page URLs from the entry point.

Adding a `detail_url_pattern` column or a `urls` array was considered and rejected:

- Detail URL patterns vary too much between venues to be usefully abstracted into a column.
- Multiple URLs complicate ETag/Last-Modified caching — which URL do you track for change detection?
- The listing page is the natural change-detection target: it changes when events are added or removed.

Instead, concrete `EventImporter` implementations own all HTTP fetching for their venue, using `HtmlFetcher` to fetch both overview and detail pages within
their `importEvents()` method. Parsing is delegated to dedicated `*OverviewPageScraper` and `*DetailPageScraper` classes that operate on parsed Jsoup Documents
without performing any I/O. This keeps the infrastructure layer simple (one URL, one ETag) while giving importers full flexibility.

### Pagination — First Page Only

Venue listing pages are sometimes paginated (e.g. Cassiopeia uses Finsweet CMS Load to lazy-load additional pages via JavaScript). **The scraper intentionally
imports only the first page of each listing.** Multi-page crawling was considered and rejected for several reasons:

1. **Most pagination is JS-driven**: The majority of paginated venue sites use client-side pagination (infinite scroll, "load more" buttons, Finsweet CMS Load).
   Fetching subsequent pages would require either reverse-engineering undocumented JavaScript APIs or adding a headless browser dependency (Playwright), both of
   which are disproportionate to the value gained.
2. **First page = most upcoming events**: Venue listings are typically sorted chronologically. The first page already contains the most relevant upcoming
   events — exactly the data users care about for event discovery. Far-future events add little practical value.
3. **Stale event cleanup is already pagination-safe**: `removeStaleEvents()` scopes cleanup to the date range of the current scrape (`today..maxScrapedDate`).
   Events beyond the scraped date range are left untouched, so not fetching page 2+ does not cause accidental deletions.
4. **Conditional requests only cover the entry page**: ETag and Last-Modified headers apply to the overview page URL. Subsequent pages would each need
   independent change tracking, significantly complicating the `event_source` metadata model for minimal benefit.
5. **Complexity cost**: Multi-page crawling introduces loop/termination logic, per-page error handling, rate-limiting between page requests, and partial-failure
   semantics — all for a marginal increase in event coverage.

If a specific venue later requires multi-page support (e.g. a site with server-side `?page=2` query parameter pagination), it can be implemented **inside that
venue's `*WebsiteImporter`** by looping over pages in `importEvents()` and concatenating results before returning `ImportResult.Success`. This keeps pagination
a per-importer concern without changing the `EventImporter` interface or the framework-level infrastructure.

### Shared Detail Pages — Fetch Once Per Distinct URL

The list+detail pattern above assumes a one-to-one mapping: one listing entry, one detail page. A venue that programmes **runs** rather than one-off nights
breaks that assumption. Bar jeder Vernunft's calendar lists one card per _performance date_, but every date of a production links to the same
`/programmuebersicht/<show>.html` page — at the time of writing 28 calendar cards resolve to 2 show pages.

`AbstractTwoPageWebsiteImporter` fetches a detail page per event, so it would re-request one page 20+ times per import. With
[per-host throttling](#per-host-politeness-throttling) that is not just wasteful but slow, and it is exactly the kind of load the
[best practices](#scraping-best-practices) say to keep off a venue's server. Such an importer therefore implements `EventImporter` directly and de-duplicates
the fetch itself — group the scraped events by their detail URL, fetch each distinct URL once, and apply the parsed result to every event that shares it.

Two consequences follow for the parser split:

1. **The detail scraper does not return a `ScrapedEvent`.** It parses _show-level_ data (genre, price range, blurb) with no date of its own, so it returns a
   small venue model (`BarJederVernunftShow`) that knows how to enrich an occurrence — the same modelling choice as `HavannaWeeklyNight` below.
2. **`sourceId` cannot be the URL slug**, which is shared by the whole run. It combines the date with the show slug (`bar_jeder_vernunft:<date>-<show-slug>`),
   keeping upserts idempotent per performance.

### Undated Recurring Programmes — Derived Occurrences

Most venues announce individual dated events. A few instead assert a **standing weekly programme**:
the same resident nights every week, described once, with no dates published anywhere. Havanna is the first such source — its `/events` page is a static teaser
linking to three undated pages (`/wednesday`, `/friday`, `/saturday`), each describing a night that simply runs every week.

Skipping these venues would leave a real, regularly-running programme out of the calendar entirely. So the importer **derives** the dates: the venue-specific
parser produces an undated recurrence model (`HavannaWeeklyNight`) and expands it into one `ScrapedEvent` per week over a rolling horizon (8 weeks). Three
constraints make this safe:

1. **Stable dated `sourceId`** (`havanna:<date>-<night-slug>`) — every run regenerates the same window, so upserts are idempotent and occurrences rolling out of
   it are removed by `removeStaleEvents()`, which already scopes cleanup to `today..maxScrapedDate`.
2. **No conditional requests** — a derived horizon must advance daily, but these pages never change. A 304 would freeze the window and the calendar would
   silently stop moving forward, so such importers fetch unconditionally and return `null` cache headers (same opt-out as AMT, for a different reason).
3. **Honour announced closures** — a page carrying a closure notice with a start date ("… AB DEM 01.07.2026 IN DER SOMMERPAUSE!") suppresses that night's
   occurrences from that date on. Without this, a derived schedule keeps asserting parties the venue has publicly called off. A notice with no parseable date is
   logged rather than acted on: treating a bare "Pause" as an open-ended shutdown would erase the venue from the calendar on a single ambiguous word.

Derived occurrences are a deliberate, bounded inference — never applied to a venue that publishes dates, and never extended past the horizon or across a page's
announced break.

### Per-Host Politeness Throttling

Venue websites are typically hosted on modest infrastructure (shared WordPress hosting, small VPS). The scraper must avoid overwhelming them with rapid
consecutive requests, especially when fetching many detail pages for a single venue.

**`PerHostThrottlingFilter`** is a WebClient `ExchangeFilterFunction` registered on `HtmlFetcher`'s WebClient instance. It enforces a minimum delay
(`ScraperProperties.politeDelayMillis`, default:
200ms) between consecutive HTTP requests to the **same host**, while allowing requests to different hosts to proceed concurrently.

```
Cassiopeia detail pages:  ──[200ms]──▶ req1 ──[200ms]──▶ req2 ──[200ms]──▶ req3
Loge detail pages:        ──▶ req1 ──[200ms]──▶ req2 ──[200ms]──▶ req3
                          ↑ different hosts proceed independently
```

**How it works:**

1. Each host gets a lazily-created throttle entry (`ConcurrentHashMap<String, HostThrottle>`)
   containing a coroutine `Mutex` and a `TimeSource.Monotonic.ValueTimeMark`.
2. Before each request, the filter acquires the per-host mutex, checks elapsed time since the last request to that host, and suspends via `delay()` if the
   minimum interval has not elapsed.
3. The `filter()` method uses `kotlinx.coroutines.reactor.mono {}` to bridge between the reactive
   `ExchangeFilterFunction` contract and coroutine-based `Mutex`/`delay`, then chains into
   `next.exchange(request)` which stays fully reactive.

**Why an `ExchangeFilterFunction`:**

- **Transparent to scrapers**: `EventImporter` implementations do not need to manage delays themselves — any HTTP request through `HtmlFetcher` is automatically
  throttled. New scrapers get rate-limiting for free.
- **Idiomatic Spring**: `ExchangeFilterFunction` is the standard WebClient extension point for cross-cutting HTTP client concerns (logging, auth, retry,
  throttling). Keeping throttling in this layer follows the same pattern.
- **Single responsibility**: `HtmlFetcher` remains focused on HTTP fetching and HTML parsing. Throttling is a separate concern handled at the HTTP client layer.

**Alternatives considered:**

| Approach                           | Verdict                                                                                                                                                                                           |
| ---------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Per-scraper `delay()` calls        | Requires every `EventImporter` to remember adding delays. Easy to forget, no enforcement. Rejected.                                                                                               |
| Resilience4j `RateLimiter`         | Standard library, but semantics ("N permits per time window") don't naturally map to "minimum delay between requests." Would still need per-host instances + registry. Adds dependency. Rejected. |
| Guava `RateLimiter`                | Blocking API — doesn't fit the reactive/coroutine stack without `withContext(Dispatchers.IO)`. Rejected.                                                                                          |
| Global throttle (all hosts)        | Too conservative — serialises all requests even to different servers. Slows down parallel imports of independent venues. Rejected.                                                                |
| Reactor Netty `ConnectionProvider` | Only limits concurrent connections, does not add delays between sequential requests. Rejected.                                                                                                    |

### Change Detection

Each import source row stores the last ETag and Last-Modified values. Before scraping, the fetcher sends conditional headers:

- If the server responds with **304 Not Modified** → skip import, no work done.
- If the server responds with **200 OK** → parse the new HTML and upsert events via `sourceId`.

This minimizes unnecessary network traffic and database writes.

### Shared Scraping Utilities

Three focused extension files in the `de.norm.events.scraper` package provide reusable utilities shared across all venue scrapers. Each file has a single
cohesive responsibility, keeping the codebase organized as the number of utilities grows with new venue scrapers.

**`ScrapingExtensions.kt`** — Jsoup HTML element extraction helpers and URL resolution:

| Extension / Function                            | Purpose                                                               |
| ----------------------------------------------- | --------------------------------------------------------------------- |
| `Element.textAt(cssQuery)`                      | Select first match → `.text()` → trim → null if blank                 |
| `Element.attrAt(cssQuery, attr)`                | Select first match → attribute value → null if blank                  |
| `Element.imgSrcAt(cssQuery)`                    | Select `<img>` → `src` attribute → null if not an absolute HTTP URL   |
| `Element.hrefAt(cssQuery)`                      | Select `<a>` → `href` attribute → null if not an absolute HTTP URL    |
| `Element.hasVisibleWebflowFlag(cssQuery, text)` | Webflow `w-condition-invisible` visibility check + text content match |
| `resolveUrl(baseUrl, href)`                     | Resolve relative URLs against a base URL, pass-through absolute URLs  |
| `extractEventSlug(url, prefix)`                 | Strip a detail-URL path prefix down to the event's stable slug        |
| `ISO_DATE_LENGTH`                               | Length of the `YYYY-MM-DD` prefix some platforms bake into that slug  |

**`DateParsingExtensions.kt`** — date and time parsing for the two common formats on venue websites:

| Extension / Function                            | Purpose                                                                           |
| ----------------------------------------------- | --------------------------------------------------------------------------------- |
| `HH_MM_FORMATTER`                               | Shared `DateTimeFormatter` for 24-hour time (`HH:mm`)                             |
| `parseTime(text, formatter = HH_MM_FORMATTER)`  | Null-safe `LocalTime` parsing — returns null instead of throwing                  |
| `parseIsoDate(dateTimeStr)`                     | Extract date from ISO 8601 datetime (e.g. `"2026-05-16T20:00"`)                   |
| `parseIsoTime(dateTimeStr)`                     | Extract time from ISO 8601 datetime, delegates to `parseTime`                     |
| `inferYearForWeekday(monthDay, weekday, clock)` | Pick the year for a year-less date using its weekday (retro single-page listings) |
| `parseGermanMonthAbbreviation(text)`            | German month abbreviation → `Month` (`Okt`, `Dez`, every `Mär`/`März` spelling)   |

**`EventTypeMapping.kt` / `ArtistNameMapping.kt` / `EventFieldMapping.kt`** — domain-level mapping of scraped text to model constants:

| Extension / Function                   | Purpose                                                                             |
| -------------------------------------- | ----------------------------------------------------------------------------------- |
| `mapGermanCategory(category)`          | Maps German category labels ("Konzert", "Party", "Sonstiges") to `EventType` values |
| `isPlaceholderName(name)`              | Detects placeholder artist names ("TBA", "N.N.") that should not be persisted       |
| `buildArtistList(title, supportNames)` | Constructs headliner + support artist list from the common title/subtitle pattern   |
| `parseEventStatus(statusText)`         | German/English status badge → `EventStatus` (sold-out stays a flag, not a status)   |
| `orderDoorsBeforeStart(doors, start)`  | Recovers a source's transposed doors/start labels by swapping them back             |
| `stripRelocationPrefix(title)`         | Strips a `"verlegt in(s) <venue> –"` note a venue prepends to a moved show's title  |

**`WixEventsWarmupData.kt`** — platform-level reader for venues on Wix with a Wix Events widget (currently Loge and MAXXIM). The widget renders client-side, but
Wix server-side-injects the full event data as strict JSON in a `<script type="application/json" id="wix-warmup-data">` block, so that payload is the source
(priority 1 below) rather than the rendered cards. Only the payload location and the two readers every Wix venue needs live here; the venue's field mapping
stays in its own scraper.

| Extension / Function                      | Purpose                                                                                        |
| ----------------------------------------- | ---------------------------------------------------------------------------------------------- |
| `WixEventsWarmupData.events(doc, source)` | Locates the `events.events` array under the global Wix Events appDefId and the per-page widget |
| `JsonNode.stringOrNull(field)`            | Trimmed string field → null when missing, JSON `null`, or blank                                |
| `parseWixSchedule(config)`                | UTC `startDate` + `timeZoneId` → Berlin-local `(LocalDate, LocalTime)`                         |

**Design rationale**: A declarative selector-map approach (mapping field names to CSS selectors) was considered but rejected because each scraped field has
different extraction logic — sibling traversal, regex extraction from `style` attributes, multi-element date assembly, visibility checks, fallback chains. A
selector map would only cover the simplest cases (~3 of ~10 fields per scraper), creating a split architecture that is harder to maintain than the current
consistent one-method-per-field pattern. Extension functions provide the right level of abstraction: they eliminate the repetitive
`selectFirst(...)?.text()?.trim()?.takeIf { ... }` chains while leaving complex field-specific logic in dedicated methods.

### Selector Strategy — Prefer Semantic Selectors

CSS selectors in `EventImporter` implementations are the most fragile part of the scraping pipeline:
when a venue redesigns their website, selectors break. To maximise robustness, scrapers must prefer **semantic selectors** — selectors that target the _meaning_
of elements rather than their visual presentation or DOM position. Semantic selectors survive redesigns far more often than layout-based ones because the
meaning of the content rarely changes even when styling does.

**Selector preference order** (most to least stable):

| Priority | Selector type                        | Example                                                        | Why stable                                              |
| -------- | ------------------------------------ | -------------------------------------------------------------- | ------------------------------------------------------- |
| 1        | JSON / API source or structured data | REST/GraphQL/widget API; `<script type="application/ld+json">` | Explicit machine-readable contract; rarely changed      |
| 2        | Semantic HTML5 elements              | `article`, `time[datetime]`, `h2`, `address`                   | Reflects content meaning, not layout                    |
| 3        | ARIA roles & landmarks               | `[role="listitem"]`, `[aria-label="Event date"]`               | Accessibility attributes tied to purpose, not design    |
| 4        | Data attributes                      | `[data-event-id]`, `[data-date]`                               | Often stable internal identifiers used by the site's JS |
| 5        | Meaningful CSS classes               | `.event-title`, `.event-date`                                  | Semantic class names tied to content, not styling       |
| 6        | Positional / presentational          | `div > div:nth-child(3) > span`                                | **Avoid** — breaks on any structural change             |

**Concrete guidelines for `EventImporter` implementations:**

1. **Structured data first**: Prefer a JSON source over rendered HTML wherever one exists (see
   [Prefer a JSON / API Source over HTML](#prefer-a-json--api-source-over-html)). A standalone REST/GraphQL or calendar-widget API (fetched via `ApiClient`,
   e.g. Festsaal, Neue Zukunft) is best; failing that,
   `schema.org/MusicEvent` JSON-LD or Microdata embedded in the page (e.g. Astra Kulturhaus) — select
   `script[type=application/ld+json]` with Jsoup and parse the JSON payload. Both are the most stable and self-documenting sources.
2. **Target semantic HTML elements**: Prefer `article`, `section`, `time[datetime]`, `h1`–`h6`,
   `a[href]` over generic `div`/`span`. For example, select `time[datetime]` to extract event dates rather than parsing text from a styled
   `<span class="small-text">`.
3. **Use `data-*` attributes when available**: Many modern sites decorate elements with
   `data-event-id`, `data-date`, etc. for their own JavaScript. These are more reliable than class names because they carry semantic meaning independent of
   styling.
4. **Prefer class names that describe content over appearance**: `.event-card` and `.artist-name`
   are better selector targets than `.col-md-4` or `.text-red`. Presentational classes change with CSS framework upgrades; content classes rarely do.
5. **Avoid deep positional selectors**: Selectors like `div.content > div:nth-child(2) > p:first-of-type`
   are extremely brittle. A single `<div>` wrapper added by a CMS update will break them.
6. **Scope selectors to the narrowest useful container**: Start with a broad semantic container (e.g. `article.event`) and select children relative to it. This
   isolates the scraper from changes elsewhere on the page (navigation, footer, ads).
7. **Use `:has()` for contextual matching**: Jsoup supports the `:has()` pseudo-class — e.g.
   `div:has(time[datetime])` selects only divs that contain a `<time>` element, combining structural and semantic targeting.

### Scraping Best Practices

The following operational best practices ensure the scraping pipeline is ethical, resilient, and maintainable at scale. Several of these are informed by common
web scraping pitfalls documented in industry literature (see References).

1. **Respect `robots.txt`**: Before adding a new venue scraper, check the venue's `robots.txt` for crawling rules. If the site disallows scraping of specific
   paths, honour those directives. This avoids legal issues and maintains good relationships with venue operators.

2. **Rate-limit requests — do not overload venue servers**: Venue websites are typically hosted on modest infrastructure (shared WordPress hosting, small VPS).
   The scraper must introduce delays between requests to avoid overwhelming them. This is enforced globally via `PerHostThrottlingFilter`
   — a WebClient `ExchangeFilterFunction` that introduces a configurable delay (default: 200ms)
   between consecutive requests to the same host (see
   [Per-Host Politeness Throttling](#per-host-politeness-throttling)). Combined with change detection (ETag / Last-Modified), this keeps the load on venue
   servers negligible. Scraper implementations do not need to manage delays themselves.

3. **Set a descriptive `User-Agent` header**: Configure WebClient to send a transparent, identifying
   `User-Agent` string (e.g. `EventJunkie/1.0 (+https://github.com/...)`) so that venue operators can identify the bot and reach out if needed. Do not
   masquerade as a browser unless a venue specifically blocks non-browser agents and explicit permission has been obtained.

4. **Handle pattern changes with regression tests**: Venue website redesigns are the most common cause of scraper breakage. Each `EventImporter` should have
   accompanying unit tests that parse sample HTML snapshots (stored as test resources) and assert the expected extracted data. These tests act as regression
   guards — when a venue changes their markup, the test fails immediately, pinpointing the broken scraper. Run these tests in CI on every build.

5. **Validate data quality**: Never blindly persist scraped data. Each `EventImporter.importEvents()` call should validate extracted fields (non-blank title,
   parseable date, valid URL). Log warnings for events that fail validation and exclude them from the import rather than inserting garbage data. Data integrity
   is critical downstream for the BFF and frontend.

6. **Use canonical URLs to avoid duplicate scraping**: Some venue sites expose multiple URLs for the same content (e.g. with/without trailing slash, query
   parameters, locale prefixes). Normalise URLs before deduplication checks. The existing `sourceId`-based upsert already prevents duplicate events, but
   canonical URL handling avoids wasting network requests on duplicate pages.

7. **Schedule scrapes during off-peak hours**: Berlin venue websites see most traffic in the evening (visitors checking tonight's events). Schedule imports
   during early-morning hours (e.g. 03:00–06:00 CET) to minimise load during peak visitor times. The per-source `import_interval_minutes` in the
   `event_source` table supports this — set import times via the scheduling mechanism (ADR-008).

8. **Be aware of honeypot traps**: Some sites embed invisible links (hidden via CSS `display: none`
   or matching the background colour) to detect bots. Scrapers should be cautious about following links discovered dynamically. The current design mitigates
   this because `EventImporter`
   implementations only parse known page structures rather than following arbitrary links.

9. **Use the scraped data responsibly**: Event data is scraped for aggregation purposes (showing users what's on in Berlin), not for republishing raw content.
   Respect venue copyright — store only the structured fields needed (title, date, artists, URL) and link back to the original source for full details.

### Alternatives Considered

| Library            | Verdict                                                                                                                                     |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------- |
| Selenium           | Outdated, flaky, slower than Playwright. Rejected.                                                                                          |
| HtmlUnit           | Incomplete JS engine, unreliable for modern SPAs. Rejected.                                                                                 |
| Skrape{it}         | Kotlin-native but small community, limited maintenance. Rejected.                                                                           |
| Scrapy / BS4       | Python ecosystem — wrong language. Rejected.                                                                                                |
| AI/LLM for parsing | Unreliable for consistent structured extraction. May be useful later for free-text fields (e.g. extracting artist names from descriptions). |

## Consequences

- **Positive**: Jsoup is mature, well-documented, and handles real-world HTML; WebClient integration keeps everything non-blocking; conditional requests reduce
  load on venue websites; first-page-only scraping keeps the pipeline simple while covering the most relevant upcoming events; the `EventImporter`
  `EventImporter` interface makes adding new scrapers a single-class addition; shared scraping utilities (`ScrapingExtensions.kt`, `DateParsingExtensions.kt`,
  `EventTypeMapping.kt`, `ArtistNameMapping.kt`,
  `EventFieldMapping.kt`) reduce boilerplate when implementing new scrapers — common patterns like text extraction, URL parsing, time/date parsing, category
  mapping, and artist list construction are available as reusable functions; per-host politeness throttling via `PerHostThrottlingFilter` is transparent to
  scrapers — new importers get rate-limiting for free without any manual delay management; import metadata in the database enables future scheduling dashboards
  (TODO item #2); semantic selector guidelines and regression tests on HTML snapshots reduce breakage when venues redesign; operational best practices
  (rate-limiting, transparent User-Agent, off-peak scheduling) keep the scraper ethical and sustainable.
- **Negative**: Jsoup's `parse()` is blocking (mitigated by `Dispatchers.IO`); each venue requires a hand-written scraper class since HTML structure varies;
  JS-rendered venues are not covered until Playwright is added; maintaining HTML snapshot test fixtures adds per-venue overhead but pays off in early breakage
  detection.
- The `event_source` table is owned by the importer (consistent with ADR-005: migrations owned by importer).

## References

- [Jsoup documentation](https://jsoup.org/)
- [Jsoup CSS selector syntax](https://jsoup.org/cookbook/extracting-data/selector-syntax) — full selector reference including `:has()`
- [Playwright for Java](https://playwright.dev/java/)
- [Spring WebClient reference](https://docs.spring.io/spring-framework/reference/web/webflux-webclient.html)
- [Web Scraping: Introduction, Best Practices & Caveats (Velotio)](https://medium.com/velotio-perspectives/web-scraping-introduction-best-practices-caveats-9cbf4acc8d0f) —
  industry best practices for ethical and resilient scraping
- [schema.org MusicEvent](https://schema.org/MusicEvent) — structured data vocabulary for music events
- [EVENT_DATA_SOURCES.md](../EVENT_DATA_SOURCES.md) — venue/promoter inventory, import status, and per-source scraping notes
