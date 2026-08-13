# Implementation Plan — Data Quality Pillar 1 (Measure)

Concrete build plan for **Pillar 1** of the
[Data Quality Strategy](DATA_QUALITY_STRATEGY.md): make quality _visible_ so Pillars 2–4 can be judged by whether the numbers move. Backlog item:
[issue #319](https://github.com/enorm-labs/event-junkie/issues/319) — _Pillar 1 — Measure_.

**Goal:** a per-source data-quality report (API + scheduled log + metrics), a queryable **curation worklist**, and **metric history** so trends are chartable in
an external BI tool. No changes to scraper extraction or the normalizers — this pillar only observes.

**Non-goals:** fixing any data (Pillar 3), regression tests / validation gate (Pillar 2), AI enrichment (Pillar 4). **No bespoke frontend** — the report and
worklist are **API-only for now**; stewards act via the existing
`PUT /api/admin/events/{id}`, Swagger, and `.http` files, and dashboards come from an **external BI tool** (Superset/Metabase or Grafana), not a hand-built UI
(see §5 and the strategy §4).

---

## 1. Metrics to report

All counts are **per event source** (`event.event_source_id` → `event_source`), with an `overall` roll-up. Manually-created events (`event_source_id IS NULL`)
are reported under a synthetic `manual` bucket so nothing is silently excluded. Each metric maps to a quality **dimension** (strategy §2.1) for consistent
reporting.

| Metric                   | Dimension    | Definition (per source)                                                                  | Signal                     |
| ------------------------ | ------------ | ---------------------------------------------------------------------------------------- | -------------------------- |
| `totalEvents`            | —            | `COUNT(*)`                                                                               | denominator                |
| `concertsWithoutArtist`  | Completeness | `event_type = 'CONCERT'` AND no `event_artist` row                                       | 🔴 the ~40% gap            |
| `eventsTypedOther`       | Validity     | `event_type = 'OTHER'`                                                                   | 🟠 classification gap      |
| `missingGenre`           | Completeness | `genre IS NULL OR genre = ''`                                                            | 🟠 completeness            |
| `missingPromoter`        | Completeness | no `event_promoter` row                                                                  | 🟠 completeness            |
| `missingPrice`           | Completeness | `price_presale IS NULL AND price_box_office IS NULL AND NOT free AND price_note IS NULL` | 🟠 completeness            |
| `missingStartTime`       | Completeness | `start_time IS NULL`                                                                     | 🟢 completeness            |
| `suspectNonArtistTitles` | Accuracy     | artist names linked to this source's events where `isNonArtistName(name)` is true        | 🟠 noise (Kotlin-side, §4) |

Each headline metric is reported as a **count and a rounded percentage** of
`totalEvents` (the percentage is what a human scans; the count is what a test asserts).

> **Open decision A — `missingPrice` definition.** Recommendation: the row above
> (treat `free` events and events with a free-form `price_note` as _not_ missing).
> Flag if you'd rather count `price_note`-only events as missing structured price.

## 2. Module & placement

A new read-only Spring Modulith module **`de.norm.events.dataquality`** — it observes across events and sources, so it doesn't belong inside `scraper` or
`event`. Follows the standard importer feature-module layout.

```
events-importer/src/main/kotlin/de/norm/events/dataquality/
  DataQualityModule.kt         // @ApplicationModule(allowedDependencies = ["event", "scraper", "artist"])
  DataQualityController.kt      // GET /api/admin/data-quality (+ worklist)  @Tag("Admin: Data Quality")
  DataQualityService.kt         // orchestrates SQL aggregate + Kotlin-side non-artist check
  DataQualityRepository.kt      // @Query aggregate + worklist projections
  DataQualityResponses.kt       // report + worklist DTOs (plural: multiple classes)
  DataQualitySnapshotEntity.kt  // persisted daily metric history (§5.2)
  DataQualityMetrics.kt         // Micrometer gauge registration (§5.2)
  DataQualityReportLogger.kt    // @Scheduled daily summary log + snapshot write (§6 Phase B)
```

- Module dependencies: `event` (event + join-table repos), `scraper`
  (`EventSourceEntity`/`EventSourceRepository` to label sources), `artist`
  (artist-name lookup for `suspectNonArtistTitles`, §4). `ModularityTests` verifies.
- `@ApplicationModule` marker + `@Tag(name = "Admin: Data Quality")` on the controller, matching the existing `Admin: *` grouping convention.

## 3. The aggregate query

R2DBC can't derive `GROUP BY`/conditional counts, so this is a raw `@Query` (like
`EventSourceRepository.findDueForImport`) — **schema-prefixed `events.`** because raw SQL bypasses the `NamingStrategy` (per ADR-004 / AGENTS.md). Postgres
`COUNT(*) FILTER (WHERE …)` with correlated `NOT EXISTS` gives all completeness metrics in one pass:

