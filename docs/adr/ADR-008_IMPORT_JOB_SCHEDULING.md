# ADR-008: Import Job Scheduling with @Scheduled

## Status

Accepted

**Amended: the retry backoff is capped, and a spent retry budget no longer removes a source from the schedule**
([#659](https://github.com/enorm-labs/event-junkie/issues/659)). Everything below about the tick, the table, the status
model and the alternatives is unaffected. Only the retry cadence and the OPEN state changed. The reasoning is in
§Circuit Breaker.

## Context

The event importer needs to periodically scrape ~40 venue websites to keep event data up to date. The scraping infrastructure (ADR-007) provides the pipeline —
`HtmlFetcher` → `EventImporter` → `EventImportService` — but everything is triggered manually via REST endpoints. We need automated scheduling with these
requirements:

1. Periodic imports per venue (different schedules: some daily, some weekly).
2. Visibility into job status: when was the last import? Was it successful? How many events?
3. Retry failed imports with backoff.
4. Enable/disable individual sources without redeployment.
5. Prevent overlapping imports of the same source.

Seven candidates were evaluated:

### Alternatives Considered

| Option                      | Verdict      | Key Issue                                                                                                                                                                                                                                                    |
| --------------------------- | ------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| **`@Scheduled` alone**      | ⚠️ Too bare  | Only supports global cron expressions, no per-source schedules, no persistence.                                                                                                                                                                              |
| **JobRunr**                 | ⚠️ Viable    | Requires a JDBC `DataSource` — incompatible with the R2DBC-only stack without adding `spring-jdbc`. Adds its own job tables that duplicate `event_source` metadata. A dual-DataSource workaround is feasible (see below) but not justified at current scale. |
| **Quartz**                  | ⚠️ Heavy     | Same JDBC requirement. Massive API surface for ~40 cron triggers. XML-heavy configuration heritage.                                                                                                                                                          |
| **Spring Batch**            | ❌ Wrong fit | Designed for chunk-oriented ETL (read→process→write in batches), not HTTP-scrape-and-upsert. Also JDBC-only for its job repository.                                                                                                                          |
| **Spring Cloud Data Flow**  | ❌ Overkill  | Kubernetes-native orchestration platform for microservice pipelines. Way too heavy for this use case.                                                                                                                                                        |
| **Spring Retry**            | ❌ Wrong fit | Designed for in-process method-level retries (immediate `@Retryable` calls with thread sleep). No coroutine support. Cannot persist retry state across restarts.                                                                                             |
| **Resilience4J**            | ❌ Wrong fit | Same in-process retry model. Useful for transient HTTP failures (e.g. retrying a 503 in `HtmlFetcher`), but doesn't fit DB-persisted, cross-tick scheduling backoff.                                                                                         |
| **Custom (`event_source`)** | ✅ Best fit  | Leverages the existing `event_source` table, zero new dependencies, fully coroutine-native.                                                                                                                                                                  |

## Decision

Use a **custom scheduling approach** combining Spring's `@Scheduled` with the existing `event_source` table.

### Design

```
┌───────────────────────────────┐
│  @Scheduled(fixedDelay = 60s) │  ← Single tick every 60 seconds
│  ScheduledImportService       │
└──────────────┬────────────────┘
               │ queries event_source for due sources
               ▼
┌───────────────────────────────┐
│  EventImportService           │  ← Already built (ADR-007)
│  importFromSource(source)     │
└───────────────────────────────┘
```

The `event_source` table already tracks most job metadata (status, last run, error, event count, enable/disable). Three columns are added to support scheduling:

- **`import_interval_minutes`** — how often this source should be imported (e.g. `1440` = daily). Uses a simple integer interval rather than cron expressions
  for clarity and ease of querying.
- **`retry_count`** — number of consecutive failures (reset to 0 on success).
- **`max_retries`** — maximum retry attempts before giving up (defaults to 3).

A single `@Scheduled` method ("tick") runs every 60 seconds and:

1. Queries for **enabled sources** that are **due for import**. A source is due when `last_import_at` is older than
   `import_interval_minutes`, or `null`.
2. Skips sources with `status = RUNNING` to prevent overlapping imports.
3. Skips sources with `status = MISCONFIGURED`. Their configuration errors are permanent — an unknown source type, no
   importer registered — and need a person.
4. Includes **failed sources**. While `retry_count < max_retries` they use backoff — `interval × 2^retryCount`,
   **capped at six hours**. Once the budget is spent they return to their own `import_interval_minutes`. They are
   never dropped from the query.
5. Resets stuck `RUNNING` sources to `FAILED` if they've been running for more than 30 minutes (staleness guard).
6. Delegates each due source to `EventImportService.importFromSource()`.

> **Why step 4 caps the wait and keeps a spent source in the query**
> ([#659](https://github.com/enorm-labs/event-junkie/issues/659)).
>
> **An uncapped backoff assumes an interval measured in minutes.** `import_interval_minutes` defaults to 1440, so the
> first retry waited 48 hours, the second 96 and the third 192. A failed source was attempted _less_ often than a
> healthy one, which inverts the point of a retry. `loge` failed on 2026-08-21 11:54 and was next attempted on
> 2026-08-23 11:55. That is the whole of the 47-hour gap #659 reports, and nothing was stuck. The cap makes the
> guarantee independent of the source's own schedule. A failure is retried within six hours whatever its interval,
> which fits all three of a daily source's retries inside the day it failed. A sub-cap interval is unaffected: an
> hourly source still waits 2 h, then 4 h.
>
> **Excluding a spent source made exhaustion invisible.** Capping the wait alone was a regression rather
> than a fix. A broken daily source spent its budget in 18 hours instead of 14 days, and then vanished from the
> schedule. See §Circuit Breaker for why absence is the one state this system cannot afford.

### Scheduling is enabled by default

The `@Scheduled` tick is active by default but can be disabled via configuration (`app.scheduling.enabled: false`) for test environments or development.

### Future: Multi-Instance Locking

When scaling to multiple instances, a `SELECT ... FOR UPDATE SKIP LOCKED` clause can be added to the due-sources query. This provides distributed locking
without any new dependencies — PostgreSQL handles it natively. This is a natural upgrade path from this design.

### Concurrency Model

Due sources within a tick are imported **concurrently**, bounded by a configurable concurrency limit (`app.import.max-concurrency`, default: 4) enforced via a
coroutine `Semaphore`:

- `fixedDelay` (not `fixedRate`) ensures the next tick starts 60 seconds **after** the previous tick completes, so ticks never overlap.
- Inside each tick, `EventImportService.importConcurrently()` launches one coroutine per due source using
  `coroutineScope { sources.map { async { semaphore.withPermit { importFromSource(it) } } }.awaitAll() }`. The semaphore limits how many sources execute
  simultaneously to avoid excessive database and network pressure.
- Per-host HTTP politeness is enforced by `PerHostThrottlingFilter` (ADR-007), which serializes requests to the same host while allowing different hosts to
  proceed concurrently.

Concurrent execution is safe because:

- The **artist cache** in `AssociationSyncService` is local to each `importFromSource` call (not shared across sources).
- **Concurrent artist creation** falls back on a `DataIntegrityViolationException`. If two imports try to create the
  same artist at once, the unique constraint on `artist.slug` catches the duplicate and the loser does a lookup.
- Each source's **upsert runs in its own transaction** via `TransactionalOperator.executeAndAwait`.
- **Status updates** (markSuccess/markFailed) use `saveWithVersionConflictRetry` off optimistic locking conflicts.
- **One run per source** is guaranteed by an atomic claim: a run opens by issuing
  `UPDATE … SET status = 'RUNNING' … WHERE id = :id AND version = :expectedVersion AND status <> 'RUNNING'` (`EventSourceRepository.claimForImport`) and imports
  only when it updated the row. This matters because `status = 'RUNNING'` is not yet set while a request waits for a
  concurrency permit. A manual trigger can request a source that _still_ looks due to a scheduler tick. Without the
  claim, both runs scrape and upsert the same events, and collide on the `event_slug_key` unique index. The `version`
  half of the guard extends that from overlapping runs to _consecutive_ ones. A tick that waited out the whole of
  another run's import would otherwise find the status back at SUCCESS and re-scrape the venue. The scheduler skips a
  source another run holds, or that another run imported since this one read it. It does not fail it. See ADR-009 for
  why optimistic locking cannot serve this purpose on its own.

The manual "import all" endpoint (`POST /api/admin/event-sources/import`) uses the same
`importConcurrently()` method, benefiting from the same bounded concurrency.

### Manual Triggers Run Fire-and-Forget

Both manual import endpoints are **asynchronous**: `POST /api/admin/event-sources/import` and
`POST /api/admin/event-sources/{slug}/import`. They launch the import on an application-scoped coroutine and return
`202 Accepted` at once, rather than blocking the request until the import finishes.

**Why:** a heavy two-page importer makes one throttled HTTP fetch per event (see ADR-007's per-host politeness
throttling). Badehaus, for example, fetches ~90 detail pages and runs for over a minute. Run that inline in the request
and the caller's HTTP read timeout can elapse first — the IntelliJ HTTP Client and `ijhttp` default to 60s. When the
client disconnects, WebFlux cancels the request-scoped coroutine. That aborts the import **mid-transaction** and leaves
the source stuck in `RUNNING` with nothing persisted. Decoupling the import from the request removes that failure mode,
and lets triggers scale to any number of sources.

**How:** `ImportJobLauncher` owns a `CoroutineScope(SupervisorJob() + ioDispatcher)`. The `SupervisorJob` keeps one
failing import from cancelling the scope or its sibling imports. The scope is application-scoped, so it outlives the
request, and `DisposableBean` cancels it on shutdown. `{slug}/import` still resolves the source synchronously before
launching, so an unknown slug returns `404` rather than failing silently in the background. Progress and outcome go on
the `event_source` row (`RUNNING → SUCCESS/FAILED`) as usual. Clients **poll**
`GET /api/admin/event-sources[/{slug}]` to observe them, instead of reading a synchronous result that is no longer
there. This is a natural fit for a future imports-status dashboard.

The **scheduled** path (`ScheduledImportService.tick()`) was already request-independent. It runs on the `@Scheduled`
executor and is bounded only by the `staleness-timeout`, so this failure mode never reached it. The change brings the
manual path in line with it.

**Known edge case — REST trigger during a scheduled tick.** A manual `POST /api/admin/event-sources/{slug}/import`
could overlap with the scheduler processing the same source. Both would read the source as IDLE, set it to RUNNING, and
upsert the same events. This is not harmful because:

- Event upserts are **idempotent by `sourceId`** — last write wins, end state is correct.
- Artist auto-creation is guarded by a `slug` UNIQUE constraint — duplicate attempts fail with a
  `DataIntegrityViolationException` (mapped to 409 by the global exception handler).

The `status = 'RUNNING'` exclusion in the due-sources query acts as a soft guard but is not a true lock (read-then-write without atomicity). For the current
single-instance deployment, this is acceptable. For multi-instance deployments, `SELECT ... FOR UPDATE SKIP LOCKED` would provide proper distributed locking
(see above).

### Circuit Breaker — Considered and Deferred

A formal circuit breaker (Resilience4J, for example) would handle an unavailable venue website. It is deferred,
because the existing retry mechanism already gives equivalent protection at the scheduling layer:

| Circuit breaker concept | Existing equivalent                                                                                  |
| ----------------------- | ---------------------------------------------------------------------------------------------------- |
| CLOSED (normal)         | `status = SUCCESS`, `retryCount = 0` — imports proceed normally                                      |
| OPEN (blocked)          | `retryCount >= maxRetries` — the shortened retry cadence ends; the source drops back to its interval |
| HALF-OPEN (probe)       | The next scheduled tick after that interval, or a manual `POST /event-sources/{slug}/retry`          |
| Cool-down period        | Exponential backoff (`interval × 2^retryCount`), capped at six hours                                 |
| Permanent fault         | `status = MISCONFIGURED` — config errors (unknown source type, no importer) skip retries             |

The difference from a classic circuit breaker is what OPEN costs. A formal one auto-transitions OPEN → HALF-OPEN after
a timeout. Here OPEN is not a separate state at all, only the end of the shortened cadence. The source keeps being
probed on its normal schedule.

**Why OPEN does not exclude the source** ([#659](https://github.com/enorm-labs/event-junkie/issues/659)). Excluding it
outright is the obvious reading: a persistently failing scraper needs a code change, not more attempts. The reasoning
is sound and the mechanism is not. A source that stops being attempted stops producing failures, and a venue with no
recent failures looks exactly like a venue with nothing to import. The observability around it reads `event_source`
rather than the scheduler — `#618`'s `has_succeeded`, `#700`'s per-source gauge, the `ej-importer-stale` rule. Silence
there therefore reads as health. Probing once a day and failing visibly costs one HTTP request, and
produces the signal those rules exist to catch. `enabled = false` remains the way to stop a source deliberately, and it
is still honoured.

Resilience4J could add value at a **different layer**: in-process HTTP retries inside `HtmlFetcher` for a transient
failure (503, a timeout) within one import attempt. That is a complementary concern, and can be added on its own.

## Consequences

- **Positive**: zero new dependencies, and fully coroutine-native. Per-source scheduling with different intervals,
  retry with exponential backoff, and staleness detection for a stuck import. All job metadata lives in one table
  (`event_source`), which makes a dashboard or an API easy to build on top.
- **Negative**: not as feature-rich as JobRunr's built-in dashboard, though we are building our own Vue frontend. No
  cron expressions, only fixed intervals — enough for venue scraping, where "every N hours" is the typical pattern.
- More sophisticated scheduling would make JobRunr the natural upgrade: time-of-day constraints, complex cron
  patterns, or distributed job processing. That needs a JDBC DataSource alongside R2DBC (see below).

### JobRunr Dual-DataSource Feasibility

JobRunr requires JDBC and has [no R2DBC support](https://github.com/jobrunr/jobrunr/issues/257). A **dual-DataSource**
approach is technically feasible. Configure a JDBC `DataSource` alongside the existing R2DBC connection, both pointing
at the same PostgreSQL instance. JobRunr supports that through `jobrunr.database.datasource`, which targets a specific
named `DataSource` bean.

This was evaluated and **deferred** because the costs outweigh the benefits at current scale:

| Cost                       | Detail                                                                                                   |
| -------------------------- | -------------------------------------------------------------------------------------------------------- |
| New dependencies           | `spring-boot-starter-jdbc`, PostgreSQL JDBC driver, `jobrunr-spring-boot-4-starter`                      |
| Duplicate connection pools | HikariCP (JDBC) + R2DBC pool to the same database — doubles connection resource usage                    |
| Duplicate job metadata     | JobRunr creates its own tables (`jobrunr_jobs`, `jobrunr_recurring_jobs`, etc.) alongside `event_source` |
| Architecture inconsistency | Breaks the "reactive stack throughout" principle ([ADR-001](ADR-001_REACTIVE_STACK.md))                  |
| Configuration complexity   | Two DataSource beans with custom qualifiers, shared credentials                                          |

**When to reconsider:** distributed job processing across multiple instances. Cron-expression scheduling ("scrape only
at 3 AM"). Job queues with priorities. A built-in dashboard that nobody has to write in the Vue frontend.

## References

- [Spring `@Scheduled` reference](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
- [JobRunr](https://www.jobrunr.io/) — considered but rejected for now
- [ADR-007: Web Scraping Strategy](ADR-007_WEB_SCRAPING_STRATEGY.md)
- [`event_source` table](../../events-importer/src/main/resources/db/migration/V001__create_initial_schema.sql)
