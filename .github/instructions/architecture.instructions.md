---
applyTo: "events-core/**,events-bff/**,events-importer/**"
paths:
    - "events-core/**"
    - "events-bff/**"
    - "events-importer/**"
---

# Backend Architecture Decisions

The decisions the three backend modules are built on. Each one has a failure mode attached — that is why it is written down rather than left to the code.

- **Reactive stack throughout**: WebFlux + R2DBC + Kotlin coroutines. Do NOT use blocking APIs (`spring-web`, JDBC) in request paths. Repositories extend
  `CoroutineCrudRepository` so all operations are suspending functions.
- **Spring Data R2DBC query derivation limitations**: Unlike Spring Data JPA, R2DBC has more limited query derivation. Derived `findBy*`, `countBy*`,
  `existsBy*`, and `deleteBy*` methods are supported. However, derived `updateBy*` methods are **not supported** — use `@Modifying` + `@Query` with raw SQL
  instead. Custom `@Query` SQL must include the schema prefix because raw queries bypass the `@Table` and `NamingStrategy` metadata — **interpolate the
  constant, never a literal**: `@Query("SELECT * FROM $EVENTS_SCHEMA.event_source …")`. A Kotlin `const val` is usable inside an annotation argument, which is
  what lets one source of truth reach even a `@Query` (#540). `SchemaConfigurationTest` fails the build on a literal.
  See [Spring Data R2DBC query methods reference](https://docs.spring.io/spring-data/relational/reference/r2dbc/query-methods.html).
- **Domain model** lives in `events-core` as plain Kotlin data classes (no Spring Data annotations). Tables: `venue`, `artist`, `promoter`, `event`,
  `event_artist` (join), `event_promoter` (join), `genre_tag`, `event_genre_tag` (join), `event_source` (import metadata). Events reference venues via FK;
  artists, promoters, and genre tags link to events through join tables.
  `Event.sourceId` enables idempotent imports (upsert semantics). `event_source` tracks per-venue import configuration and conditional-request headers (ETag,
  Last-Modified).
- **Spring Modulith** enforces module boundaries: each direct sub-package under `de.norm.events` is an application module. Run `ModularityTests` (present in all
  three modules) to verify structure.
- **Database schema**: All tables live in a dedicated `events` schema (not `public`). Both apps configure this via `spring.r2dbc.properties.schema: events`;
  importer also sets `spring.flyway.schemas: events`, which tells Flyway to auto-create the schema and set `search_path` before running migrations. A custom
  `NamingStrategy` bean in `R2dbcConfiguration` applies the schema globally to all derived query methods (`findBy*`, `save`, `delete`, etc.), so `@Table`
  annotations don't need to repeat it. **The name itself comes from `EVENTS_SCHEMA` in `events-core`, not from the property** — the two YAML declarations remain
  because only they can create the schema and set the connection's `search_path`, but they are checked against the constant rather than being its source, and
  the context fails to start on divergence (#540, ADR-004). Without this `NamingStrategy`, Spring Data R2DBC generates unqualified table references (e.g. `INSERT INTO "venue"`) that fail because
  the tables don't exist in the `public` schema. Raw `@Query` SQL must still include the schema prefix manually — as `$EVENTS_SCHEMA`, never as a literal — since custom
  queries bypass both `@Table` and
  `NamingStrategy` metadata.
- **Database migrations** live in `events-importer` only (Flyway). The BFF does not run migrations. Migration naming: `V001__description.sql`.
  **`V001__create_initial_schema.sql` is closed. Every schema change is now its own migration** — `V002`, `V003`, … — and editing an existing file is the change
  to refuse in review.

    The failure it prevents is worse than a missing column. Editing an applied migration is a `FlywayValidateException: Migration checksum mismatch`, so the
    importer's context does not start, the pod never goes Ready, and the HelmRelease's `remediateLastFailure: true` **rolls the release back** — presenting as
    "the deploy reverted", two layers from the cause.

    Migrations stay **unqualified**: Flyway sets `search_path` from `spring.flyway.schemas` before running them, so an unqualified migration follows the
    configuration and a qualified one pins itself to a schema the configuration no longer controls (ADR-004).

    **A `slug` literal in a migration is checked, because a wrong one fails open.** A guarded `UPDATE venue ... WHERE slug = '...'` with a misspelt slug updates
    no row, and Flyway still records the migration as applied — the row stays wrong and nothing says so. `MigrationSlugTest` slugifies every venue name in
    `http/importer/dev-seed.http` through `SlugGenerator` and asserts that every slug literal under `db/migration/` is in that set, naming the file and the line
    of any that is not. It runs in `./gradlew build`.

    **When a venue is renamed or removed, add its old slug to `RETIRED_VENUE_SLUGS` in that test, with the reason.** An older migration then keeps naming a
    venue that no longer exists, which is correct and is what the entry records. A second assertion deletes the entry again once it is stale, so the escape
    hatch cannot silently grow. Never reach for `@Disabled` here: the check exists because this class of defect is invisible without it (#987).

- **Docker Compose dev services**: `bootRun` auto-starts PostgreSQL via Spring Docker Compose support (`compose.yaml` at root).
- **SpringDoc OpenAPI** enabled in both BFF and importer — Swagger UI available at `/webjars/swagger-ui/index.html`; OpenAPI spec (JSON) at `/v3/api-docs`.
  Controllers are annotated with `@Tag(name = "Admin: <Entity>")` to group endpoints by entity type in Swagger UI (e.g. `"Admin: Venues"`, `"Admin: Events"`).
  Request and response DTOs use `@Schema` annotations on every field to provide descriptions, examples, and required-mode metadata in the generated API docs.
  Domain classes in `events-core` are intentionally kept free of Swagger annotations to avoid coupling the shared library to web concerns.
    - **Changing the BFF's public API means regenerating the frontend's types in the same PR.** `events-frontend/src/api/schema.d.ts` is generated from the
      BFF's `/v3/api-docs` and committed; nothing in either build checks that it is current, so a new endpoint, a renamed or added response field or a changed
      type leaves the frontend type-checking against an API that no longer exists. With the BFF running: `cd events-frontend && npm run generate:api`. Details
      and failure modes in [events-frontend/AGENTS.md](../../events-frontend/AGENTS.md) §API Communication.
- **Jackson 3.x** (`tools.jackson.module:jackson-module-kotlin`) is used for JSON serialization.
- **Spring Boot Actuator** is included in both BFF and importer for health checks and monitoring, and since #415 both also carry
  **`micrometer-registry-prometheus`** and expose `health,info,prometheus`. Four things about the instrumentation are decisions rather than defaults, and each
  is the kind that fails silently if reversed:
    - **Meter names are an interface.** The dashboards and alert rules in [docs/ops/PLATFORM_SETUP.md](../../docs/ops/PLATFORM_SETUP.md) §7 are written against the
      exact strings in `ImporterMetrics` and `BffMetrics`, and nothing checks that the two agree — so a rename that looks like a tidy-up is a dead panel. The
      tests assert the strings literally rather than referring to the constants, deliberately, since referring to them would pass through any rename.
    - **Tag values are constants, never derived from a venue's page.** Prometheus creates one time series per distinct tag combination, so a tag fed by free
      text is unbounded; `scrapeFailureReason()` exists to enforce that and has a test asserting no exception message ever reaches a tag.
    - **Gauges are refreshed on a schedule, not by a supplier.** Micrometer reads a gauge synchronously at scrape time, and every query here suspends — a
      supplier that asked the database would block a Netty event-loop thread. `MetricsRefreshService` writes into atomics that the gauges read; the cost is that
      they are as stale as `app.metrics.refresh-interval-ms` (60s).
    - **`@AutoConfigureMetrics` is required on any Spring test that hits `/actuator/prometheus`.** Boot forces
      `management.defaults.metrics.export.enabled` to false in tests, and without the annotation the endpoint 404s in a way that reads exactly like a wrong
      exposure list. Note also that `src/test/resources/application.yaml` **shadows** the main file in both modules, so an actuator property has to be repeated
      there — `MetricsExposureConfigTest` asserts the two lists match, because a test config that quietly differs from the shipped one is how a test starts
      lying.
- **Logging**: Both apps use [kotlin-logging](https://github.com/oshai/kotlin-logging) (`io.github.oshai:kotlin-logging-jvm`) as an idiomatic SLF4J wrapper.
  Declare loggers as: `private val logger = KotlinLogging.logger {}`. Use lambda syntax for lazy evaluation: `logger.info { "msg $var" }`. The BFF registers a
  `RequestLoggingFilter` (`WebFilter`, `HIGHEST_PRECEDENCE`) that emits one INFO access-log line per request (`GET /venues?q=astra -> 200 (12ms)`); WebFlux does
  not log requests at INFO by default. Both apps also define a `local` profile (`--spring.profiles.active=local`, or "Active profiles: local" in an IntelliJ run
  configuration) whose only effect is to mirror the console to a file — `events-importer/build/dev-env/importer.log` and `events-bff/build/dev-env/bff.log`,
  relative to each module directory — so a run can be grepped after the fact instead of scrolled in the IDE console. The filenames differ so running both at
  once keeps two logs. It is profile-gated because a container platform wants the log on stdout. `scripts/dev-env.sh up` does not need the profile — it
  redirects the importer's stdout to the repository-root `build/dev-env/importer.log` itself.
- **Error handling**: The importer has a `GlobalExceptionHandler` (`@RestControllerAdvice`) that translates domain exceptions into RFC 9457 Problem Details
  (`ProblemDetail`). Domain exceptions follow the `*NotFoundException` naming pattern (e.g. `VenueNotFoundException`)
  and map to 404. `DataIntegrityViolationException` maps to 409 CONFLICT for duplicate records. `WebExchangeBindException` maps to 400 BAD REQUEST for Bean
  Validation failures, with field-level error details in the response body. `IllegalArgumentException` maps to 500 for data inconsistencies like unknown enum
  values from manual DB edits.
- **Request validation**: All `@RequestBody` parameters in controllers are annotated with `@Valid` to trigger Bean Validation. Request DTOs use
  `@field:NotBlank` on required string fields (e.g. `name`, `title`, `sourceId`), `@field:NotNull` on required non-string fields (e.g. `eventDate`), and
  `@field:Size` for max-length constraints. Nested DTOs use `@field:Valid` for cascading validation (e.g.
  `EventRequest.artists`). The `spring-boot-starter-validation` dependency provides the Jakarta Bean Validation API.
- **Entity/Domain separation**: Persistence entities (`*Entity.kt`) in the importer are separate from domain data classes in `events-core`. Entities carry
  Spring Data annotations (`@Table`, `@Id`) and provide `toDomain()` instance method + `fromDomain()` companion factory for conversion.
- **Request/Response DTOs**: Controllers accept `*Request` data classes and always return `*Response` DTOs — never domain objects directly. This decouples the
  API contract from the domain model so internal changes don't break the API. Each response DTO has a `companion object`
  with a `fromDomain()` factory method for conversion; services call `*Response.fromDomain()` and return the response DTO directly. **Exception**:
  `EventResponse` uses `fromEntity()` instead of `fromDomain()` because the event aggregate has complex associations (artists, promoters) that are resolved at
  the entity level rather than round-tripping through the domain model. The request → response flow is: `*Request` → Service (builds domain object → persists
  via `*Entity`) → `*Response.fromDomain()` → Controller. The event module uses plural filenames (`EventEntities.kt`, `EventRepositories.kt`,
  `EventRequests.kt`, `EventResponses.kt`) when a file contains multiple related classes; the scraper module also uses `EventSourceResponses.kt` for the same
  reason; the genre tag module uses
  `GenreTagEntities.kt` and `GenreTagRepositories.kt` for the same reason. Other modules use singular names (`VenueRequest.kt`, `VenueResponse.kt`, etc.).
- **Pagination, sorting & limiting**: All list endpoints accept `Pageable` parameters via query string (`?page=0&size=20&sort=name,asc`). Controllers use
  `@PageableDefault` to declare sensible defaults (20 items per page; venues/artists/promoters sort by `name`, events sort by `eventDate`). Repositories expose
  `findAllBy(pageable: Pageable): Flow<Entity>` for Spring Data to apply
  `LIMIT`/`OFFSET`/`ORDER BY`. `WebFluxConfiguration` (in **both** the importer and the BFF) registers `StableSortPageableArgumentResolver` so WebFlux can
  resolve `Pageable` from request parameters (not auto-configured unlike Spring MVC). The event list endpoint uses batch loading to avoid N+1 queries: it
  fetches the current page of events, then bulk-fetches all artist, promoter, and genre tag associations for that page in 3 additional queries (4 queries total
  per page).
    - **`StableSortPageableArgumentResolver`** extends Spring Data's `ReactivePageableHandlerMethodArgumentResolver` and appends `id` as the final sort key.
      Every list endpoint sorts by a **non-unique** column (`name`, `eventDate`), and `LIMIT`/`OFFSET` gives PostgreSQL no obligation to order tied rows the
      same way across two queries — so a client paging through could see one row twice and never see another. The tiebreaker is always ascending (within a tie
      group only the order's _stability_ matters) and is skipped when the caller already sorts by `id` or the request is unpaged. **Do not move this to
      `@PageableDefault`**: that only applies when the request carries no `sort` parameter, and the SPA sends one, so a default-only tiebreaker leaves the real
      paging path unstable. The resolver and its test are duplicated per module because `events-core` is deliberately free of web dependencies.
    - The BFF's `EventSearchRepository` builds its `ORDER BY` by hand (filtered event search) and ends it with `e.id ASC` for the same reason; it allowlists
      sort properties, so the key the resolver appends is ignored there.
- **Read responses are cached in the BFF, at the controller layer** (#269). `ResponseCache` (a Caffeine cache in `common`) holds assembled response objects for
  `app.api.cache.ttl-seconds`, and `CacheControlFilter` puts the same lifetime in a `Cache-Control` header so a browser stops asking too. Three things about it
  are decisions rather than defaults:
    - **Invalidation is the TTL and nothing else.** The importer is a different pod and the BFF runs two replicas, so there is no in-process event to subscribe
      to. `event_source.last_success_at` would be a real watermark and reading it per request is the query the cache exists to avoid. Imports default to daily,
      so a minute is a fraction of a cycle.
    - **One shared cache with one item budget, which is why this is not `@Cacheable`.** Spring's annotation does work on suspend functions — that was tested,
      not assumed — but it gives a cache per method, so the memory bound becomes a per-cache number times however many caches exist. Bounding by items rather
      than entries matters because a calendar response carries up to 92 days while a detail response carries one.
    - **The cache sits in the controller, not the service**, so the loader runs in the caller's coroutine and the transaction, the log context and cancellation
      all behave as they would without it.
    - **Keys are a data class per endpoint**, declared beside the controller that owns them. A data class is equal only to its own type, so `/events/{slug}` and
      `/venues/{slug}` cannot answer each other even though both carry a slug. `ResponseCachingIntegrationTest` asserts exactly that.
- **API path convention**: All importer admin endpoints live under `/api/admin/<resource>` (e.g. `/api/admin/venues`, `/api/admin/events`).
- **Module metadata**: Each feature package in the importer has a `*Module.kt` marker class annotated with
  `@ApplicationModule(allowedDependencies = [...])` to declare allowed inter-module dependencies for Spring Modulith verification. Similarly, `events-core` has
  `*Module.kt` markers per feature package (`ArtistModule`, `EventModule`, `GenreTagModule`, `PromoterModule`, `VenueModule`)
  plus a root `EventsCoreModule.kt`.
- **Importer feature module structure**: Each feature package follows a consistent file layout:
  `*Controller.kt`, `*Service.kt`, `*Repository.kt`, `*Entity.kt`, `*Request.kt`, `*Response.kt`, `*Module.kt`, `*NotFoundException.kt`.
- **Slugify**: The importer uses `com.github.slugify:slugify` to always auto-generate URL-friendly slugs from entity names. Slugs are not accepted in request
  DTOs — they are a server-side concern computed by the service layer. The slug logic is encapsulated in a dedicated `slug` Spring Modulith module
  (`de.norm.events.slug`) with a `SlugGenerator`
  object singleton (see `SlugGenerator.kt`, `SlugModule.kt`). All feature modules declare `"slug"` in their `allowedDependencies`.
    - **Transliteration**: Slugify strips accents by normalizing to NFD and dropping non-ASCII, which only works for letters that _decompose_ into a base letter
      plus a combining mark (`ö`→`o`, `å`, `é`, `ñ`, `ğ`, `ş`). A letter that is a single indivisible code point has nothing to strip down to and would be
      **silently deleted**, so `SlugGenerator` supplies a `NON_DECOMPOSING_LATIN` map of explicit `customReplacements` for `ø æ ð þ ł đ ı ß œ` (both cases).
      Without it, `Kėkė Søl` → `keke-sl` and `Revaler Straße` → `revaler-strae`, and distinct names collide (`Søl`/`Sæl` → `sl`).
    - Entries map to the letter's **base form**, not its national expansion, so a slug stays internally consistent with the NFD stripping applied to its other
      letters: `ø`→`o` beside `ö`→`o`, giving `Ørlög` → `orlog`. **Do not switch to slugify's `locale()` bundles** — `no`/`da` would expand `ø`→`oe` _and_
      silently rewrite existing `å`→`aa`, and `de` would turn `ö`→`oe`, changing slugs that are already correct. `æ`, `œ`, `ß`, `þ` have no single base letter
      and take their standard two-letter romanisation. Extend the map as new letters surface.
    - **Changing the map is a data migration in disguise.** Event slugs self-heal (regenerated on every upsert, matched by `sourceId`), but
      `AssociationSyncService` resolves artists and promoters **by slug** — so an existing row keyed on the old spelling is missed and re-inserted as a
      duplicate. Pre-production the answer is a reseed, not a Flyway migration (ADR-005 keeps migrations DDL-only).
- **Price normalization**: All monetary `BigDecimal` fields (presale, box office) are normalized to scale 2 (`setScale(2, HALF_UP)`)
  at the mapping boundaries where prices enter `EventEntity` — scraper (`ScrapedEvent.toEventEntity()`) and admin API (`EventService`). The
  `BigDecimal.normalizeMoneyScale()` extension function lives in `events-core` (`MoneyExtensions.kt`) as a domain-level concern. This ensures consistent storage
  and prevents false positives in the scraper's `contentEquals` change detection, because
  `BigDecimal.equals()` is scale-sensitive (e.g. `BigDecimal("10.0") != BigDecimal("10.00")`).
- **Genre tags**: The `genre` column on events stores raw free-text from venue websites for display. A separate `genre_tag`
  table and `event_genre_tag` join table provide normalized many-to-many genre tags for structured filtering. Genre tags are auto-created during event imports
  and admin API calls — there is no manual CRUD API. The `GenreNormalizer` utility in the
  `genretag` module parses raw genre strings by splitting on common delimiters (`,`, `//`, `&`, `/`), stripping noise suffixes ("Floor", "etc."), and
  mapping known synonyms to canonical names (e.g. "Hip-Hop"/"Rap" → "Hip Hop"). Unknown genres are kept with title case and auto-created as new tags. The
  normalizer is shared between the admin API (`EventService`) and the scraper pipeline (`AssociationSyncService`). The `GET /api/admin/genre-tags` endpoint
  provides the tag list for frontend filter dropdowns.
- **Web scraping**: The importer uses a `scraper` Spring Modulith module (`de.norm.events.scraper`) for importing event data from venue websites. See ADR-007.
  Key design:
    - **Jsoup** (`org.jsoup:jsoup`) for HTML parsing — robust handling of real-world HTML with CSS selector API.
    - **Spring WebClient** for reactive HTTP fetching with ETag/Last-Modified conditional requests.
    - **`PerHostThrottlingFilter`** — WebClient `ExchangeFilterFunction` enforcing a configurable politeness delay (`app.scraper.polite-delay-millis`, default:
      200ms) between consecutive requests to the same host. Transparent to scrapers — all `HtmlFetcher` requests are throttled automatically. Requests to
      different hosts proceed concurrently. See ADR-007 "Per-Host Politeness Throttling".
    - **`EventSource` enum** — compile-time safe registry of known import sources (e.g. `CASSIOPEIA`). Each value's KDoc is a **one-line description of the
      venue** and nothing more: how its programme is published and every parsing decision are documented on that venue's importer and scrapers, so the two
      cannot drift.
    - **`EventImporter` interface** — each venue-specific importer implements this, dispatched by `eventSource` property.
    - **`event_source` table** — tracks per-venue import configuration, conditional-request headers, and import status/metrics. Event sources are created via
      `POST /api/admin/event-sources` (not Flyway — Flyway is reserved for DDL-only migrations). Operational config (enable/disable, interval, retries) is
      managed via `PATCH` and sources can be removed via `DELETE`.
    - **`EventImportService`** — orchestrates the import pipeline: resolves the correct importer, delegates persistence to `EventUpsertService`, and manages
      event source status transitions (RUNNING → SUCCESS/FAILED/MISCONFIGURED). Imports multiple sources concurrently using coroutine `async` with a `Semaphore`
      -based concurrency limit (`app.import.max-concurrency`, default: 4). This is safe because the artist cache is local to each import call, concurrent artist
      creation is handled via `DataIntegrityViolationException` fallback in `AssociationSyncService`, and per-host HTTP politeness is enforced by
      `PerHostThrottlingFilter`.
    - **`EventUpsertService`** — handles the event persistence pipeline: deduplication, change detection (skips unchanged events to avoid unnecessary writes and
      inflated `updated_at` timestamps), event upsert, and stale event cleanup. Delegates artist/promoter resolution and association syncing to
      `AssociationSyncService`. Called within a transactional boundary by `EventImportService`.
    - **`AssociationSyncService`** — resolves artists and promoters by slug (auto-creating unknown ones via
      `DataIntegrityViolationException` fallback for concurrent safety) and synchronizes many-to-many join-table associations using a diff strategy (insert new,
      update changed, delete stale). Called by `EventUpsertService`.
    - **`EventSourceService`** — CRUD service for managing event source configuration.
    - **Shared scraper utilities** — three focused extension files in the `scraper/` package, shared across all venue scrapers. New venue scrapers should use
      these utilities instead of reimplementing the same patterns.
        - **`ScrapingExtensions.kt`** — Jsoup HTML element extraction helpers (`Element.textAt()`, `Element.attrAt()`,
          `Element.imgSrcAt()`, `Element.hrefAt()`, `Element.hasVisibleWebflowFlag()`) and URL resolution (`resolveUrl()`).
        - **`DateParsingExtensions.kt`** — date/time parsing for the two common formats on venue websites: standalone
          `HH:mm` strings from HTML (`parseTime()`) and ISO 8601 datetime strings from schema.org JSON-LD (`parseIsoDate()`, `parseIsoTime()`).
        - **`EventTypeMapping.kt`** — domain-level classification of scraped text into `EventType` constants: category/genre/title keyword mapping
          (`mapEventType()`, `refineConcertVenueType()`, `isFestivalTitle()`).
        - **`ArtistNameMapping.kt`** — artist-name resolution: placeholder/non-artist detection (`isPlaceholderName()`,
          `isNonArtistName()`), name cleanup (`stripArtistSuffix()`), and artist list construction from the headliner + support pattern (`buildArtistList()`,
          `buildArtistsForEventType()`).
        - **`EventFieldMapping.kt`** — field-level mapping: status badges (`parseEventStatus()`), doors/start ordering (`orderDoorsBeforeStart()`), title
          cleanup (`cleanEventTitle()`), and free-entry detection (`detectFree()`).
    - **Venue-specific subdirectories** — each venue importer lives in its own sub-package under `scraper/` (e.g. `scraper/cassiopeia/`). Each contains a
      `*WebsiteImporter.kt` implementing `EventImporter`, plus pure (no-I/O) parsers: HTML importers use
      `*OverviewPageScraper.kt` / `*DetailPageScraper.kt`, while JSON/API importers use a single `*ApiScraper.kt` (see below). Use existing implementations as
      templates when adding new venue importers. **The sub-package's KDoc is the single home for everything about that source** — the platform, which pages or
      APIs are read and why, the traps the parser handles, and the limitations it accepts (a field the venue never publishes, a signal it cannot express).
      Defects that could actually be repaired become an **issue** instead — the 🔍 Importer / data defect form, or `/new-issue`.
    - **`AbstractTwoPageWebsiteImporter`** — base class for venues that use the overview → detail pattern, the most common shape in the `scraper/` package. The
      subclasses are deliberately not enumerated here (the list drifts with every new venue —
      `grep -rl 'AbstractTwoPageWebsiteImporter(' events-importer/src/main/kotlin/de/norm/events/scraper/` is authoritative). Owns the shared overview-fetch →
      per-event detail-fetch → gap-fill orchestration, including `NotModified` handling and the "degrade to overview data if the detail page fails" fallback.
      Subclasses implement only `scrapeOverview`, `scrapeDetail`, and `fillGapsFromOverview` (the venue-specific merge strategy). Single-page HTML venues (e.g.
      `PrivatclubWebsiteImporter`) implement `EventImporter` directly instead. The two-layer strategy itself is the decision recorded in ADR-007; the abstract
      class is just the implementation vehicle.
    - **JSON/API importers (`ApiClient`)** — venues whose events come from a structured JSON feed rather than scrapeable HTML (Festsaal Kreuzberg → Wagtail
      headless-CMS REST, Neue Zukunft → Elfsight widget boot API, Madame Claude → WordPress
      `event` REST API with ACF fields) implement `EventImporter` directly and fetch via **`ApiClient`** — the JSON counterpart to `HtmlFetcher`, sharing the
      same `@Qualifier(SCRAPER_WEB_CLIENT)` throttled `WebClient` (so the same per-host politeness and User-Agent apply). Each has a single pure
      `*ApiScraper.kt` that parses the raw JSON body into `List<ScrapedEvent>` — no Overview/Detail split. **Prefer a JSON/API source over HTML whenever one
      exists** (ADR-007 §"Prefer a JSON / API Source").
    - **List + _shared_ detail page** — a venue whose calendar lists performance _dates_ of a production run (currently Bar jeder Vernunft: 28 calendar cards
      resolving to 2 show pages) points many events at the same detail URL. Such an importer implements `EventImporter` directly and fetches each **distinct**
      detail URL once per run, applying the result to every date of that show — rather than extending `AbstractTwoPageWebsiteImporter`, whose per-event fetch
      would re-request one page 20+ times and be serialised by `PerHostThrottlingFilter`. See ADR-007 §"Shared Detail Pages".
    - **Derived occurrences for undated recurring programmes** — a venue that publishes no dates at all, only a standing weekly programme (currently Havanna:
      three undated `/wednesday`, `/friday`, `/saturday` pages linked from `/events`), is parsed into an undated recurrence model (`HavannaWeeklyNight`) and
      expanded into one dated `ScrapedEvent` per week over a rolling horizon. Such importers disable conditional requests (a 304 would freeze the horizon) and
      honour an announced closure notice on the page. See ADR-007 §"Undated Recurring Programmes". Do **not** apply this to a venue that publishes dates.
- **Scheduled imports**: The importer uses Spring `@Scheduled` with the `event_source` table for periodic event imports. See ADR-008. Key design:
    - A single `@Scheduled(fixedDelay = 60s)` tick in `ScheduledImportService` queries for due sources.
    - Due sources are imported concurrently via `EventImportService.importConcurrently()`, bounded by the configured concurrency limit
      (`app.import.max-concurrency`, default: 4).
    - Each source has its own `import_interval_minutes` (default: 1440 = daily).
    - Failed imports are retried with exponential backoff up to `max_retries` times, **capped at six hours** — doubling a 1440-minute interval produced a
      retry slower than the healthy cadence (#659). A source that spends its budget returns to its own interval; it is never dropped from the schedule, because
      a source that stops being attempted is indistinguishable from one with nothing to import.
    - Sources stuck in RUNNING for >30 min are automatically reset to FAILED (staleness guard).
    - Scheduling is enabled by default but disabled in tests via `app.scheduling.enabled: false`.
    - `@EnableScheduling` is applied on `EventsImporterApplication`.
- **OWASP Dependency-Check** (`org.owasp.dependencycheck`) scans all project dependencies against the National Vulnerability Database (NVD) for known CVEs.
  Configured at the root level with `dependencyCheckAggregate` to produce a single report. Fails the build on CVSS ≥ 7 (HIGH). False positives can be suppressed
  via `owasp-suppressions.xml`. SARIF output is uploaded to GitHub Code Scanning; the HTML report is uploaded as a CI artifact.
    - **`scanProjects` is intentionally unset.** Empty means "scan every project", which is what the aggregate report wants. The plugin matches a configured
      entry against `project.path` (`:events-core`), _not_ `project.name` (`events-core`) — a name list matches nothing, so every project is skipped and the
      report reads `Dependencies Scanned: 0` while the build passes the CVSS gate trivially. That was live in this repo until 2026-08-05 and nothing failed; **a
      green OWASP run is not evidence the scan looked at anything.** When changing this config, read `Dependencies Scanned` in the HTML report, not the exit
      code. The healthy baseline is ~208 dependencies scanned.
    - **`skipConfigurations` excludes the build-tool classpaths** (`detekt`, `detektPlugins`, the four `ktlint*` configurations, and the Kotlin
      compiler-plugin/script ones). They carry their own, often much older, copies of Kotlin and logging libraries that no BOM override can reach and that never
      ship. This narrows the scan deliberately — a real CVE in detekt or ktlint will not be reported here; Dependabot still watches them via the submitted
      dependency graph. **Do not add anything to that list that ships.** Names must match exactly (the plugin does a `contains` on the configuration name, with
      no globbing), so a renamed or newly added tool configuration silently starts being scanned again rather than erroring.
    - **Suppression entries are scoped by `packageUrl` regex, and getting the scope wrong fails open.** Two entries originally anchored on
      `org.jetbrains.(kotlin|kotlinx)/` and missed IntelliJ's repackaged `org.jetbrains.intellij.deps.kotlinx` artifacts, which alone failed the enforced scan.
      Check a new pattern against the real package URLs in the HTML report before trusting it.
