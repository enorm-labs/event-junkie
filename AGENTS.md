# AGENTS.md

## Agent Instructions

- **Git non-interactive mode**: Always run git commands with the pager disabled to prevent the agent from hanging on interactive output. Use
  `git --no-pager <command>` or set the environment variable `GIT_PAGER=cat`. This applies to all git commands that may produce paged output (`log`, `diff`,
  `show`, `branch`, etc.). See [git docs](https://git-scm.com/docs/git#Documentation/git.txt---no-pager).
- **ktlint auto-format first**: When ktlint reports formatting issues, always run `./gradlew ktlintFormat` first to auto-correct them. Only edit files manually
  for issues that ktlint cannot auto-fix.
- **Reformatting is intentional — keep it**: Files in the working tree are routinely reformatted on purpose (IDE reformat-on-save, `./gradlew ktlintFormat`,
  `npm run format`). Treat that as deliberate and leave it in place. Never revert, re-fetch, re-download, or otherwise "restore" a file to an earlier shape
  because its indentation, tabs/spaces, line wrapping, attribute order, or trailing whitespace changed — and don't reformat _back_ to a previous style either.
  Review the content instead: `git --no-pager diff -w` (or `-b`) hides whitespace-only churn. Whitespace-only changes need no report, no explanation, and no
  action; they are not a signal that something went wrong.
    - This includes **test fixtures**, notably the scraper HTML snapshots under `events-importer/src/test/resources/scraper/`. A reformatted snapshot is still a
      valid fixture — Jsoup ignores indentation and attribute order — so a reformat is never on its own a reason to re-capture a page from the live site.
    - The one real caveat: in HTML, whitespace _between inline elements_ affects the text Jsoup returns (`<b>a</b><b>b</b>` yields `ab`, but with a newline
      between them it yields `a b`). So if a reformat makes a scraper test fail, that is a genuine finding — **raise it with the user**. Do not silently revert
      the file, and do not loosen the assertion to make it pass.
- **Build verification**: Always run `./gradlew clean build` after finishing an implementation to verify that all modules compile, tests pass, ktlint and detekt
  checks succeed, and Kover coverage thresholds are met. **Skip this step** when only Markdown documentation (`.md` files) or frontend files
  (`events-frontend/`) were changed — the Gradle build covers the backend modules only. A Markdown-only change is not check-free, though: run
  `scripts/format-markdown.sh` (see §Code Conventions), which the commit hook runs anyway.
- **No unsolicited git commits/pushes**: Never run `git commit`, `git push`, or `git rebase` (squash) unless explicitly asked to by the user.
- **ADR numbers are claimed by writing the ADR, never by planning one.** A document that says _"needs ADR-0NN"_ for an ADR nobody has written yet is a
  reservation the numbering scheme does not honour: the next ADR actually written takes that number, and the reference silently starts pointing at an unrelated
  decision. This has already happened twice to the same planned ADR. **Refer to a future ADR by its title only** — _"needs an ADR: AI-Assisted Data Quality"_ —
  and assign the next free number from `docs/adr/` at the moment you create the file.
- **GitHub CLI (`gh`)**: The `gh` CLI is installed (Homebrew) and authenticated for GitHub.com and enterprise instances. Use it for GitHub interactions such as
  creating/viewing PRs, managing issues, checking CI status, and browsing repositories.
  See [GitHub CLI quickstart](https://docs.github.com/en/github-cli/github-cli/quickstart) and
  [CLI reference](https://docs.github.com/en/github-cli/github-cli/github-cli-reference).

## Privacy & GDPR — re-check when infrastructure or features change

The public privacy notice (`/legal/privacy`) and the imprint describe **what this system actually does**. Each exists as **two documents** —
`PrivacyView.en.vue` and `PrivacyView.de.vue` under `events-frontend/src/views/legal/`, with the German one authoritative — so updating one and not the other
leaves the site stating two different things. They are only correct as long as that description matches reality, and the changes that break them do not look
like privacy work. **Before merging, check whether your change falls into any category below — and if it does, say so explicitly in the PR description and
update
[docs/LEGAL.md](docs/LEGAL.md) §7 plus the privacy page in the same PR.**

**Infrastructure and operations**

- Choosing or changing a hosting provider, CDN, WAF, DNS, mail, backup, or object-storage provider — each is a processor that must be _named_, needs an Art. 28
  DPA in place, and, if it is outside the EU/EEA, a transfer mechanism. [ADR-012](docs/adr/ADR-012_CLOUD_PLATFORM.md) is **Accepted** as of 2026-08-10 and
  amended the same day to remove Cloudflare, so the intended answer is now **one processor, Hetzner**, and the notice says so. Nothing is deployed yet, so
  `INFRASTRUCTURE_IS_PROPOSED` stays `true` until it is — accepting an ADR is not the moment that changes.
- Changing log content, log retention, or IP handling (truncation/anonymisation) — the notice states a retention period; it must be the real one.
- Adding monitoring, error tracking, uptime checks, APM, or a metrics backend that receives request or user data.
- Adding a staging or preview environment reachable from the internet. **Note the SEO hazard alongside the privacy one:** the build emits a `robots.txt` that
  allows all crawlers and a `sitemap.xml` naming the production origin, so any environment serving that build invites indexing. Override both per environment.

**Features**

- **Anything stored on the visitor's device** — a cookie, `localStorage`, `sessionStorage`, IndexedDB, or the Cache API. § 25 TDDDG covers _storage on terminal
  equipment_, not cookies specifically. Today every stored item is strictly necessary, so **no consent banner is required** — that is a property worth
  protecting deliberately. The first non-essential item (analytics ID, A/B bucket, recommendation history) makes a consent banner mandatory and is a product
  decision, not an implementation detail. **Escalate rather than implement.**
- **Any third-party resource loaded by the browser** — a font, script, iframe, map, embed, social widget, or image hotlinked from another host. Each one
  transmits the visitor's IP address to that host. Fonts are self-hosted (`@fontsource-variable/geist`) for exactly this reason; keep it that way.
- **Any outbound call made from the frontend** to a domain we do not operate. The GitHub API is the tempting one — see LEGAL.md §4.1 for why the footer's
  version does not come from it.
- **Accounts, login, sessions, newsletter, contact form, comments, favourites, or notifications** — each introduces user data we do not process at all today,
  and needs its own legal basis, retention period and deletion route.
- **New personal data in the domain model.** Artist names are already personal data (§7.3 of the plan, and §4 of the privacy notice). Adding contact details,
  social handles, photographs of identifiable people, or user-submitted content extends that materially.
- **Analytics of any kind**, including self-hosted and "cookieless" tools. Self-hosted and cookieless is a better posture, but it is still processing and still
  needs a legal basis and a notice entry.

**Commercial changes** — ads, affiliate links, sponsorships, donations, or paid features also change the § 5 DDG imprint analysis, not just the privacy notice.

When in doubt, flag it in the PR rather than deciding silently. The cost of raising it is a sentence; the cost of missing it is a legal defect on a public site.

## Project Overview

Event Junkie is a multi-module Kotlin/Spring Boot application for discovering music events in Berlin. It uses a **Gradle multi-project build** with three
subprojects sharing a root `settings.gradle.kts`, plus a standalone frontend project:

- **`events-core`** – Shared domain model library (no Boot app); consumed via `project(":events-core")` dependency. Applies `java-library`, `maven-publish`, and
  `java-test-fixtures` plugins (add fixtures under `src/testFixtures/`). Uses `api()` scope for `spring-modulith-starter-core` so it's transitively available to
  consumers. Contains domain data classes organized by feature: `artist/`, `event/`, `promoter/`, `venue/`. Also defines enums (`EventType`,
  `EventStatus`, `ArtistRole`) and the `LineupEntry` value object in `event/Event.kt`.
- **`events-bff`** – Backend-for-Frontend REST API (Spring Boot 4 + WebFlux + R2DBC). Runs on default port `8080`.
- **`events-importer`** – Imports events from external sources into the database (Spring Boot 4 + WebFlux + R2DBC + Flyway). Runs on port `8081`. Owns all
  Flyway migrations under `src/main/resources/db/migration/`.
- **`events-frontend`** – Vue 3 SPA (Vite 8, TypeScript 6, Vue Router). Uses oxlint/oxfmt for linting/formatting. Not a Gradle subproject — managed separately
  via npm. Requires Node `>=22.13.0` (see `engines` in `package.json`) — raised from 20 when vue-i18n was adopted, see ADR-013.

## Architecture Decisions

- **Reactive stack throughout**: WebFlux + R2DBC + Kotlin coroutines. Do NOT use blocking APIs (`spring-web`, JDBC) in request paths. Repositories extend
  `CoroutineCrudRepository` so all operations are suspending functions.
- **Spring Data R2DBC query derivation limitations**: Unlike Spring Data JPA, R2DBC has more limited query derivation. Derived `findBy*`, `countBy*`,
  `existsBy*`, and `deleteBy*` methods are supported. However, derived `updateBy*` methods are **not supported** — use `@Modifying` + `@Query` with raw SQL
  instead. Custom `@Query` SQL must include the schema prefix (e.g. `events.event_artist`) because raw queries bypass the `@Table(schema = ...)` metadata.
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
  `NamingStrategy` bean in `R2dbcConfiguration` reads the schema from `spring.r2dbc.properties.schema` and applies it globally to all derived query methods
  (`findBy*`, `save`, `delete`, etc.), so `@Table` annotations don't need to repeat
  `schema = "events"`. Without this `NamingStrategy`, Spring Data R2DBC generates unqualified table references (e.g. `INSERT INTO "venue"`) that fail because
  the tables don't exist in the `public` schema. Raw `@Query` SQL must still include the schema prefix manually (e.g. `events.event_source`) since custom
  queries bypass both `@Table` and
  `NamingStrategy` metadata.
- **Database migrations** live in `events-importer` only (Flyway). The BFF does not run migrations. Migration naming: `V001__description.sql`. While the project
  is in development (not yet deployed to production), all schema changes are consolidated into a single
  `V001__create_initial_schema.sql` migration. Incremental migrations (`V002`, `V003`, …) will be introduced once the first production deployment establishes a
  baseline.
- **Docker Compose dev services**: `bootRun` auto-starts PostgreSQL via Spring Docker Compose support (`compose.yaml` at root).
- **SpringDoc OpenAPI** enabled in both BFF and importer — Swagger UI available at `/webjars/swagger-ui/index.html`; OpenAPI spec (JSON) at `/v3/api-docs`.
  Controllers are annotated with `@Tag(name = "Admin: <Entity>")` to group endpoints by entity type in Swagger UI (e.g. `"Admin: Venues"`, `"Admin: Events"`).
  Request and response DTOs use `@Schema` annotations on every field to provide descriptions, examples, and required-mode metadata in the generated API docs.
  Domain classes in `events-core` are intentionally kept free of Swagger annotations to avoid coupling the shared library to web concerns.
    - **Changing the BFF's public API means regenerating the frontend's types in the same PR.** `events-frontend/src/api/schema.d.ts` is generated from the
      BFF's `/v3/api-docs` and committed; nothing in either build checks that it is current, so a new endpoint, a renamed or added response field or a changed
      type leaves the frontend type-checking against an API that no longer exists. With the BFF running: `cd events-frontend && npm run generate:api`. Details
      and failure modes in [events-frontend/AGENTS.md](events-frontend/AGENTS.md) §API Communication.
- **Jackson 3.x** (`tools.jackson.module:jackson-module-kotlin`) is used for JSON serialization.
- **Spring Boot Actuator** is included in both BFF and importer for health checks and monitoring.
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
    - Failed imports are retried with exponential backoff up to `max_retries` times.
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

## Build & Dev Commands

```bash
./gradlew clean build          # Full build (all modules, tests, ktlint)
./gradlew :events-bff:bootRun  # Run BFF (auto-starts Postgres via compose.yaml)
./gradlew :events-importer:bootRun  # Run importer
./gradlew ktlintCheck          # Lint all modules
./gradlew ktlintFormat         # Auto-fix formatting
./gradlew detekt               # Static analysis (all modules)
./gradlew koverLog             # Print test coverage summary per module
./gradlew koverHtmlReport      # Generate HTML coverage reports
./gradlew dependencyUpdates    # Check for newer dependency versions
./gradlew dependencyCheckAggregate --no-configuration-cache  # OWASP Dependency-Check (CVE scan)
./gradlew httpTest                  # Run .http files via IntelliJ HTTP Client CLI (requires ijhttp + running importer)
```

Performance tests against the BFF's read API ([k6](https://k6.io); `brew install k6`, and the BFF has to be running):

```bash
k6 run perf/smoke.js           # every endpoint once — safe to run anywhere, ~1s, tolerates an empty DB
k6 run perf/load.js            # sustained realistic load — watch whether p95 climbs with the VU count
k6 run perf/spike.js           # a sudden surge — the finding is whether it recovers, not the peak
```

See [perf/README.md](perf/README.md) for what each answers, the thresholds and how to re-baseline them, and why there is deliberately no CI workflow yet.

Infrastructure ([`infra/`](infra), OpenTofu). **Read [infra/AGENTS.md](infra/AGENTS.md) before touching any of it** — it opens with the commands that must
never be run there. These four are the safe ones, need no credentials, and are exactly what `validate-infra.yml` runs:

```bash
tofu fmt -recursive -check -diff infra
tofu -chdir=infra/<stack> init -backend=false   # bootstrap · environments/production · environments/staging
tofu -chdir=infra/<stack> validate
shellcheck -x infra/modules/environment/cloud-init/*.sh
```

`tofu plan` and `tofu apply` are **not** on that list: they need a Hetzner API token and they spend money. Nothing in `infra/` has ever been applied.

Helm chart ([`deploy/`](deploy)). **Read [deploy/AGENTS.md](deploy/AGENTS.md) before touching it.** Everything that renders the chart is safe — it reaches no
cluster and needs no kubeconfig — and these are what `validate-chart.yml` runs:

```bash
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
helm template t deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
deploy/scripts/render-assertions.sh          # asserts on the rendered output; the gate that matters
shellcheck -x deploy/scripts/*.sh
```

`helm install`, `upgrade`, `uninstall` and `rollback` are **not** on that list — and neither is `helm install --dry-run`, which resolves the current kubeconfig
context and talks to that cluster. Use `helm template`, or `--dry-run=client` when you specifically need `NOTES.txt`. The chart has never been installed
anywhere: #263 is the first time it runs.

The whole stack on a local Kubernetes — the runtime counterpart to everything above, since `helm template` passing is not evidence that a pod starts:

```bash
scripts/k3d-rehearsal.sh all      # build, install on k3d, assert routing, run a real import, tear down
```

Driven by [`/k3d-rehearsal`](.github/prompts/k3d-rehearsal.prompt.md). It is the only thing here that talks to a Kubernetes cluster, and it passes
`--context k3d-event-junkie` on every call rather than trusting the active one — read `deploy/AGENTS.md` before changing that.

Container images (`events-bff/Dockerfile`, `events-importer/Dockerfile`). The build context is each module's `build/docker`, not the module directory — it is
exactly the extracted layers, which is why neither needs a `.dockerignore`:

```bash
./gradlew :events-bff:bootJarLayers                 # explode the fat jar into build/docker/
docker buildx build -f events-bff/Dockerfile events-bff/build/docker \
  --platform linux/amd64,linux/arm64 --output type=cacheonly          # both arches, no push
docker buildx build -f events-bff/Dockerfile events-bff/build/docker -t event-junkie/bff:dev --load
```

Three rules these files exist under, each of which something else depends on:

- **No `RUN`, and no builder stage.** A `RUN` executes target-architecture code, which is what would force QEMU or a runner per architecture. With none, one
  runner emits both platforms. This is why the layer extraction lives in Gradle rather than in the Dockerfile, unlike Spring Boot's reference example — and it
  is also why the **AOT cache** Spring Boot recommends for Java 25+ is deliberately not used: it needs a `RUN` and its output is architecture-specific.
- **`USER 1000:1000`, numeric.** A named user would need `RUN useradd`. It must match `security.runAsUser` in the chart's `values.yaml`; a mismatch is a pod
  that cannot read its own files, which does not look like a values problem from the logs.
- **Nothing about the runtime is baked in.** Ports and `JAVA_TOOL_OPTIONS` come from the chart via `SERVER_PORT`, `MANAGEMENT_SERVER_PORT` and the environment.
  A value fixed in the image either gets overridden confusingly or silently wins.

The **frontend** image follows the same shape with a different artefact — `npm run build` produces `dist/`, and the image is nginx plus that directory:

```bash
npm --prefix events-frontend run build
docker buildx build events-frontend -t event-junkie/frontend:dev --load
```

Three things about it that are decisions rather than defaults:

- **`nginxinc/nginx-unprivileged`, not `nginx`.** It listens on 8080 (a non-root process cannot bind 80) and its `nginx.conf` already relocates the pid file
  and every `*_temp_path` into `/tmp`, which is why the container needs exactly one writable path. Replace `conf.d/default.conf` only — rewriting the image's
  `nginx.conf` is how those properties get lost.
- **No `/api` proxy.** The ingress routes `/api` to the BFF and `/` here, so nginx never sees an API request. Running the image standalone therefore gives a
  working site whose API calls 404, and that is expected.
- **`index.html` is `no-cache`, `/assets/` is `immutable`, and a missing asset must 404** rather than fall back to `index.html` — otherwise a stale page asking
  for a deleted bundle gets HTML with a 200 and fails to parse as JavaScript.

**Verify a change by running the image the way the chart will**, which is the check that catches what `docker build` cannot:

```bash
docker run --rm --read-only --tmpfs /tmp --user 1000:1000 -e … event-junkie/bff:dev
docker run --rm --read-only --tmpfs /tmp --user 1000:1000 -p 8080:8080 event-junkie/frontend:dev
```

Local dev environment (used by `/importer-smoke` and `/next-importer`; run with no arguments for the full command list):

```bash
scripts/dev-env.sh status                 # Is the database / importer / bff / frontend up?
scripts/dev-env.sh db-reset               # docker compose down --volumes + fresh Postgres
scripts/dev-env.sh up [service…]          # Start in the background, wait until it answers
scripts/dev-env.sh down [service…] [--db] # Stop service(s) (and optionally the database)
scripts/dev-env.sh seed-all               # Run http/importer/dev-seed.http via ijhttp — scrapes every venue
scripts/dev-env.sh seed-one v.json s.json # Register a single venue + event source, print its slug
scripts/dev-env.sh import <slug>          # Trigger one source's import and poll until it settles
scripts/dev-env.sh snapshot [file]        # Per-source event counts (regression baseline)
scripts/dev-env.sh diff-snapshot a b      # Which sources gained or lost events between two snapshots
scripts/dev-env.sh check <slug>           # Data-quality report for one source
```

`service` is one or more of `importer` (default) · `bff` · `frontend` · `all`, so bare `up` / `down [--db]` behave exactly as before. `up all` brings up the
whole stack; the frontend proxies `/api` to the BFF (`events-frontend/vite.config.ts`), so on its own it renders but every request 502s. The frontend is pinned
with `--strictPort` — a busy port fails loudly instead of Vite quietly moving to the next one.

`up` starts the importer with `app.scheduling.enabled=false` so a smoke test scrapes only the source under test rather than every source whose 24h interval
happens to be due. Pass `--scheduling` to leave it on (that is the configuration in which the scheduler races manual triggers — see ADR-009 on the import
claim). Neither `bootRun` nor this script hot-reloads Kotlin — restart (`down` then `up`) after changing code, or the smoke test runs the previous build. Vite
_does_ hot-reload, so the frontend needs no restart. Runtime artefacts land in `build/dev-env/` (gitignored): `<service>.log`, `<service>.pid`, snapshots.

When launching these from an agent shell, redirect the command's own output (`> file 2>&1 < /dev/null`) — the detached `bootRun`/`vite` process inherits the
tool's stdout pipe and keeps the call hanging long after the script itself has exited.

**Never run Gradle while an import is in flight.** The "does not hot-reload" note above is about _picking up_ your changes; it is not the same as nothing
happening. Both Boot modules carry `spring-boot-devtools` (`developmentOnly`), which watches the classpath — so **any** task that writes classes
(`compileKotlin`, `classes`, `build`, even a single `--tests` run) restarts the running service and **kills every import mid-flight**. Those sources are then
stuck in `RUNNING` forever, because the 30-minute staleness guard only runs under the scheduler and `dev-env.sh up` disables it. The tell in the log is
`restartedMain` next to a suspiciously short `Started EventsImporterApplicationKt in 1.0 seconds (process running for 117.3)`. There is no reset endpoint;
recovery is manual:

```bash
scripts/dev-env.sh psql "UPDATE events.event_source SET status='IDLE', retry_count=0, version=version+1 WHERE status='RUNNING'"
```

then re-trigger those slugs. On a long job — a `--full` re-seed, a before/after diff — compile everything first, restart once, _then_ import, and leave the
build alone until every source has left `RUNNING`.

**Re-keying a live source collides with its own today-dated rows.** Changing how a scraper builds its `sourceId` — adding the session start time, the occurrence
date, anything — gives every event a new id, so the old rows go stale and the new ones insert. But `EventUpsertService.removeStaleEvents`
deliberately spares **today**: a today-dated row therefore keeps its old id _and_ its slug while its replacement tries to take the same slug, and the insert
collides. **Re-key on a day the venue's programme is dark, or clear that source's rows first** — and check which it is before importing rather than after.
Admiralspalast (2026-08-08) got away with it by luck; Velomax (2026-08-09) was checked and was genuinely dark three weeks out.

**Do not truncate `<service>.log` while the service is running.** `: > build/dev-env/importer.log` looks like the obvious way to get a clean log before a test
import, and it silently breaks every later `grep`: the process keeps its file descriptor at the old offset, so new writes land far into the file and everything
before them is NUL padding. `grep` then treats the file as binary and prints `Binary file … matches`, or **nothing at all with `-c`** — which reads exactly like
"no matches" and is how a real finding gets reported as a clean result. Get a clean log by restarting the service instead (`down` then `up`, which reopens the
file); if one has already been truncated, `grep -a` reads it. **A zero count from a log you truncated is not evidence.**

**Working in a git worktree** (a session started with `claude --worktree`, or any `git worktree add` checkout — see
[docs/WORKTREES.md](docs/WORKTREES.md)). Files and Gradle output are isolated; the local runtime is not.

- **Export `COMPOSE_PROJECT_NAME=event-junkie` before any `bootRun` or `scripts/dev-env.sh up` in a worktree.** Docker Compose names the project after the
  directory containing the `compose.yaml` it is given, and both paths pass the worktree's copy — so without the override the worktree starts a _second_
  Postgres on a new empty volume, which collides with the main checkout on host port `56298` and makes `diff-snapshot` report every existing source as `GONE`.
  With it, the running `event-junkie-postgres-1` container and its seeded data are reused.
- **One stack at a time.** Ports `8081` / `8080` / `5173` are fixed in `application.yaml` and `dev-env.sh`; `IMPORTER_HOST` / `BFF_HOST` only change the URL the
  script polls, not the port the JVM binds. Run `scripts/dev-env.sh down` in the other checkout before `up` here, and remember `bootRun` does not hot-reload —
  whichever worktree started the JVM is the code under test.
- **Never trigger an import while another worktree is importing.** `snapshot` / `diff-snapshot` are per-source counts over the whole shared database, so the
  other session's events land in this session's regression diff.
- **Expect conflicts in the files every importer PR touches**: the count table and moved row in `docs/EVENT_DATA_SOURCES.md` (recount after rebasing rather than
  trusting either side), the alphabetical header list and venue block in `http/importer/dev-seed.http` (a "keep both" resolution silently fuses two blocks —
  rebuild by hand) and the new `EventSource.kt` enum entry. Rebase onto `main`; never merge `main` in. The backlog snapshot is generated into `build/` and is
  not committed, so it never appears in a diff at all.

The **configuration cache** is enabled (`org.gradle.configuration-cache=true` in `gradle.properties`), so repeat builds skip the configuration phase. Every task
above benefits except `dependencyCheckAggregate` — the OWASP plugin's `Aggregate` task reaches for `project.rootProject` / `project.subprojects` at execution
time, which the configuration cache forbids. That task still runs correctly without the flag, but the cache entry is discarded on every invocation and the build
prints a problems report, so pass `--no-configuration-cache` to skip the futile attempt. Both CI workflows that run it already do. Still the case on **13.0.0**;
the upstream fix ([dependency-check-gradle#478](https://github.com/dependency-check/dependency-check-gradle/pull/478)) is still open, so recheck when it lands.

Frontend (`events-frontend/`):

```bash
npm run dev        # Vite dev server
npm run build      # Type-check + production build
npm run test:unit  # Vitest unit tests
npm run test:e2e   # Playwright end-to-end tests
npm run lint       # oxlint + eslint (auto-fix)
npm run format     # oxfmt formatter
```

Java version is managed via SDKMAN (`.sdkmanrc` pins `java=25.0.2-tem`; run `sdk env` to activate). Toolchain target: **Java 25**.

## Code Conventions

- **Application version**: lives in `version` in the root `gradle.properties` — the single source of truth. Gradle applies it to every project, so
  `build.gradle.kts` must **not** assign `version` in its `subprojects` block; a leftover assignment silently wins while the build stays green.
  `events-frontend/package.json` mirrors it **by hand**, deliberately without the `-SNAPSHOT` suffix (npm SemVer has no such convention), so the two files are
  intentionally not byte-identical — both move in one commit. A release build overrides the version from the tag (`-Pversion=0.1.1`) rather than editing the
  file. The version the site displays always comes from `GET /meta`, which is stamped from the build — never from `package.json`. See
  [docs/LEGAL.md](docs/LEGAL.md) §4.
- **Package structure**: `de.norm.events.<module-name>` — organize by feature/domain, not layer.
- **Kotlin DSL** for all Gradle build scripts (`build.gradle.kts`).
- **Kotlin 2.4.10** with **Spring Boot 4.1.0**; plugin versions pinned in `settings.gradle.kts` `pluginManagement`.
- **ktlint 1.8.0** enforced project-wide via root `subprojects` block; do not override per-module.
- **detekt 2.0.0-alpha.6** (`dev.detekt` plugin, migrated from `io.gitlab.arturbosch.detekt`) applied project-wide. The 2.0 line is still pre-release; the alpha
  is tracked deliberately because it is what supports current Kotlin (see the compatibility-table link in `settings.gradle.kts`). Builds upon default config
  with overrides in root `detekt.yml` (currently only `MaxLineLength: 160`). Run `./gradlew detekt` to analyze all modules.
- **Max line length**: 160 characters (enforced by both `.editorconfig` and `detekt.yml`).
- **Markdown is formatted by oxfmt**, via `scripts/format-markdown.sh` (config: root `.oxfmtrc.json`, hook: `format-markdown`). Tables aligned, `_emphasis_`,
  `-` bullets, and **prose left exactly where it was wrapped** — `proseWrap: preserve`, so hard wrapping is still yours to place and a prose edit stays a
  one-line diff. Full rationale in [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) §Markdown formatting; the parts that matter when editing:
    - **Never widen it past Markdown.** oxfmt also formats YAML, JSON, CSS and TS. Running it across this repository's YAML and JSON was measured and rejected:
      105,005 lines of churn, of which 94,336 were captured scraper fixtures (which must stay faithful to what the venue actually returned) and 10,298 were
      Flux-generated `gotk-components.yaml`. The genuinely useful remainder was 371 lines of single-to-double quote churn in workflows. Worse, oxfmt **cannot
      parse the Go-templated YAML** under `deploy/charts/*/templates/` at all — it errors and exits 2 on all 16 of them, the same reason
      `.pre-commit-config.yaml` refuses a `check-yaml` hook. Scope is pinned in two independent places (the script's arguments and `ignorePatterns`); a change
      that loosens either is a change that breaks the chart build.
    - **Use the pinned binary**, `events-frontend/node_modules/.bin/oxfmt`, never one on `$PATH`. oxfmt is pre-1.0 and its Markdown output is not stable across
      versions, so the hook, CI and every contributor have to be on one version; `package-lock.json` is what makes that reproducible. The script already does
      this — do not "simplify" it to `oxfmt`.
    - **oxfmt reads `.editorconfig`.** The `[*] indent_size = 4` is what gives nested list items their four-space indent; without that file oxfmt uses its own
      default and produces different output. So a scratch directory does not reproduce this repository's formatting unless `.editorconfig` is copied into it —
      worth knowing before concluding that two oxfmt versions disagree, which is exactly how a scratch-directory measurement misled once already.
    - **Write mode runs it twice**, because a table nested under a list item is skipped on the first pass. This file is the one that exhibits it. **Permanent
      and intended** — Prettier behaves identically and oxfmt tracks Prettier, so upstream closed it as _not planned_; do not try to drop the second run on a
      version bump. See [DEVELOPMENT.md](docs/DEVELOPMENT.md) §Markdown formatting.
- Centralized library versions in **`gradle.properties`** (`java.version`, `jsoup.version`, `kotest.version`,
  `kotlin-logging.version`, `mockk.version`, `mockwebserver.version`, `slugify.version`, `spring-modulith.version`,
  `springdoc.version`), read in the module build scripts via `property("…")`; plugin versions in `settings.gradle.kts`
  `pluginManagement`. They live in `gradle.properties` rather than root `extra[...]` because Gradle 10 removes the implicit lookup of parent-project properties
  that the `extra[...]` form depended on.
    - **`gradle.properties` also holds a second, different kind of entry** — the "Spring Boot BOM overrides (CVE remediation)" block (`netty.version`,
      `postgresql.version`, `log4j2.version`, `jackson-2-bom.version`, `jackson-bom.version`) plus `scram.version`. These are **not** ordinary project versions
      and must not be bumped on sight. Each overrides a version the Spring Boot BOM would otherwise manage, and exists only because the BOM's version carries a
      known CVE. `io.spring.dependency-management` resolves BOM properties from Gradle project properties, so naming the BOM's own property here is enough to
      reach every module that applies the Boot plugin.
    - **They are temporary by design: delete each one once a Spring Boot release ships an equal or newer version.** An override kept past its purpose pins the
      project _behind_ the BOM, so later Boot upgrades stop raising that dependency and the staleness is invisible. `/update-dependencies` checks this on every
      run.
    - Two dependencies are not BOM-managed at all and are pinned by `constraints` blocks instead: **`scram.version`** (a transitive of `r2dbc-postgresql`, which
      pins the vulnerable version in every release) in both Boot modules, and **`log4j2.version`** reused in `events-core`. That last one matters —
      `events-core` applies `io.spring.dependency-management` but **not** the Boot plugin, so no BOM override reaches it. Importing the Boot BOM there is not a
      fix: without the Boot plugin nothing aligns the BOM's `kotlin.version`, and `compileKotlin` fails with a null plugin classpath. **When adding a BOM
      override, check `events-core` separately — verifying only the two Boot modules will report success while this one keeps the vulnerable version.**
- Use `val` for injected dependencies; constructor injection only (no field injection).
- Application config files use **`.yaml`** extension (not `.yml`).
- Kotlin compiler flags: `-Xjsr305=strict` (all modules) and `-Xannotation-default-target=param-property` (BFF + importer) are set in `compilerOptions`.
- **A Kotlin warning fails the build in CI, not locally.** The warning set is empty and stays that way because `build-backend.yml` sets
  `ORG_GRADLE_PROJECT_warningsAsErrors=true` for its whole job, which the root `build.gradle.kts` turns into `allWarningsAsErrors` on every `KotlinCompile`
  task (`main` and `test` alike). Locally it is off by default, deliberately: the warnings that appear unbidden come from a Kotlin or Spring Boot upgrade, and a
  red local build punishes whoever runs the bump at the moment they can least act on it — in CI the same failure is a PR check.
    - **Reproduce a CI failure locally with `./gradlew build -PwarningsAsErrors`**, and turn it off again with `-PwarningsAsErrors=false` (an explicit `false`
      really disables it; the switch is not merely presence-based).
    - **It does not cover the build scripts.** `build.gradle.kts` is compiled by Gradle's Kotlin DSL, not by these tasks, so a warning there only ever prints —
      and Gradle caches the compiled script by content hash, so it prints exactly once and then never again until the file changes. If you are hunting one, add
      a throwaway comment to bust the cache.
- **Kover** (`org.jetbrains.kotlinx.kover`) is configured for code coverage reports. Run `./gradlew koverLog` for a console summary or
  `./gradlew koverHtmlReport` for detailed HTML reports.
    - **Exclusions live in three places, and filters never propagate between them.** A class hidden from one report is still counted in the others unless it is
      excluded there too — this is the single thing to know before editing them.

        | Where                                                                         | Scope                     | Holds                                                              |
        | ----------------------------------------------------------------------------- | ------------------------- | ------------------------------------------------------------------ |
        | root `build.gradle.kts`, `subprojects { configure<KoverProjectExtension> … }` | every module's own report | `de.norm.events.*Module`, `de.norm.events.*Fixtures`               |
        | root `build.gradle.kts`, top-level `kover { }`                                | the aggregated report     | the shared patterns **again**, plus the events-core domain classes |
        | `events-core/build.gradle.kts`, `kover { }`                                   | events-core's own report  | its plain domain data classes, by exact name                       |

    - **What gets excluded, and why**: classes with no executable logic, whose synthetic members Kover would otherwise count as uncovered — Spring Modulith
      `@ApplicationModule` markers (`*Module`), published `java-test-fixtures` factories (`*Fixtures`), and events-core's plain domain data classes. Everything
      that carries logic stays measured.
    - `*` **spans package segments** in a Kover class pattern, so `de.norm.events.*Module` matches `de.norm.events.meta.MetaModule`. That is why the domain data
      classes are listed by exact name instead: a `de.norm.events.*Entity`-style pattern would silently swallow the BFF/importer persistence classes, which
      _should_ be measured.
    - Adding a new `*Module` marker or `*Fixtures` factory therefore needs no config change. Anything else does — in all three places.
    - **`koverVerify` enforces a line-coverage floor per module**, and `check` (so `build`) runs it. Floors are set in `koverVerificationFloor(...)` in the root
      `build.gradle.kts`, next to the number each module actually sits at.

        | Module            | Actual | Floor |
        | ----------------- | -----: | ----: |
        | `events-core`     | 100.0% |    95 |
        | `events-bff`      |  98.6% |    92 |
        | `events-importer` |  95.4% |    90 |
        | aggregate         |  95.6% |    90 |

    - **They are floors, not targets, and the gap is deliberate.** A floor pinned to today's number fails the build for one uncovered line, which teaches people
      to lower it — and a threshold that gets lowered on contact is worse than no threshold. These catch a _material_ regression: a feature landing untested, or
      a test class quietly ceasing to run. **Do not raise a floor in the same PR that pushes the number up**; raise it when a module has held comfortably above
      the next step for a while.
    - **If `koverVerify` fails, write the test.** Lowering the floor is a decision to be argued for in the PR description, not a way to go green.
    - **`-x test` implies `-x koverVerify`.** Skipping tests leaves no execution data, so every module reports 0% and the rule fails for a reason that has
      nothing to do with coverage. `build-backend.yml` passes both flags in its build step and runs `koverVerify` in the coverage step instead, after `test`.
      Any other `build -x test` invocation needs the same treatment.
- **Kotlin idioms** (per [official coding conventions](https://kotlinlang.org/docs/coding-conventions.html)):
    - **Trailing commas** at declaration sites (constructor params, function params, enum entries, collection literals) — produces cleaner VCS diffs.
    - **Expression bodies** — prefer `fun foo() = expr` over `fun foo() { return expr }` for single-expression functions.
    - **Named arguments** — use when a function has multiple parameters of the same type or Boolean parameters whose meaning isn't obvious from context.
    - **Immutable collection interfaces** — declare parameters and return types as `List`, `Set`, `Map` (not `MutableList` etc.) when the collection is not
      mutated. Use `listOf()`, `setOf()`, `mapOf()` factory functions.
    - **Expression form of control flow** — prefer `if`/`when`/`try` as expressions returning a value over imperative `return` inside branches.
    - **Higher-order functions over loops** — prefer `filter`, `map`, `flatMap`, `associate` over imperative `for` loops where readability is equal or better.
    - **Default parameter values** — prefer over function overloads.
    - **Scope functions** — use `let`, `apply`, `also`, `run`, `with` appropriately; avoid deep nesting of scope functions.

## Testing Patterns

- **JUnit 5** + **WebTestClient** for reactive endpoint tests (see `BaseControllerTest.kt`). Create the client via lazy delegate with `@LocalServerPort`:
    ```kotlin
    @LocalServerPort private var port: Int = 0
    private val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }
    ```
- **Spring Boot 4 test starters**: Each runtime starter has a `*-test` companion (e.g. `spring-boot-starter-webflux-test`,
  `spring-boot-starter-data-r2dbc-test`). Always add the `-test` variant alongside the main starter.
- Tests requiring PostgreSQL import `PostgresTestcontainersConfiguration` via `@Import` — this provides a reusable Testcontainers `@ServiceConnection` bean.
  Both `events-bff` and `events-importer` have their own copy.
- Testcontainers use `PostgreSQLContainer("postgres:18.3-alpine").withReuse(true)` to match the dev compose image and speed up repeated test runs. Uses modular
  Testcontainers 2.x artifacts (`org.testcontainers:testcontainers-postgresql`, `testcontainers-r2dbc`, `testcontainers-junit-jupiter`)
  with modular package imports (`org.testcontainers.postgresql.PostgreSQLContainer`).
- Use backtick function names for readable test descriptions: `` `GET hello returns Hello world`() ``.
- **BaseControllerTest** (importer only): Abstract base class for integration tests that extends Testcontainers setup, provides a `WebTestClient`, and truncates
  all domain tables via `@BeforeEach` so each test starts with a clean database. Extend this instead of repeating boilerplate.
- **Kotest assertions**: The importer uses `io.kotest:kotest-assertions-core` for expressive test matchers (e.g. `shouldBe`, `shouldContain`).
- **MockK**: The importer uses `io.mockk:mockk` for mocking in Kotlin tests (preferred over Mockito). Used for unit-testing services with injected dependencies.
- **MockWebServer**: `ApiClientTest` and `HtmlFetcherTest` drive the real `WebClient` pipeline against a local server rather than mocking HTTP. Use the **
  `com.squareup.okhttp3:mockwebserver3`** artifact (package `mockwebserver3`), _not_ the legacy `com.squareup.okhttp3:mockwebserver` — the latter still ships at
  5.x purely as a deprecation bridge whose `MockWebServer` extends JUnit 4's `ExternalResource`, which would put `junit:junit` back on the classpath of this
  JUnit 5-only project. API notes: `MockResponse` is immutable (`MockResponse.Builder().code(…).body(…).build()`), the server is closed with `close()` rather
  than `shutdown()`, and the recorded request line is `RecordedRequest.target` (the okhttp 4 `path` property is gone; `target` includes the query string, so it
  is a drop-in replacement).
- **Test fixture factories**: Each importer feature module has a `*RequestFixtures` object singleton with factory methods that provide sensible defaults, so
  tests only override properties relevant to the scenario (e.g. `VenueRequestFixtures.astra()`, `VenueRequestFixtures.create(name = "Privatclub")`).
- **Full lifecycle integration test**: `FullLifecycleIntegrationTest` exercises the complete CRUD flow across all entity types in a single sequential scenario
  (create → list → get → update → delete), mirroring the `full-lifecycle.http` script. Extend this pattern for new cross-entity workflows.
- `ModularityTests` in each module (core, BFF, importer) validates Spring Modulith structure and generates docs to `build/spring-modulith-docs/`.
- `events-core` publishes test fixtures via `java-test-fixtures` plugin — consume with `testImplementation(testFixtures(project(":events-core")))`.

## CI/CD & Automation

- **GitHub Actions** runs these workflows (`.github/workflows/`):
    - `build-backend.yml` — Lint (`ktlintCheck`), static analysis (`detekt`), build, test, and OWASP dependency CVE scan. Posts detekt markdown reports and
      Kover coverage to the job summary; on PRs, also posts Kover coverage as a sticky comment (via `mi-kas/kover-report`). Detekt SARIF reports are uploaded
      per module to GitHub Code Scanning. Triggers on `main` push/PR, skips
      `events-frontend/**`, `*.md`, `docs/**`. Its build job also sets `ORG_GRADLE_PROJECT_warningsAsErrors=true`, so a Kotlin warning fails the build here and
      nowhere else — see §Code Conventions.
    - `build-frontend.yml` — Install, lint, build, unit test, and Playwright e2e test. Triggers only when `events-frontend/**` changes. Uses Node 24.
    - Both build workflows also declare **`workflow_dispatch`**, so they can be run by hand —
      `gh workflow run build-backend.yml --ref <branch>` (or the Actions tab). This exists because the automatic triggers cannot always be relied on: during the
      2026-08-06 Actions outage GitHub throttled webhooks to ~15%, so four PRs merged without a run ever being _created_, and `gh run rerun` cannot help when
      there is no run to re-run. A manual run ignores the path filters, so it also answers "build this ref anyway". **Caveat:** GitHub only offers a manual
      trigger for workflows present on the **default branch**, so a `workflow_dispatch` added in a PR is not usable until that PR merges.
    - `dependency-review.yml` — Runs on PRs to diff dependency changes between base and head. Flags newly introduced vulnerabilities (high+ severity) and
      license issues using the GitHub Advisory Database. Complements OWASP Dependency-Check with fast, PR-scoped feedback.
    - `dependency-submission.yml` — Submits Gradle dependency graph to GitHub on `main` push (for Dependabot alerts/security).
    - `dependency-check-scheduled.yml` — The authoritative nightly OWASP Dependency-Check on `main`. Owns the shared NVD cache that the informational PR scan in
      `build-backend.yml` restores.
    - `label-pr.yml` — Derives labels from the Conventional Commits PR title (`feat(scraper): …` → `feat` + `importer`, `fix(api)!: …` → `fix` +
      `breaking-change`) via `actions/github-script`. Creates any missing label on demand and re-syncs when the title is edited. Uses `pull_request_target` so
      fork PRs get a writable token; safe because it never checks out or runs PR code.
    - `validate-workflows.yml` — **actionlint** (correctness) and **zizmor** (security) over `.github/workflows/`, since #383. It is the only gate that looks at
      the workflows themselves, and on its first run zizmor found a template injection in `release.yml`, a cache-poisoning path into it, and two workflow-level
      permission grants that belonged to a single job. zizmor blocks at `--min-severity medium`; suppressions live in `zizmor.yml` or as inline
      `# zizmor: ignore[…]` comments, each with a reason and a date. **`unpinned-uses` is downgraded to `ref-pin` on purpose** — the 57 tag-pinned actions are
      #443's work, and that suppression should be raised to `hash-pin` the day it lands.
    - `validate-docs.yml` — `scripts/format-markdown.sh check` over every `.md` file. It **checks and never writes**: a job that pushed a formatting commit back
      would need write access on every pull request including forks, which is far more than a formatter is worth, so a failure names the files and leaves the
      one-command fix to the author. It installs `events-frontend`'s dependencies for the pinned oxfmt rather than fetching a released one — versions disagree
      about Markdown, and a check run against "whatever is newest" would fail on files a contributor's pinned copy had just written. `package-lock.json` is in
      its path filters for that reason: an oxfmt bump can reformat every document here. **It is the one `validate-*` workflow that keeps a `paths:` filter on
      `pull_request`**, because it is not on the required list — see the note in the file, and delete the filter if it is ever made required.
    - `validate-infra.yml` — `tofu fmt -check`, `tofu init -backend=false` + `validate` across all three stacks in a matrix, and ShellCheck on the cloud-init
      scripts. Triggers only when `infra/**` changes. **It deliberately never runs `plan`**: that needs a Hetzner token, and per PLATFORM_SETUP.md §4 nothing
      outside the cluster holds a cluster or cloud credential. So this is a syntax and type gate, not a correctness one, and there is no drift detection.
    - `build-frontend.yml` builds the frontend image the same way, from the `dist/` its own `npm run build` step already produced.
    - `build-backend.yml` also **builds both container images for `linux/amd64` and `linux/arm64` and deliberately does not push them.** It runs on every pull
      request, including from forks, so publishing here would put an image built from unreviewed code into GHCR — that is `release.yml`'s job.
      `outputs: type=cacheonly` because a multi-platform image cannot be loaded into the local daemon, and dropping to one platform would leave arm64 — the
      architecture the Hetzner nodes run — unbuilt. **Both workflows build images on pull requests only**, since `release.yml` builds and pushes the same three
      images on every push to `main` and doing it twice per merge buys nothing.
    - `release.yml` — **the only workflow that publishes anything.** Builds the three images and packages the chart from one computed version, scans the images
      with Trivy before pushing, and pushes to GHCR: a snapshot on every push to `main`, a release on a `v*` tag. **It does not deploy** — Flux pulls and
      reconciles (#414), so a green run means the artifacts exist, not that they are live. Three things about it are deliberate and easy to "fix" wrongly:
      **no path filters** (the chart's `appVersion` is the default image tag for all three components, so every published chart needs all three image tags to
      exist — a path filter here publishes a chart referencing images that were never built); **no tests** (they gate the PR); and **two builds per image**,
      because a multi-platform image cannot be loaded into the local daemon and therefore cannot be scanned before it exists in a registry. **It publishes on an
      allowlist** — `push` events, or a `workflow_dispatch` whose `publish` input is ticked — never "everything except the dry run", so a trigger added later
      cannot quietly become a publishing one. And it **tests itself on pull requests that change it**, because the `workflow_dispatch` caveat above applies to
      it with teeth: the button does not exist until the change merges, and merging is what publishes.
    - `validate-chart.yml` — `helm lint --strict` and `helm template` | `flux schema validate` across every values file and every cluster's Flux resources, plus `deploy/scripts/render-assertions.sh`
      and ShellCheck. Triggers only when `deploy/**` changes. **Pins a Helm 3 client** even though local binaries are Helm 4, because Flux's helm-controller
      embeds the Helm 3 SDK and a chart that renders only under Helm 4 is one Flux cannot install. Like `validate-infra.yml` it reaches no cluster, so it is a
      syntax and shape gate; the assertions are the part that catches a chart which is well-formed and wrong.
- **Nine checks are REQUIRED on `main` and a pull request cannot merge without them** (#443, applied 2026-08-13). They were chosen for a specific reason: each
  runs on _every_ pull request, because GitHub keeps a required-but-skipped check `Pending` forever — _"a pull request that requires those checks to be
  successful will be blocked from merging"_ — so requiring a path-filtered check deadlocks every PR that does not touch its paths. #447 was a live example: it
  never ran `Lint & render` at all. The `pull_request` path filters on `validate-chart`, `validate-infra` and `validate-workflows` were removed so their checks
  always report; their combined cost is **54 seconds**.

    ```
    Lint & render · ShellCheck deploy scripts        validate-chart.yml
    Lint & audit workflows                           validate-workflows.yml
    Format & Validate (infra/bootstrap)              validate-infra.yml — a MATRIX, so one context per stack
    Format & Validate (infra/environments/staging)
    Format & Validate (infra/environments/production)
    ShellCheck cloud-init                            validate-infra.yml
    CodeQL · Dependency Review                       always run, unfiltered
    ```

    Two consequences worth knowing before changing any of this. **Adding a stack to `validate-infra`'s matrix creates a context that is not required** — the rule
    names each one exactly, so the matrix growing silently weakens the gate; add it to the ruleset in the same change. And **never add a `paths:` filter to the
    `pull_request` trigger of those three workflows** — it would block every unrelated pull request, and the failure looks like a hung check rather than a
    misconfiguration.

    **`Build & Test` is deliberately NOT required**, for both backend and frontend. They cost 382s and 597s, so requiring them means either +16½ minutes on
    every pull request including documentation-only ones, or a change-detection job whose semantics were unverified at the time. A red `Build & Test` is
    visible but not blocking. Revisit deliberately rather than by drift.

- **When CI misbehaves, check [githubstatus.com](https://www.githubstatus.com/) before debugging this repo.** Scriptable as
  `https://www.githubstatus.com/api/v2/summary.json`. A GitHub-side incident mimics repo-level bugs closely enough to send you hunting through trigger and path
  filters that are perfectly fine. Symptoms seen during the 2026-08-06 Actions outage:
    - **No run is created at all** for a PR — nothing to re-run, and `gh run rerun` cannot help. Trigger webhooks were throttled to ~15%. The tell-tale: a PR
      that gets no label either, since `label-pr.yml` was dropped by the same throttle.
    - **A run "fails" with zero steps executed**, annotated `The job was not acquired by Runner of type hosted even after multiple attempts`. That is runner
      starvation, _not_ a test failure — read the annotation before concluding the code is broken, and never merge past a red check without checking which of
      the two it is.
    - **Runs appear for branches deleted hours ago** as the throttled backlog replays. They are noise about the past, not signal about `main`.
    - Do not trust the **Webhooks** component on the status page: it read _Operational_ throughout, while the Actions incident text was the thing saying
      workflow-triggering webhooks were being dropped. Read the incident, not the component grid.
    - `gh run list --branch <name>` can look empty while `gh pr view --json statusCheckRollup` still shows CodeQL "Analyze" checks — CodeQL is GitHub's
      **default setup** (`event: dynamic`), which runs on a separate path from the workflow files here and so survives outages that stop everything else.
    - The `head_sha` filter on `/actions/runs` needs the **full 40-character SHA**; an abbreviated one silently returns `total_count: 0` and looks exactly like
      "no runs were created".
    - With CI unavailable, the honest fallback is a local `/verify` against the merged commit — and say in the PR that CI never ran, rather than implying a
      green build.
- **Dependabot** (`.github/dependabot.yml`) runs weekly across **four ecosystems**. Everything is grouped, because the alternative on a project this size is a
  pull-request queue nobody reads.
    - **`gradle`** (`/`) — grouped by library family: `kotlin`, `spring-boot`, `spring-modulith`, `testcontainers`, `jackson`, `springdoc`, `kotest`,
      `postgresql`, `flyway`, `reactor`, `detekt`, `owasp`, `gradle-plugins`.
    - **`npm`** (`/events-frontend`) — `versioning-strategy: increase`, which is what preserves the frontend's exact-pin convention: Dependabot rewrites the pin
      rather than widening it into a `^` range. Five families (`vue`, `linting`, `testing`, `typescript`, `tailwind`) keep toolchains that must move together in
      one PR, and `frontend-minor-patch` sweeps up the rest. **A dependency joins the first group it matches**, so the families must stay above the sweep in the
      file. Majors outside a family stay ungrouped deliberately — a Vite or Vue major deserves its own PR.
    - **`github-actions`** (`/`) — one group for all of them. `/` here does not mean the repository root in the usual sense; for this ecosystem Dependabot
      always reads `.github/workflows/`.
    - **`opentofu`** (`/infra/**`) — **not `terraform`**. They are separate ecosystems with separate registries, and the lock files there record providers as
      `registry.opentofu.org/…`, which the `terraform` updater would rewrite to `registry.terraform.io`. All four directories are grouped into one PR, since a
      single provider release otherwise opens four identical ones. Expect it to change **`.terraform.lock.hcl` and not `versions.tf`**: the `~> 1.68` constraint
      already permits 1.69, so the constraint is left alone until 2.0 while the lock file — which decides the version actually used — moves.
    - **`docker`** (`/events-bff`, `/events-importer`, `/events-frontend`) — the base image in every Dockerfile is pinned by tag **and** digest, and this is
      what keeps the digest from going stale. An unmaintained digest pin is a promise never to receive a security fix: the tag moves, the digest does not, and
      nothing says so. The three directories are grouped into one PR because the two backends pin the same base image.
      It updates the **tag** as well as the digest. The limit is not what it notices but what it can do: in #264 the Trivy gate found 10 fixable HIGH Alpine
      advisories in the frontend image, and Dependabot had already opened #437 proposing that exact bump — it was simply still open. **An open Dependabot PR is
      a live vulnerability**, and nothing in its title distinguishes one from a routine version bump. `release.yml`'s image scan is what turns an unmerged one
      into a failing build. **When bumping a base image, check the branch is still being rebuilt**, not just that a newer tag exists: `nginx 1.29` was a
      superseded mainline branch and had been shipping three-month-old Alpine packages.
    - **What no ecosystem covers: a tool version pinned as a plain string.** `HELM_VERSION` and `KUBECONFORM_VERSION` in `validate-chart.yml`, `HELM_VERSION`
      and `TRIVY_VERSION` in `release.yml`, and gitleaks' `rev:` in `.pre-commit-config.yaml` belong to no Dependabot ecosystem — `github-actions` updates
      `uses: azure/setup-helm@v5` and has nothing to say about the `version:` handed to it. They rot silently, and a scanner a year behind still reports
      success. `/update-dependencies` step 12 sweeps them. **`HELM_VERSION` is deliberately held at 3.x** — Flux's helm-controller embeds the Helm 3 SDK, so
      that pin is a constraint, not a lag.
    - `/update-dependencies` still exists and is not redundant: Dependabot proposes one bump at a time, while that skill does a deliberate sweep across both
      stacks and knows which Gradle versions are BOM-managed and must **not** be pinned.
- **Conventional Commits** — Commit messages follow the [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) spec. Reusable prompts are
  available at `.github/prompts/` for commit messages, squash commit messages, and code reviews.
- **Release notes** (`.github/release.yml`) — GitHub's automatically generated release notes group merged PRs into categories (🎪 New Event Sources, ✨ Features,
  🐛 Bug Fixes, …) by the labels `label-pr.yml` applies. Categories are matched **in order**, first match wins, so specific ones (`importer`, `dependencies`)
  precede general ones (`feat`, `build`). Label a PR `ignore-for-release` to keep it out of the notes entirely.
- **Opening a PR** — the `/open-pr` skill (`.github/prompts/open-pr.prompt.md`) runs the full ship flow: cut a branch, commit with a Conventional Commits
  message, push, and open the PR via `gh`. Invoking it is the explicit go-ahead for the commit/push that the "no unsolicited commits/pushes" rule above
  otherwise withholds.

### Constraints on automating GitHub itself

Learned the expensive way during the TODO.md → Issues migration (2026-08-09). Each of these looks like a bug in your script the first time you hit it.

- **Nothing running in CI can push to `main`.** The `main` ruleset requires every change to arrive by pull request, and its **only** bypass actor is
  `OrganizationAdmin`. The obvious workaround does not exist: GitHub refuses the Actions bot as a bypass actor with _"Actor GitHub Actions integration must be
  part of the ruleset source or owner organization"_ — a platform constraint, not a permissions problem, and the UI offers no such actor either. **Design any
  workflow that wants to write to the repo as generate-on-demand or open-a-PR, never as push-to-main.** A whole snapshot workflow was written, merged and
  deleted before this was discovered.
- **Pace bulk mutations.** GitHub's _secondary_ rate limit bites long before the documented hourly one. A `sleep 0.45` between calls carried 255 PR edits and
  146 issue creations with zero failures; without it, a few hundred back-to-back writes reliably trip it.
- **`gh issue create` and `gh issue edit` do not share a label flag.** Create takes `--label`; edit takes `--add-label` / `--remove-label`. One argument list
  for both works perfectly on creates and dies on the first update — invisible until something already exists. And an update must reconcile labels in _both_
  directions: `--add-label` alone lets a removed label survive forever with nothing reporting the drift.
- **Project view grouping and sorting cannot be set through the API.** `ProjectV2ViewConfigurationInput` exposes only `visibleFieldIds`. Names, layouts and
  filters are scriptable; the arrangement is a manual UI step. (Still outstanding for the Event Junkie board.)
- **gitleaks fires on `key:` with a high-entropy value.** A YAML front-matter field named `key` tripped the `generic-api-key` rule on 1 file out of 146 —
  intermittent by nature, since it depends on the value's entropy. Prefer `slug`, `id` or `name` for identifier fields. The existing `.gitleaks.toml` allowlist
  is for the scraper fixture tree, and widening it costs real scanning coverage.
- **A cautious first run pays for itself.** `--limit 5`, inspect, then continue. That is what turned the `gh issue edit` bug into a five-issue problem instead
  of a 146-issue one.

## The Backlog — GitHub Issues

**The backlog is [GitHub Issues](https://github.com/enorm-labs/event-junkie/issues), not a file.** `TODO.md` no longer exists.

**Read a generated snapshot; write through `gh`.** `scripts/generate-backlog-snapshot.sh` renders every open issue into `build/BACKLOG.md` — grouped by
milestone, with type, area, size and blocking state per row. Consulting it is then a local file read: cheap, grep-able, no network round trip per question.

**Regenerate it before you rely on it**, and never edit it. It is written into `build/`, which is gitignored, so it is never committed and never appears in a
diff — it is exactly as current as the last time someone ran the script, and its header carries the timestamp so you can tell.

```sh
scripts/generate-backlog-snapshot.sh                # refresh it first — one gh call
grep -i 'heimathafen' build/BACKLOG.md              # is this already tracked?
gh issue list --label importer --state open         # when you need live state
gh issue view 313                                   # the full body, including its Links footer
```

_(This was briefly a committed file refreshed by a workflow. That cannot work here: the `main` ruleset requires every change to arrive by pull request, only an
OrganizationAdmin may bypass it, and GitHub refuses the Actions bot as a bypass actor. The workflow failed on its first run and the committed copy went stale
within the hour — so the file moved to `build/` and the workflow was deleted.)_

**Filing something.** Use `/new-issue`, which checks for a duplicate first and picks the right form. By hand,
`.github/ISSUE_TEMPLATE/` has 🛠 Task, ✨ Feature, 🔍 Importer / data defect, ⚖️ Decision and 🧭 Epic. The importer-defect form is the one to reach for after a
smoke test or a data-quality audit — it asks the questions those findings need, including **whether the fix requires a `--full` re-seed**, which is usually the
difference between a one-hour change and a one-day one.

**Where a finding goes** — the same rule as before, with a new destination:

| Finding                                                                                           | Goes to                                                 |
| ------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| A defect with a known repair — we lose or mangle data the source _did_ publish                    | **An issue** (🔍 Importer / data defect)                |
| An accepted limitation — the venue never publishes it, or the parser makes a deliberate trade-off | **That scraper's KDoc**, next to the code it constrains |
| A choice that must be made before work can start                                                  | **An issue** (⚖️ Decision), labelled `needs-decision`   |

**The label and field split.** Intrinsic properties of the work are **labels** — `area:*`, `size:*`, plus `importer` and `documentation`. Planning state lives
in the **[project board](https://github.com/orgs/enorm-labs/projects/1)** as Status and Priority fields, because priority churns and label churn is noise. Issue
_type_ is a GitHub issue type (Task / Bug / Feature), not a label — do not add a `type:` label.

Three labels name _why_ something cannot start: `blocked` (another issue), `needs-decision` (a choice), `needs-deployment` (a live origin). **The last is not
neglected work** — it is work that cannot exist yet, and it is labelled so it stops reading as neglect.

**Milestones.** `v0.2 — Deployable` → `v0.3 — Launch-ready` → `v1.0 — Go-live` are the path to launch; `Phase 2/3/4` are post-launch buckets with no due date.
No milestone means unscheduled. Direction and the reasoning behind the phases stay in [docs/VISION_ROADMAP_IDEAS.md](docs/VISION_ROADMAP_IDEAS.md).

**Closing.** Put `Closes #NNN` in the **PR body**, on its own line. This repo allows only **Rebase and merge** — squash and merge commits are both disabled — so
commit messages are replayed onto `main` as written, and a closing keyword in one of them would work too. The PR body is still the right home: it is one line to
fix when the issue number changes, whereas the same line in a commit means rewriting history, and it survives the amending and rebasing a branch goes through
during review. Use `Closes` rather than `Fixes`/`Resolves`, one line per issue.

Give the PR the issue's milestone as well. Every closed PR here carries one — the 255 that predate the tracker were backfilled into `Phase 0 — Foundation` — and
a PR without one is the exception that makes the milestone view stop meaning anything.

## Key Files

| Purpose                                | Path                                                                                                      |
| -------------------------------------- | --------------------------------------------------------------------------------------------------------- |
| Root build config & shared versions    | `build.gradle.kts`                                                                                        |
| Plugin versions & module includes      | `settings.gradle.kts`                                                                                     |
| Gradle daemon JVM args                 | `gradle.properties`                                                                                       |
| Dev database (Postgres)                | `compose.yaml`                                                                                            |
| Detekt rule overrides                  | `detekt.yml`                                                                                              |
| OWASP CVE false-positive suppressions  | `owasp-suppressions.xml`                                                                                  |
| CI: backend build & test               | `.github/workflows/build-backend.yml`                                                                     |
| CI: frontend build & test              | `.github/workflows/build-frontend.yml`                                                                    |
| CI: dependency review (PR)             | `.github/workflows/dependency-review.yml`                                                                 |
| CI: dependency graph submission        | `.github/workflows/dependency-submission.yml`                                                             |
| CI: nightly OWASP scan                 | `.github/workflows/dependency-check-scheduled.yml`                                                        |
| CI: PR labelling                       | `.github/workflows/label-pr.yml`                                                                          |
| CI: OpenTofu fmt/validate + ShellCheck | `.github/workflows/validate-infra.yml`                                                                    |
| CI: workflow lint + security audit     | `.github/workflows/validate-workflows.yml`; suppressions in `zizmor.yml`                                  |
| CI: Helm lint/render/assertions        | `.github/workflows/validate-chart.yml`                                                                    |
| CI: Markdown formatting                | `.github/workflows/validate-docs.yml`                                                                     |
| CI: build, scan and publish to GHCR    | `.github/workflows/release.yml` — the only workflow that pushes anything; it does not deploy              |
| Version scheme (one number, 4 files)   | `scripts/version.sh`; `gradle.properties` is the source of truth — docs/DEVELOPMENT.md §Versions          |
| Snapshot versions must ORDER (#455)    | `scripts/version-test.sh` — asserted against Helm's own solver; a format check would not catch it         |
| Markdown formatting                    | `scripts/format-markdown.sh` + `.oxfmtrc.json` — Markdown only, and the scope is load-bearing             |
| Trivy waivers                          | `.trivyignore` — empty on purpose; an entry needs a reason and a date                                     |
| Infrastructure as code (OpenTofu)      | `infra/` — read `infra/AGENTS.md` first; `bootstrap/` is applied, `environments/` is not                  |
| Cloud-init for the Hetzner nodes       | `infra/modules/environment/cloud-init/`                                                                   |
| Helm chart (bff · importer · frontend) | `deploy/charts/event-junkie/` — read `deploy/AGENTS.md` first; exercised on k3d, never on a real cluster  |
| Backend container images               | `events-bff/Dockerfile`, `events-importer/Dockerfile` — no `RUN`, context is each module's `build/docker` |
| Frontend container image               | `events-frontend/Dockerfile` + `events-frontend/docker/nginx.conf` — nginx on 8080, context is the module |
| Chart render assertions                | `deploy/scripts/render-assertions.sh`                                                                     |
| Release notes categories               | `.github/release.yml`                                                                                     |
| Dependabot config                      | `.github/dependabot.yml`                                                                                  |
| Commit message prompt                  | `.github/prompts/commit-message.prompt.md`                                                                |
| Squash commit message prompt           | `.github/prompts/squash-commit-message.prompt.md`                                                         |
| Open PR prompt                         | `.github/prompts/open-pr.prompt.md`                                                                       |
| Code review prompt                     | `.github/prompts/code-review.prompt.md`                                                                   |
| Security report prompt                 | `.github/prompts/security-report.prompt.md`                                                               |
| Shared domain module marker            | `events-core/src/.../EventsCoreModule.kt`                                                                 |
| Domain data classes                    | `events-core/src/.../artist/`, `event/`, `genretag/`, `promoter/`, `venue/`                               |
| Price normalization utility            | `events-core/src/.../event/MoneyExtensions.kt`                                                            |
| Initial DB migration                   | `events-importer/src/main/resources/db/migration/V001__create_initial_schema.sql`                         |
| Global exception handler               | `events-importer/src/.../GlobalExceptionHandler.kt`                                                       |
| Slug generator utility                 | `events-importer/src/.../slug/SlugGenerator.kt`                                                           |
| Genre normalizer utility               | `events-importer/src/.../genretag/GenreNormalizer.kt`                                                     |
| Shared scraping utilities              | `events-importer/src/.../scraper/ScrapingExtensions.kt`                                                   |
| Shared date/time parsing               | `events-importer/src/.../scraper/DateParsingExtensions.kt`                                                |
| Event-type classification              | `events-importer/src/.../scraper/EventTypeMapping.kt`                                                     |
| Artist-name resolution                 | `events-importer/src/.../scraper/ArtistNameMapping.kt`                                                    |
| Event field-level mapping              | `events-importer/src/.../scraper/EventFieldMapping.kt`                                                    |
| WebFlux Pageable resolver config       | `events-importer/src/.../WebFluxConfiguration.kt`                                                         |
| Stable-sort Pageable resolver          | `events-importer/src/.../StableSortPageableArgumentResolver.kt` (duplicated in `events-bff`)              |
| Base integration test class            | `events-importer/src/test/.../BaseControllerTest.kt`                                                      |
| Full lifecycle integration test        | `events-importer/src/test/.../event/FullLifecycleIntegrationTest.kt`                                      |
| Testcontainers setup (BFF)             | `events-bff/src/test/.../PostgresTestcontainersConfiguration.kt`                                          |
| Testcontainers setup (importer)        | `events-importer/src/test/.../PostgresTestcontainersConfiguration.kt`                                     |
| Modularity verification (BFF)          | `events-bff/src/test/.../ModularityTests.kt`                                                              |
| Modularity verification (importer)     | `events-importer/src/test/.../ModularityTests.kt`                                                         |
| Modularity verification (core)         | `events-core/src/test/.../ModularityTests.kt`                                                             |
| ADR: Reactive stack                    | `docs/adr/ADR-001_REACTIVE_STACK.md`                                                                      |
| ADR: R2DBC query derivation limits     | `docs/adr/ADR-002_R2DBC_QUERY_DERIVATION.md`                                                              |
| ADR: Entity/domain separation          | `docs/adr/ADR-003_ENTITY_DOMAIN_SEPARATION.md`                                                            |
| ADR: Dedicated database schema         | `docs/adr/ADR-004_DEDICATED_DATABASE_SCHEMA.md`                                                           |
| ADR: Migrations owned by importer      | `docs/adr/ADR-005_MIGRATIONS_OWNED_BY_IMPORTER.md`                                                        |
| ADR: Spring Modulith                   | `docs/adr/ADR-006_SPRING_MODULITH.md`                                                                     |
| ADR: Web scraping strategy             | `docs/adr/ADR-007_WEB_SCRAPING_STRATEGY.md`                                                               |
| ADR: Import job scheduling             | `docs/adr/ADR-008_IMPORT_JOB_SCHEDULING.md`                                                               |
| ADR: Optimistic locking (event src)    | `docs/adr/ADR-009_OPTIMISTIC_LOCKING_EVENT_SOURCE.md`                                                     |
| ADR: Frontend styling framework        | `docs/adr/ADR-010_FRONTEND_STYLING_FRAMEWORK.md`                                                          |
| ADR: Event-calendar library            | `docs/adr/ADR-011_CALENDAR_LIBRARY.md`                                                                    |
| ADR: Cloud platform & hosting          | `docs/adr/ADR-012_CLOUD_PLATFORM.md`                                                                      |
| ADR: Localisation (English + German)   | `docs/adr/ADR-013_LOCALISATION.md`                                                                        |
| ADR: Rendering strategy (SPA/SSG/SSR)  | `docs/adr/ADR-014_RENDERING_STRATEGY.md`                                                                  |
| ADR: Observability stack               | `docs/adr/ADR-015_OBSERVABILITY_STACK.md`                                                                 |
| ADR: GitOps delivery (Flux, pull)      | `docs/adr/ADR-016_GITOPS_DELIVERY.md`                                                                     |
| ADR: JRE base image (Liberica/Alpine)  | `docs/adr/ADR-017_JRE_BASE_IMAGE.md`                                                                      |
| Plan: Hetzner + k3s setup, go-live     | `docs/PLATFORM_SETUP.md`                                                                                  |
| Releasing & deploying, end to end      | `docs/RELEASING.md` — the diagram; ADR-016 has the reasoning                                              |
| Bootstrapping a cluster, once          | `docs/CLUSTER_BOOTSTRAP.md` — ordered runbook, first run 2026-08-13; traps table at the bottom            |
| Connecting to a running cluster        | `docs/CLUSTER_ACCESS.md` — tunnel, kubeconfig, contexts, k9s. Read-only; nothing in it changes anything   |
| Flux resources (one dir per cluster)   | `deploy/clusters/` — read `deploy/AGENTS.md` first; the semver range is on the OCIRepository              |
| Plan: footer, legal pages, versioning  | `docs/LEGAL.md`                                                                                           |
| Backlog snapshot generator             | `scripts/generate-backlog-snapshot.sh` → `build/BACKLOG.md` (generated, not committed)                    |
| Issue board helper                     | `scripts/issue-board.sh` — Status and Priority are project fields, not labels                             |
| Frontend entry point                   | `events-frontend/src/main.ts`                                                                             |
| IntelliJ HTTP Client requests          | `http/importer/` (admin) and `http/bff/` (public read) `.http` files + shared `http/http-client.env.json` |
| Local dev environment control script   | `scripts/dev-env.sh` (start/stop the stack, seed sources, trigger imports, inspect + diff the data)       |
| Performance tests (k6)                 | `perf/` — `smoke.js` · `load.js` · `spike.js`, endpoints in `perf/lib/api.js`                             |
