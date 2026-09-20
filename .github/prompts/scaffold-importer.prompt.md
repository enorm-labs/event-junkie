# Scaffold a New Importer

Add a new venue event importer to `events-importer`, end to end: enum value, parser + importer classes, fixtures, tests, dev-seed wiring — following ADR-007
and the importers already there.

**Arguments**: `$ARGUMENTS` names the venue and its listing URL (`SO36 https://so36.com/programm/`). Ask if either is missing.

## Important

- `git --no-pager`.
- Read [ADR-007](../../docs/adr/ADR-007_WEB_SCRAPING_STRATEGY.md) in full first — architecture, selector strategy, scraping ethics. Read the venue's row in
  [EVENT_DATA_SOURCES.md](../../docs/EVENT_DATA_SOURCES.md) for platform and quirks; it is not a field mapping — analyse the live site and map it onto
  [DATA_MODEL.md](../../docs/DATA_MODEL.md) yourself.
- Copy the closest existing importer: **JSON / API source** — `scraper/festsaal/`, `scraper/neuezukunft/`, `scraper/madameclaude/` (`EventImporter` directly,
  `ApiClient`, one `*ApiScraper.kt`); **single listing page** — `scraper/privatclub/` (`EventImporter` directly); **list + detail** — `scraper/cassiopeia/`,
  `scraper/astra/` (`AbstractTwoPageWebsiteImporter`). New code looks like the code around it; the shared helpers in `scraper/` are used, not reimplemented.

## 1. Reconnaissance

1. **`robots.txt`**: honour any `Disallow` on the listing and detail paths; disallowed means stop and report.
2. **Look for a JSON / API source before committing to HTML** (ADR-007 selector priority 1): XHR/`fetch` calls in the Network tab, WordPress
   `/wp-json/wp/v2/<type>`, an RSS feed, `?format=json`, an embedded calendar widget's boot endpoint (Festsaal is Wagtail REST, Neue Zukunft an Elfsight
   widget, Madame Claude WordPress `event` with ACF). `<script type="application/ld+json">` `schema.org/MusicEvent` is still an HTML fetch, parsed as JSON-LD
   (Astra, Privatclub). **A clean JSON source wins.**
3. **Save the real listing** (and one or two detail pages for list+detail, including an edge case — cancelled, sold out, free, missing date) as the fixture:
   `curl -sSL -A 'EventJunkie/1.0 (+https://github.com/enorm-labs/event-junkie)' '<url>' -o events-importer/src/test/resources/scraper/<venue>/<venue>-overview.html`.
4. **Classify**: JSON / API → `ApiClient.fetchJson` + a pure JSON scraper; JSON-LD → `HtmlFetcher`, parse the structured data; server-rendered HTML → Jsoup;
   **JS-rendered with no API → stop and flag** (Playwright is not in the project, ADR-007 §3, and adding it is a separate decision).
5. **Page pattern**: single page, list+detail or paginated. **First page only** (ADR-007 § Pagination); a venue that truly needs more loops inside
   `importEvents()`, never a changed interface.

## 2. The `EventSource` value

In `scraper/EventSource.kt`, **one line of KDoc about the venue itself** — nothing about the site or the parsing, which lives on the importer. The name becomes
`source_type` and the `sourceId` prefix:

```kotlin
/** SO36 Berlin – the Oranienstraße club central to Berlin's punk and new-wave history. */
SO36,
```

## 3. The `scraper/<venue>/` package

- **`<Venue>OverviewPageScraper.kt`** — pure, no I/O; a Jsoup `Document` + base URL in (or the raw JSON `String`), `List<ScrapedEvent>` out.
- **`<Venue>DetailPageScraper.kt`** — list+detail only; `ScrapedEvent?` for one page.
- **`<Venue>WebsiteImporter.kt`** — the `@Component` that fetches and wires. JSON: inject `ApiClient`, `fetchJson(url)`, return
  `ImportResult.Success(events, null, null)` (most APIs send no validators; idempotent `sourceId` upserts do the work). Single-page HTML:
  `HtmlFetcher.fetch(url, etag, lastModified)`, `FetchResult.NotModified` → `ImportResult.NotModified`. List+detail: extend `AbstractTwoPageWebsiteImporter`,
  implement `scrapeOverview`, `scrapeDetail`, `fillGapsFromOverview` (only what the detail page cannot supply). `override val eventSource = EventSource.<VENUE>`.