```sql
SELECT
    e.event_source_id                                               AS event_source_id,
    COUNT(*)                                                        AS total_events,
    COUNT(*) FILTER (WHERE e.event_type = 'CONCERT'
        AND NOT EXISTS (SELECT 1 FROM events.event_artist ea WHERE ea.event_id = e.id))
                                                                    AS concerts_without_artist,
    COUNT(*) FILTER (WHERE e.event_type = 'OTHER')                 AS events_typed_other,
    COUNT(*) FILTER (WHERE e.genre IS NULL OR e.genre = '')        AS missing_genre,
    COUNT(*) FILTER (WHERE NOT EXISTS
        (SELECT 1 FROM events.event_promoter ep WHERE ep.event_id = e.id))
                                                                    AS missing_promoter,
    COUNT(*) FILTER (WHERE e.price_presale IS NULL AND e.price_box_office IS NULL
        AND e.free = false AND e.price_note IS NULL)               AS missing_price,
    COUNT(*) FILTER (WHERE e.start_time IS NULL)                   AS missing_start_time
FROM events.event e
GROUP BY e.event_source_id
```

Mapped to a **projection data class** whose properties match the column **aliases**. R2DBC projection mapping by column label is the one fiddly part — alias
every column in `snake_case` and assert the mapping in the integration test (§6). Source `slug`/`name` are resolved in the service from
`EventSourceRepository` (small, cached set) rather than joined, keeping the aggregate query focused; `null` id → the `manual` bucket.

```kotlin
interface DataQualityRepository : CoroutineCrudRepository<EventEntity, Long> {
    @Query(""" …aggregate SQL above… """)
    fun aggregatePerSource(): Flow<SourceQualityRow>
}

data class SourceQualityRow(
    val eventSourceId: Long?,
    val totalEvents: Long,
    val concertsWithoutArtist: Long,
    val eventsTypedOther: Long,
    val missingGenre: Long,
    val missingPromoter: Long,
    val missingPrice: Long,
    val missingStartTime: Long,
)
```

## 4. `suspectNonArtistTitles` (Kotlin-side)

`isNonArtistName` is Kotlin, not SQL, so this metric is computed in the service:
load the artist names linked to each source's events, run `isNonArtistName` over the distinct set, count the hits. Two viable data paths — pick per what's
cheapest to query:

- reuse the batch association fetch (`EventArtistRepository.findByEventIdIn`) + artist name lookup, grouped by source; or
- a dedicated `@Query` returning `(event_source_id, artist_name)` pairs, filtered in Kotlin.

Recommendation: start with the dedicated `@Query` (one round-trip, no N+1), filter in the service. Keep it **optional/lazy** if it proves expensive — it's the
only metric that can't be a pure SQL count.

## 5. Exposure — worklist, history, and BI

The report is not just a number to read once; it feeds fixing and trend-charting. Three exposure surfaces, all API/DB — **no bespoke frontend** (strategy §6).

### 5.1 Report + curation worklist endpoints

- `GET /api/admin/data-quality` → the per-source + `overall` metrics snapshot (payload in §5.4).
- `GET /api/admin/data-quality/worklist?issue=<metric>&source=<slug>` → the **curation worklist**: the paginated _list of offending events_ for one metric (e.g.
  `concertsWithoutArtist`), so a steward can open each and fix it via the existing `PUT /api/admin/events/{id}`. This is the "close the loop" surface, API-only
  for now.

> **Open decision B — worklist depth.** Pillar 1 minimum surfaces the
> _state-derived_ worklists above (events failing a metric — pure queries, no new
> signal storage). The strategy also wants the `Dropping non-genre token '…'`
> signal surfaced, which is currently **only logged** and not persisted. Options:
>
> - **B1 (recommended, in-scope):** ship the state-derived worklists now; expose
>   the dropped-token queue as a follow-up (`Pillar 1b`) since persisting it means
>   giving the stateless `GenreNormalizer` a side-effect or a structured-log sink.
> - **B2:** add a `data_quality_flag` table now and write to it from the
>   normalizers. Bigger blast radius (touches the shared normalizer + schema).
>   If B2: the table goes into `V001__create_initial_schema.sql` directly (no `V002`
>   until the first prod baseline — see ADR-005 / AGENTS.md).

### 5.2 Metric history (for trends)

A point-in-time report can't show whether Pillar 3/4 _moved_ the numbers. Persist a daily snapshot so trends are chartable:

- **`data_quality_snapshot`** table — one row per `(source, metric, date)` with the count and total. Written by the scheduled logger (§6 Phase B). Added to
  `V001__create_initial_schema.sql` (single-migration policy, ADR-005).
- Additionally register **Micrometer gauges** per metric/source on the Actuator registry already in the importer, so a Prometheus scrape captures the same
  series operationally.

### 5.3 Dashboards via an external BI tool (not a hand-built UI)

