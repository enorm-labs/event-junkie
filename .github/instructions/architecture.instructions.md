---
applyTo: "events-core/**,events-bff/**,events-importer/**"
paths:
    - "events-core/**"
    - "events-bff/**"
    - "events-importer/**"
---

# Backend Architecture Decisions

The decisions the three backend modules are built on, each with the failure mode that is why it is written down. What the code already says — file layouts,
which helper lives where — is not repeated; `grep` answers that.

- **Reactive stack throughout**: WebFlux + R2DBC + coroutines. No blocking API (`spring-web`, JDBC) in a request path; repositories extend
  `CoroutineCrudRepository`. ADR-001.
- **R2DBC query derivation is limited**: `findBy*`, `countBy*`, `existsBy*`, `deleteBy*` derive; **`updateBy*` does not** — `@Modifying` + `@Query`. Raw
  `@Query` SQL bypasses `@Table` and the `NamingStrategy`, so it must carry the schema — **as `$EVENTS_SCHEMA`, never a literal** (a `const val` works inside
  an annotation, #540); `SchemaConfigurationTest` fails the build on a literal. ADR-002.
- **The schema is `events`, named by `EVENTS_SCHEMA` in `events-core`, not by the property.** `spring.r2dbc.properties.schema` and `spring.flyway.schemas`
  stay because only they create the schema and set `search_path`, but they are checked against the constant and the context fails to start on divergence
  (ADR-004). The `NamingStrategy` bean in `R2dbcConfiguration` qualifies every derived query; without it Spring emits `INSERT INTO "venue"` against `public`.
- **Migrations live in `events-importer` only** (ADR-005), `V001__…` closed, every change its own file, unqualified (Flyway sets `search_path`; a qualified
  migration pins itself to a schema the configuration no longer controls). **Editing an applied migration is a `FlywayValidateException: Migration checksum
mismatch`** — the pod never goes Ready and `remediateLastFailure` rolls the release back, presenting as "the deploy reverted" two layers from the cause.
    - **A `slug` literal in a data migration is checked, because a wrong one fails open**: `UPDATE … WHERE slug = 'mispelt'` updates nothing and Flyway records
      it applied. `MigrationSlugTest` asserts every venue slug literal under `db/migration/` is one `SlugGenerator` derives from `dev-seed.http`; a renamed or
      removed venue goes into `RETIRED_VENUE_SLUGS` with a reason, and a second assertion deletes the entry once stale. Never `@Disabled` (#987).
    - **Promoter merges** name slugs no seed creates, so `MergeDuplicatePromotersMigrationTest` runs each against planted rows and asserts a survivor's slug is
      `slugify(name)` (V025 repaired that). Step 1 picks the smallest loser **that exists** (V023 left `tip-berlin` behind on production, #1343); step 3
      inserts the survivor's links rather than updating the losers', or two losers on one event trip `UNIQUE (event_id, promoter_id)` (that failed `v0.13.1`).
      Copy V031's shape.
    - **A migration ships with the fixture row that exercises it** (#272). `fixtures/events.sql` is the shared dataset, and `FixtureTest` in `events-bff`
      loads it and asserts that every column of `event`, `venue` and `artist` is set on some row. A NOT NULL column or a new CHECK breaks the load and names
      itself; a **nullable** column loads and covers nothing, so that test fails until the fixture sets it or `WAIVED` carries a reason. Every `EventStatus`
      and `ArtistRole` value is asserted present for the same reason.
- **Spring Modulith** (ADR-006): each direct sub-package under `de.norm.events` is a module, declared by a `*Module.kt` marker with `allowedDependencies`;
  `ModularityTests` in all three modules verify it. Domain classes in `events-core` carry no Spring Data and no Swagger annotations; entities (`*Entity.kt`)
  convert with `toDomain()` / `fromDomain()`; controllers take `*Request` and return `*Response`, never a domain object (`EventResponse.fromEntity()` is the
  exception, because the aggregate resolves associations at the entity level). ADR-003.
- **Changing the BFF's public API means regenerating the frontend's types in the same PR** — `events-frontend/src/api/schema.d.ts` is generated from
  `/v3/api-docs` and nothing checks it is current. `cd events-frontend && npm run generate:api` with the BFF running; the traps are in
  [events-frontend/AGENTS.md](../../events-frontend/AGENTS.md) § API Communication.
- **Metrics** (`micrometer-registry-prometheus`, `health,info,prometheus` exposed, #415), four decisions that fail silently if reversed:
    - **Meter names are an interface.** Dashboards and alert rules are written against the exact strings in `ImporterMetrics` and `BffMetrics`, and the tests
      assert the literals rather than the constants, so a rename fails the test instead of a panel.
    - **Tag values are constants, never text from a venue's page** — a free-text tag is unbounded cardinality. `scrapeFailureReason()` enforces it; a test
      asserts no exception message reaches a tag.
    - **Gauges are refreshed on a schedule, not by a supplier.** A supplier that queried the database would block a Netty thread at scrape time;
      `MetricsRefreshService` writes atomics, stale by `app.metrics.refresh-interval-ms` (60s).
    - **`@AutoConfigureMetrics` on any test that hits `/actuator/prometheus`**, or it 404s like a wrong exposure list. `src/test/resources/application.yaml`
      **shadows** main in both modules, so an actuator property is repeated there; `MetricsExposureConfigTest` asserts the two lists match.
- **Logging**: kotlin-logging, `private val logger = KotlinLogging.logger {}`, lambda syntax. The BFF's `RequestLoggingFilter` writes one INFO access line per
  request (WebFlux does not). The `local` profile mirrors the console to `<module>/build/dev-env/<service>.log`; `dev-env.sh` redirects stdout itself and
  needs no profile. How a line is written is [logging.instructions.md](logging.instructions.md).
- **Errors**: `GlobalExceptionHandler` maps `*NotFoundException` → 404, `DataIntegrityViolationException` → 409, `WebExchangeBindException` → 400 with field
  details, `IllegalArgumentException` → 500 (an unknown enum value from a manual edit), as RFC 9457 `ProblemDetail`. Request DTOs carry `@field:NotBlank` /
  `@field:NotNull` / `@field:Size`, nested ones `@field:Valid`, controllers `@Valid`.
- **Pagination**: `Pageable` from the query string, `@PageableDefault` per controller, `findAllBy(pageable): Flow<Entity>`. The event list bulk-loads
  associations in three extra queries. Two traps:
    - **Both APIs answer a list in the same `PageResponse` envelope**, and the importer's did not until #810 — a bare array cannot say it was cut short, and a
      script wrote 20 of 86 sources reporting `Wrote 20 of 20.` The type is duplicated per module on purpose (`events-core` has no web dependency), so change
      both together, and a client compares `totalElements`.
    - **`StableSortPageableArgumentResolver`** (registered by `WebFluxConfiguration` in **both** modules, duplicated for the same reason) appends `id` as the
      final sort key, because every list sorts on a non-unique column and `LIMIT`/`OFFSET` over ties can show a row twice and skip another. **Do not move it
      to `@PageableDefault`** — that applies only when the request carries no `sort`, and the SPA always sends one. `EventSearchRepository` builds its own
      `ORDER BY … e.id ASC` and allowlists sort properties.
- **Read responses are cached in the BFF at the controller layer** (#269): `ResponseCache` (Caffeine, one shared item budget) for `app.api.cache.ttl-seconds`,
  and `CacheControlFilter` sends the same lifetime. Invalidation is the TTL and nothing else (the importer is another pod, the BFF has two replicas). Not
  `@Cacheable` — it works on suspend functions, but gives a cache per method and no shared bound, and a calendar response holds 92 days where a detail holds
  one. In the controller, not the service, so the loader runs in the caller's coroutine. Keys are a data class per endpoint, so `/events/{slug}` and
  `/venues/{slug}` cannot answer each other; `ResponseCachingIntegrationTest` asserts it.
- **Slugs are server-side** (`SlugGenerator`, the `slug` module, never accepted in a request). Slugify's NFD stripping deletes a letter that does not decompose,
  so `NON_DECOMPOSING_LATIN` maps `ø æ ð þ ł đ ı ß œ` to their base form (`ø`→`o` beside `ö`→`o`; `æ`, `œ`, `ß`, `þ` to their two-letter romanisation).
  Without it `Kėkė Søl` → `keke-sl` and `Søl`/`Sæl` collide. **Do not switch to slugify's `locale()` bundles** — `no` rewrites `å`→`aa`, `de` turns `ö`→`oe`,
  changing slugs that are already correct. **Changing the map is a data migration in disguise**: `AssociationSyncService` resolves artists and promoters by
  slug, so an old spelling is missed and re-inserted as a duplicate.
- **Money is scale 2** (`normalizeMoneyScale()` in `events-core`, applied where prices enter `EventEntity`), because `BigDecimal.equals()` is scale-sensitive
  and `10.0 != 10.00` would defeat the scraper's change detection.
- **Genre tags**: `genre` on an event is the raw text for display; `genre_tag` + `event_genre_tag` hold the normalised many-to-many. `GenreNormalizer` splits
  on `,`, `//`, `&`, `/`, strips noise suffixes, maps synonyms (`Hip-Hop`/`Rap` → `Hip Hop`), keeps unknowns in title case; shared by `EventService` and
  `AssociationSyncService`. No manual tag CRUD.
- **Web scraping** (ADR-007), the `scraper` module: Jsoup, a throttled `WebClient` (`PerHostThrottlingFilter`, `app.scraper.polite-delay-millis`, 200ms per
  host, transparent to every `HtmlFetcher` and `ApiClient` call), conditional requests by ETag / Last-Modified. `EventSource` is the enum registry; each
  value's KDoc is **one line about the venue**, and everything else about a source — platform, pages read, traps, accepted limitations — lives on that
  venue's sub-package under `scraper/<venue>/`, the single home. A repairable defect is an issue (🔍 Importer / data defect), not KDoc.
    - `EventImportService` orchestrates (RUNNING → SUCCESS/FAILED/MISCONFIGURED, concurrent under `app.import.max-concurrency`, 4) and delegates to
      `EventUpsertService` (dedupe, change detection, upsert, stale cleanup) and `AssociationSyncService` (artists and promoters by slug, created on
      `DataIntegrityViolationException` for concurrent safety, join tables diffed). Sources are rows created through `POST /api/admin/event-sources`, never
      Flyway.
    - **Shapes**: `AbstractTwoPageWebsiteImporter` for overview → detail (the common case; `grep -rl 'AbstractTwoPageWebsiteImporter('` is the list, and
      subclasses implement only `scrapeOverview`, `scrapeDetail`, `fillGapsFromOverview`); `EventImporter` directly for single-page HTML, for JSON feeds via
      `ApiClient` with one pure `*ApiScraper.kt` (**prefer a JSON source over HTML when one exists**), for a list whose rows share a detail page (fetch each
      distinct URL once — Bar jeder Vernunft resolves 28 cards to 2 pages), and for an undated weekly programme expanded over a rolling horizon with
      conditional requests off (Havanna; **never for a venue that publishes dates**). The shared helpers — `ScrapingExtensions.kt`, `DateParsingExtensions.kt`,
      `EventTypeMapping.kt`, `ArtistNameMapping.kt`, `EventFieldMapping.kt` — are used, not reimplemented.
- **Scheduled imports** (ADR-008): one `@Scheduled(fixedDelay = 60s)` tick finds due sources (`import_interval_minutes`, default 1440) and imports them
  concurrently. Retries back off exponentially **capped at six hours** — doubling a daily interval produced a retry slower than the healthy cadence (#659) —
  and a source that spends its budget returns to its interval, never dropped, because a source not attempted is indistinguishable from one with nothing to
  import. RUNNING for >30 min resets to FAILED. `app.scheduling.enabled: false` in tests.
- **OWASP Dependency-Check** (`dependencyCheckAggregate`, fails on CVSS ≥ 7, suppressions in `owasp-suppressions.xml`):
    - **`scanProjects` stays unset.** The plugin matches `project.path` (`:events-core`), not `project.name`, so a name list matches nothing and the report reads
      `Dependencies Scanned: 0` while the CVSS gate passes trivially — live here until 2026-08-05. **A green OWASP run is not evidence the scan looked at
      anything**; read `Dependencies Scanned` (baseline ~208).
    - **`skipConfigurations` excludes the build-tool classpaths** (`detekt`, `detektPlugins`, `ktlint*`, the compiler-plugin ones), which carry old Kotlin and
      logging copies that never ship. Add nothing that ships. Matching is `contains` with no globbing, so a renamed tool configuration silently rejoins the scan.
    - **Suppression `packageUrl` regexes fail open when scoped wrong** — `org.jetbrains.(kotlin|kotlinx)/` missed `org.jetbrains.intellij.deps.kotlinx`. Check a
      pattern against the real package URLs in the HTML report.