**The importer's and scrapers' KDoc is the one home for the source** — platform, pages read and why, the traps, why a selector was chosen. Under 20 comment
lines on the importer, under 10 per scraper; **do not copy the boilerplate an existing scraper still carries** (purity, fixture setup, `@param document the
parsed document` — #393 deleted it from 106 files). A defect we could repair is an issue, not KDoc.

**What the source does not carry is a record** (#715). Every importer file ends with one, and the build fails without it:

```kotlin
val <VENUE>_LIMITATIONS =
    VenueLimitations(
        EventSource.<VENUE>,
        AcceptedLimitation(LimitedAspect.DOORS_TIME, "the site publishes only one time per night"),
    )
```

One `AcceptedLimitation` per withheld thing, the reason a property of the _site_, lowercase, one sentence, no full stop. A venue that publishes everything
declares `VenueLimitations(EventSource.<VENUE>)` — a statement, not an omission. `/data-quality-audit` reads these; a limitation left in prose is re-reported
every run.

## 4. Shared helpers, selectors, fields

`ScrapingExtensions.kt` (`textAt`, `attrAt`, `imgSrcAt`, `hrefAt`, `resolveUrl`), `DateParsingExtensions.kt` (`parseTime`, `parseIsoDate`, `parseIsoTime`),
`EventTypeMapping.kt` (`mapEventType`, `refineConcertVenueType`, `isFestivalTitle`), `ArtistNameMapping.kt` (`isPlaceholderName`, `isNonArtistName`,
`buildArtistList`, `extractSupportFromSubtitle`), `EventFieldMapping.kt` (`parseEventStatus`, `orderDoorsBeforeStart`, `cleanEventTitle`, `detectFree`). A
helper two venues need goes into the extension file and ADR-007's utility table, not the venue package.

Selectors in ADR-007's order: JSON-LD > semantic HTML5 (`article`, `time[datetime]`, headings) > ARIA > `data-*` > meaningful classes > **never** positional
(`div:nth-child(3)`, `.col-md-4`). Scope to the narrowest container; `:has()` for context.

`ScrapedEvent` (`scraper/ScrapedEvent.kt`): required `title`, `eventDate`, `sourceUrl`, `sourceId` = `"${EventSource.<VENUE>.sourceIdPrefix}$slug"` derived
from the canonical URL, never from mutable text. **Validate before returning**: skip a blank title or an unparseable date with a warning, and wrap per-event
parsing in try/catch so one malformed event does not abort the import.

## 5. Tests

Under `src/test/kotlin/de/norm/events/scraper/<venue>/`: **`<Venue>OverviewPageScraperTest`** (and `DetailPageScraperTest`) parse the fixture
(`Jsoup.parse(html, baseUrl)`, or the raw string) and assert the count, one fully populated event, and the edge cases. **`<Venue>WebsiteImporterTest`** mocks
`ApiClient` or `HtmlFetcher` with MockK (`coEvery { … }`) and asserts `ImportResult.Success` with the right events, validators propagated, `NotModified`
passed through, an empty page handled, `eventSource` matching. Template: `PrivatclubWebsiteImporterTest`. JUnit 5 + Kotest + MockK; a fixed `Clock` where the
scraper infers the year.

## 6. Register the source

Sources are runtime rows, not Flyway (ADR-007). In `http/importer/dev-seed.http`: a `POST /api/admin/venues` (with `district`) capturing the id, a
`POST /api/admin/event-sources` with `"sourceType": "<VENUE>"` and the listing `url`, a `POST /api/admin/event-sources/<slug>/import`; the venue in the
`Sources (alphabetical)` list at the top as `Name — URL`. The only comments in a block are the two naming the wiring. The import slug is derived from the
source **name** (`Astra Kulturhaus` → `astra-kulturhaus`).

## 7. Verify

```bash
./gradlew :events-importer:test --tests '*<Venue>*' --tests '*ModularityTests'
./gradlew :events-importer:ktlintCheck :events-importer:detekt :events-importer:detektMain
```

Then `/verify`, then [`/importer-smoke`](importer-smoke.prompt.md) against the live site — the per-host throttle applies; do not hammer the venue.

## Checklist

- [ ] `robots.txt` checked; JSON/API source looked for first; not a JS SPA without an API
- [ ] `EventSource` value with a one-line venue description
- [ ] `<venue>/` package: scraper(s) + `@Component` importer; shared helpers reused; semantic selectors
- [ ] `sourceId` stable and prefixed; events validated before return
- [ ] `<VENUE>_LIMITATIONS` at the foot of the importer, registered in `AcceptedLimitations.declarations`; `ACCEPTED_LIMITATIONS.md` regenerated
- [ ] Fixtures under `src/test/resources/scraper/<venue>/`; scraper + importer tests cover happy path, edge cases, NotModified, empty page
- [ ] `dev-seed.http` updated, list at the top refreshed
- [ ] `ktlintCheck`, detekt, `ModularityTests`, new tests green; `/verify` clean