Reuse the backlogged _"Dashboard for analysing the data (Superset / Kibana / Grafana)"_ item rather than building a frontend — two clean paths, pick per taste:

- **Apache Superset / Metabase** pointed at the `events` schema + the
  `data_quality_snapshot` table → SQL-driven data-quality dashboards and trend charts. Best fit for data-level reporting; no app code.
- **Micrometer → Prometheus → Grafana** via the importer's Actuator → operational trend lines + alerting (e.g. alert if `concertsWithoutArtist` regresses).

Pillar 1's job is only to _expose_ the metrics (§5.2) in a shape these tools consume; standing up the tool itself is the separate backlog item.

### 5.4 Report payload

`GET /api/admin/data-quality` → `200`:

```jsonc
{
  "generatedAt": "2026-07-07T10:15:00Z",
  "overall":   { "totalEvents": 354, "concertsWithoutArtist": 148, "concertsWithoutArtistPct": 41.8, "...": "…" },
  "perSource": [
    { "source": "badehaus",   "totalEvents": 92, "concertsWithoutArtist": 72, "concertsWithoutArtistPct": 78.3, "...": "…" },
    { "source": "privatclub", "…": "…" },
    { "source": "manual",     "…": "…" }
  ]
}
```

- `DataQualityReportResponse` + `SourceQualityMetrics` in `DataQualityResponses.kt`, each with a `from(...)` factory per the DTO convention; `@Schema` on every
  field.
- `@Operation(summary = …)` on each endpoint. Read-only `GET`s, no request DTO.
- `overall` is summed from the per-source rows in the service (not a second query).

## 6. Testing

- **`DataQualityServiceTest`** (MockK) — unit-test the roll-up math, percentage rounding, the `manual` bucket for `null` source, and the `isNonArtistName`
  filter over a seeded name set.
- **`DataQualityControllerTest`** extends `BaseControllerTest` (Testcontainers) — the authoritative test: seed events across ≥2 sources plus one manual event,
  with/without artist rows, `OTHER` types, and missing fields; `GET` the report and a worklist; assert every count, percentage, and the returned offending IDs.
  **This is where the R2DBC projection column-alias mapping (§3) is proven.**
- **Snapshot write** — a focused test that the scheduled logger persists a
  `data_quality_snapshot` row per source/metric.
- **`ModularityTests`** — already present; picks up `DataQualityModule` and verifies its `allowedDependencies`.
- Coverage: keep the module above the Kover threshold (service logic is the bulk).

## 7. Build sequence

1. **Module skeleton** — `DataQualityModule.kt` + empty controller/service/repo; confirm `ModularityTests` passes with the declared dependencies.
2. **Aggregate query + projection** — `DataQualityRepository.aggregatePerSource()`
    - `SourceQualityRow`; prove column mapping with a thin integration test.
3. **Service roll-up** — per-source → response, `overall` sum, percentages,
   `manual` bucket. Unit tests.
4. **`suspectNonArtistTitles`** — Kotlin-side metric (§4).
5. **Report endpoint + DTOs + Swagger** — `GET /api/admin/data-quality`; controller test.
6. **Worklist endpoint** — `GET …/worklist?issue=&source=` returning offending events (§5.1); paginated, reuses `EventResponse`.
7. **History + metrics** — `data_quality_snapshot` table (in `V001`), Micrometer gauges (§5.2), and the `@Scheduled` daily logger+snapshot writer (respect
   `app.scheduling.enabled: false` in tests).
8. **Docs & wiring** — close issue #319; add `http/importer/` `.http`
   requests for the new endpoints; note the endpoints in the importer Swagger set.
9. **Verify** — `./gradlew ktlintCheck detekt build koverLog` (the `/verify`
   backend sequence). Backend-only change, so frontend steps don't apply.

## 8. Definition of done

- `GET /api/admin/data-quality` returns per-source + overall counts and percentages for every §1 metric.
- `GET …/worklist` returns the offending events for a given metric/source.
- Daily scheduled summary log + `data_quality_snapshot` rows; Micrometer gauges registered (metrics consumable by Superset/Grafana per §5.3).
- Unit + Testcontainers integration tests green; new module within Kover threshold; `ModularityTests` green.
- Baseline numbers captured (first snapshot row set) so Pillars 3–4 have a
  "before" to move.

## 9. Open decisions (recap)

- **A — `missingPrice` definition** (§1): recommend excluding `free` and
  `price_note`-only events.
- **B — worklist depth** (§5.1): recommend B1 (state-derived worklists now, dropped-token queue as Pillar 1b) over B2 (persisted flag table now).
- **C — `suspectNonArtistTitles` cost** (§4): ship it; make it lazy/optional if the query proves heavy.
- **Deferred to the strategy (§6), not blocking Pillar 1:** curated-vocab storage (code vs. data) and the eventual review frontend — Pillar 1 stays API-only and
  vocab-agnostic.
