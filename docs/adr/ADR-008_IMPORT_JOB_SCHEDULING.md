# ADR-008: Import Job Scheduling with @Scheduled

## Status

Accepted

**Amended: the retry backoff is capped, and a spent retry budget no longer removes a source from the schedule**
([#659](https://github.com/enorm-labs/event-junkie/issues/659)). Everything below about the tick, the table, the status model and the alternatives is
unaffected; only the retry cadence and the OPEN state changed. The reasoning is in §Circuit Breaker.

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

1. Queries for **enabled sources** that are **due for import** — where `last_import_at` is older than
   `import_interval_minutes` ago (or `null` for never-imported sources).
2. Skips sources with `status = RUNNING` to prevent overlapping imports.
3. Skips sources with `status = MISCONFIGURED` — these have permanent configuration errors (e.g. unknown source type, no importer registered) that require
   manual intervention.
4. Includes **failed sources**. While `retry_count < max_retries` they use backoff — `interval × 2^retryCount`, **capped at six hours**. Once the budget is
   spent they return to their own `import_interval_minutes`; they are never dropped from the query.
5. Resets stuck `RUNNING` sources to `FAILED` if they've been running for more than 30 minutes (staleness guard).
6. Delegates each due source to `EventImportService.importFromSource()`.

> **Amended 2026-08-24 ([#659](https://github.com/enorm-labs/event-junkie/issues/659)). Step 4 originally read "includes failed sources if
> `retry_count < max_retries`, applying exponential backoff (`interval × 2^retryCount`)", and both halves of that were wrong in the same direction.**
>
> **The backoff assumed an interval measured in minutes.** `import_interval_minutes` defaults to 1440, so the first retry waited 48 hours, the second 96 and
> the third 192 — a failed source was attempted _less_ often than a healthy one, which inverts the point of a retry. `loge` failed on 2026-08-21 11:54 and was
> next attempted on 2026-08-23 11:55; that is the whole of the 47-hour gap #659 reports, and nothing was stuck. The cap makes the guarantee independent of the
> source's own schedule: a failure is retried within six hours whatever its interval, which fits all three of a daily source's retries inside the day it
> failed. Sub-cap intervals are unaffected — an hourly source still waits 2 h, then 4 h.
>
> **And the exclusion made exhaustion invisible.** Capping the wait without touching the query would have been a regression, not a fix: a broken daily source
> spent its budget in 18 hours instead of 14 days and then vanished from the schedule. See §Circuit Breaker for why absence is the one state this system cannot
> afford.

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
- **Concurrent artist creation** is handled via a `DataIntegrityViolationException` fallback — if two imports try to create the same artist simultaneously, the
  unique constraint on `artist.slug` catches the duplicate and the loser falls back to a lookup.
- Each source's **upsert runs in its own transaction** via `TransactionalOperator.executeAndAwait`.
- **Status updates** (markSuccess/markFailed) use `saveWithVersionConflictRetry` off optimistic locking conflicts.
- **One run per source** is guaranteed by an atomic claim: a run opens by issuing
  `UPDATE … SET status = 'RUNNING' … WHERE id = :id AND version = :expectedVersion AND status <> 'RUNNING'` (`EventSourceRepository.claimForImport`) and imports
  only when it updated the row. This matters because `status = 'RUNNING'` is not yet set while a request waits for a concurrency permit, so a source can be
  requested by a manual trigger and _still_ look due to a scheduler tick; without the claim both runs scrape and upsert the same events and collide on the
  `event_slug_key` unique index. The `version` half of the guard extends that from overlapping runs to _consecutive_ ones: a tick that waited out the whole of
  another run's import would otherwise find the status back at SUCCESS and re-scrape the venue. A source another run holds, or that has been imported since this
  run read it, is skipped, not failed. See ADR-009 for why optimistic locking cannot serve this purpose on its own.

The manual "import all" endpoint (`POST /api/admin/event-sources/import`) uses the same
`importConcurrently()` method, benefiting from the same bounded concurrency.

### Manual Triggers Run Fire-and-Forget

Both manual import endpoints — `POST /api/admin/event-sources/import` and
`POST /api/admin/event-sources/{slug}/import` — are **asynchronous**: they launch the import on an application-scoped coroutine and return `202 Accepted`
immediately, rather than blocking the request until the import finishes.

**Why:** a heavy two-page importer makes one throttled HTTP fetch per event (see ADR-007's per-host politeness throttling). Badehaus, for example, fetches ~90
detail pages and runs for over a minute. Running that inline in the request means the caller's HTTP read timeout (the IntelliJ HTTP Client /
`ijhttp` default is 60s) can elapse first; when the client disconnects, WebFlux cancels the request-scoped coroutine, which aborts the import
**mid-transaction** and leaves the source stuck in
`RUNNING` with nothing persisted. Decoupling the import from the request removes that failure mode and lets triggers scale to any number of sources.

**How:** `ImportJobLauncher` owns a `CoroutineScope(SupervisorJob() + ioDispatcher)` — a `SupervisorJob`
so one failing import never cancels the scope or sibling imports, and application-scoped so it outlives the request (cancelled on shutdown via
`DisposableBean`). `{slug}/import` still resolves the source synchronously before launching, so an unknown slug returns `404` rather than failing silently in
the background. Progress and outcome are recorded on the `event_source` row (`RUNNING → SUCCESS/FAILED`) as usual, so clients **poll**
`GET /api/admin/event-sources[/{slug}]` to observe them instead of reading the (now absent) synchronous result. This is a natural fit for a future
imports-status dashboard.

The **scheduled** path (`ScheduledImportService.tick()`) was already request-independent — it runs on the `@Scheduled` executor and is bounded only by the
`staleness-timeout` — so it was never affected by this failure mode; this change brings the manual path in line with it.

**Known edge case — REST trigger during a scheduled tick:** A manual `POST /api/admin/event-sources/{slug}/import`
could overlap with the scheduler processing the same source. Both would read the source as IDLE, both would set it to RUNNING, and both would upsert the same
events. This is not harmful because:

- Event upserts are **idempotent by `sourceId`** — last write wins, end state is correct.
- Artist auto-creation is guarded by a `slug` UNIQUE constraint — duplicate attempts fail with a
  `DataIntegrityViolationException` (mapped to 409 by the global exception handler).

The `status = 'RUNNING'` exclusion in the due-sources query acts as a soft guard but is not a true lock (read-then-write without atomicity). For the current
single-instance deployment, this is acceptable. For multi-instance deployments, `SELECT ... FOR UPDATE SKIP LOCKED` would provide proper distributed locking
(see above).

### Circuit Breaker — Considered and Deferred

A formal circuit breaker (e.g. Resilience4J) was considered for handling unavailable venue websites but deferred because the existing retry mechanism already
provides equivalent protection at the scheduling layer:

| Circuit breaker concept | Existing equivalent                                                                                  |
| ----------------------- | ---------------------------------------------------------------------------------------------------- |
| CLOSED (normal)         | `status = SUCCESS`, `retryCount = 0` — imports proceed normally                                      |
| OPEN (blocked)          | `retryCount >= maxRetries` — the shortened retry cadence ends; the source drops back to its interval |
| HALF-OPEN (probe)       | The next scheduled tick after that interval, or a manual `POST /event-sources/{slug}/retry`          |
| Cool-down period        | Exponential backoff (`interval × 2^retryCount`), capped at six hours                                 |
| Permanent fault         | `status = MISCONFIGURED` — config errors (unknown source type, no importer) skip retries             |

The difference from a classic circuit breaker is what OPEN costs. A formal one auto-transitions OPEN → HALF-OPEN after a timeout; here OPEN is not a separate
state at all, only the end of the shortened cadence, and the source keeps being probed on its normal schedule indefinitely.

**That is a change, and #659 is why.** OPEN used to mean the source was excluded from the due-sources query outright, on the reasoning that a persistently
failing scraper needs a code change rather than more attempts. The reasoning is sound and the mechanism was not: a source that stops being attempted stops
producing failures, and a venue with no recent failures is indistinguishable from a venue with nothing to import. The observability built since — `#618`'s
`has_succeeded`, `#700`'s per-source gauge, the `ej-importer-stale` rule — all read `event_source` rather than the scheduler, so silence there reads as health.
Probing once a day and failing visibly costs one HTTP request and produces the signal those rules exist to catch. `enabled = false` remains the way to stop a
source deliberately, and it is still honoured.

Resilience4J could add value at a **different layer** — in-process HTTP retries inside `HtmlFetcher`
for transient failures (503, timeouts) within a single import attempt. This is a complementary concern and can be added independently if needed.

## Consequences

- **Positive**: Zero new dependencies; fully coroutine-native; per-source scheduling with different intervals; retry with exponential backoff; staleness
  detection for stuck imports; all job metadata lives in one table (`event_source`); easy to build a dashboard/API on top.
- **Negative**: Not as feature-rich as JobRunr's built-in dashboard (but we're building our own Vue frontend); cron expressions are not supported (only fixed
  intervals — sufficient for venue scraping where "every N hours" is the typical pattern).
- If more sophisticated scheduling is needed in the future (e.g. time-of-day constraints, complex cron patterns, distributed job processing), JobRunr would be
  the natural upgrade — but this would require adding a JDBC DataSource alongside R2DBC (see below).

### JobRunr Dual-DataSource Feasibility

JobRunr requires JDBC and has [no R2DBC support](https://github.com/jobrunr/jobrunr/issues/257). However, a **dual-DataSource** approach is technically
feasible: configure a JDBC `DataSource`
alongside the existing R2DBC connection, both pointing to the same PostgreSQL instance. JobRunr supports this via `jobrunr.database.datasource` to target a
specific named `DataSource` bean.

This was evaluated and **deferred** because the costs outweigh the benefits at current scale:

| Cost                       | Detail                                                                                                   |
| -------------------------- | -------------------------------------------------------------------------------------------------------- |
| New dependencies           | `spring-boot-starter-jdbc`, PostgreSQL JDBC driver, `jobrunr-spring-boot-4-starter`                      |
| Duplicate connection pools | HikariCP (JDBC) + R2DBC pool to the same database — doubles connection resource usage                    |
| Duplicate job metadata     | JobRunr creates its own tables (`jobrunr_jobs`, `jobrunr_recurring_jobs`, etc.) alongside `event_source` |
| Architecture inconsistency | Breaks the "reactive stack throughout" principle ([ADR-001](ADR-001_REACTIVE_STACK.md))                  |
| Configuration complexity   | Two DataSource beans with custom qualifiers, shared credentials                                          |

**When to reconsider:** if the project needs distributed job processing across multiple instances, cron-expression scheduling (e.g. "scrape only at 3 AM"), job
queues with priorities, or a built-in dashboard without building one in the Vue frontend.

## References

- [Spring `@Scheduled` reference](https://docs.spring.io/spring-framework/reference/integration/scheduling.html)
- [JobRunr](https://www.jobrunr.io/) — considered but rejected for now
- [ADR-007: Web Scraping Strategy](ADR-007_WEB_SCRAPING_STRATEGY.md)
- [`event_source` table](../../events-importer/src/main/resources/db/migration/V001__create_initial_schema.sql)
