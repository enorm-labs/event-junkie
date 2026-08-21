# Importer — Data Quality Strategy

How we fix the data-quality gaps we have and prevent new ones from accumulating. This is the **strategy / plan**; the actionable backlog lives in the
[issue tracker](https://github.com/enorm-labs/event-junkie/issues) and each importer's accepted limitations in its
own scraper KDoc. Where this doc names work to do, **the authoritative task is the issue** and this doc points at it — the two must not drift.

Related: [ADR-007 Web Scraping Strategy](adr/ADR-007_WEB_SCRAPING_STRATEGY.md) ·
[EVENT_DATA_SOURCES.md](EVENT_DATA_SOURCES.md) · [DATA_MODEL.md](DATA_MODEL.md) ·
[DATA_QUALITY_PILLAR_1_PLAN.md](DATA_QUALITY_PILLAR_1_PLAN.md).

---

## 1. Where we are today

Data quality is enforced by **deterministic, curated-list normalizers applied at the scrape → domain mapping boundary**. The building blocks:

| Concern             | Mechanism                                                                               | Location                         |
| ------------------- | --------------------------------------------------------------------------------------- | -------------------------------- |
| Artist display name | `canonicalArtistName` — de-shout, casing-only                                           | `artist/ArtistNormalizer.kt`     |
| Promoter identity   | `canonicalPromoterName` — strip trailing descriptors, fold typos via `NAME_CORRECTIONS` | `promoter/PromoterNormalizer.kt` |
| Non-artist titles   | `isNonArtistName` (`NON_ARTIST_NAMES` denylist), `stripArtistSuffix`                    | `scraper/ArtistNameMapping.kt`   |
| Title-as-headliner  | `buildArtistsForEventType` / `buildArtistList`                                          | `scraper/ArtistNameMapping.kt`   |
| Genre tags          | `GenreNormalizer` — synonym map + `NON_GENRE_TOKENS` stop-list + `looksLikeGenre` gate  | `genretag/GenreNormalizer.kt`    |

This is sound, cheap, and fast — and it should stay the first pass. But it has three _structural_ weaknesses that adding more curated entries will never
resolve:

1. **It is reactive.** Every mechanism ("handled case-by-case as they surface",
   "new ones need an entry", "slip through until denylisted") only catches values we have _already seen_. Newly-seen bad data always lands in the DB first and
   is corrected later, if ever.
2. **The feedback loop is open.** The curation signal already exists — the
   `Dropping non-genre token '…'` logs (`GenreNormalizer.kt`), artist-less concerts, `OTHER`-typed events — but nothing routes it back to a human. The curation
   queue is invisible, so the curated lists only grow when someone happens to notice a bad row.
3. ~~**There is no measurement and no gate.**~~ **Half-resolved 2026-08-19.** Pillar 1 shipped as `de.norm.events.dataquality`: the numbers exist, per source,
   with a `data_quality_snapshot` history so a trend is visible — see [DATA_QUALITY_PILLAR_1_PLAN.md](DATA_QUALITY_PILLAR_1_PLAN.md) §8a. **The gate half is
   still open**: nothing fails when a change regresses a metric, so quality is now _observed_ but still not _enforced_.

~~The single largest _fix_ opportunity is already identified: **~40% of `CONCERT` events carry no artist**~~ — **superseded by measurement, 2026-08-21.**

Counted against staging's 3,409 events, the number is **3.5%**: 74 artist-less concerts out of 2,128, with 119 events typed `OTHER`. Title-as-headliner
extraction is now used by 49 scrapers rather than being disabled at the three venues this paragraph named.

**The order-of-magnitude gap between the estimate and the measurement is the point of §1.3**, not a footnote to it. This document asserted ~40% in prose, that
figure survived unchallenged into planning, and the first thing an actual query did was refute it. A strategy whose headline problem is ten times smaller than
stated is one that prioritises the wrong pillar — which is precisely the argument for measuring first.

## 2. What data-quality issues _are_ — a shared taxonomy

Before fixing anything we need a shared vocabulary for _what kind_ of wrong a value is and _where_ it was introduced. Two axes.

### 2.1 By quality dimension (DAMA-DMBOK)

The industry-standard dimensions, mapped to our data. This vocabulary is the backbone of the Pillar 1 metrics.

| Dimension                 | Meaning                                       | In our data                                                                        |
| ------------------------- | --------------------------------------------- | ---------------------------------------------------------------------------------- |
| **Completeness**          | expected value is present                     | missing headliner, genre, promoter, price, start time                              |
| **Validity / Conformity** | value matches the expected type/format/domain | `eventType = OTHER`, malformed date/time, price-parse failure, URL double-encoding |
| **Accuracy**              | value is the _correct_ real-world fact        | non-artist title stored as an artist, wrong promoter, festival-day mislabel        |
| **Consistency**           | the same fact is represented one way          | one act/promoter spelled many ways (ALL-CAPS vs mixed case)                        |
| **Uniqueness**            | no unintended duplicates                      | duplicate events, fragmented artist/promoter rows                                  |
| **Timeliness**            | data reflects the current world               | stale past events, first-page-only, year inferred from weekday                     |

### 2.2 By stage introduced

_Where_ a defect enters decides _where_ it can be fixed — and whether it's even fixable on our side.

| Stage                 | Failure mode                                    | Example                                                            |
| --------------------- | ----------------------------------------------- | ------------------------------------------------------------------ |
| **Source**            | site is ambiguous / incomplete / stale          | Badehaus exposes no artist field; Roadrunner leaves past events up |
| **Fetch**             | 404, JS-rendered, cookie-wall, dead link        | `%`-encoded Arabic-slug 404; JS-only venues unimportable           |
| **Parse / extract**   | wrong or fragile selector, positional fallback  | Cassiopeia `._5` / `._8` positional fallbacks                      |
| **Normalize / map**   | over- or under-normalization (the reactive gap) | `MUNA → Muna`; a new non-artist title slips through                |
| **Entity resolution** | fragmentation or false merge                    | promoter variants; case-insensitive slug collisions                |

### 2.3 Prioritization: Impact × Prevalence × Fixability

Not every issue is worth chasing. Rank by three factors:

- **Impact** — 🔴 user-visible wrong/missing · 🟠 data-quality noise · 🟢 cosmetic/edge case.
- **Prevalence** — how many rows are affected. _This is exactly what Pillar 1 measures_ — which is why we measure before we fix.
- **Fixability** — deterministic rule (cheap) · curated entry (human) · needs a classifier (AI) · source-limited (accept & document). **Fixability sequences the
  pillars.**

Applied to the current catalogue:

| Rank | Issue                             | Dimension    | Impact | Prevalence   | Fix path                                |
| ---- | --------------------------------- | ------------ | ------ | ------------ | --------------------------------------- |
| 1    | Missing headliner                 | Completeness | 🔴     | ~40%         | Deterministic — **Pillar 3, ready now** |
| 2    | `eventType = OTHER`               | Validity     | 🟠     | high         | Measure → heuristic / AI (Pillar 4)     |
| 3    | Non-artist title as artist        | Accuracy     | 🟠     | low          | Classifier (Pillar 4) + curation queue  |
| 4    | Promoter/artist residual variants | Consistency  | 🟠     | low          | Curated map via curation queue          |
| 5    | Missing price / time / promoter   | Completeness | 🟠🟢   | source-bound | Mostly _accept & document_ — low ROI    |

The lesson: deterministic-and-ready work goes first (headliner); classifier-needed work waits for Pillar 4; source-limited items get _accepted_, not chased.

## 3. Principles

- **Deterministic-first, model-second.** Curated rules are fast, free, and auditable — keep them as the first pass. Escalate to AI only for the long tail they
  cannot cover (Pillar 4).
- **Measure before you change.** No normalizer change ships without a baseline number and a golden test that would catch its regression.
- **Close the loop.** Every value the pipeline drops or can't classify becomes a visible curation item, not a silent log line.
- **Preserve the raw.** Normalization is additive — the raw scraped text stays on the event (as `genre` already does), so re-processing is always possible.
- **Fix at the boundary, backfill separately.** New imports are corrected at the mapping boundary; already-persisted bad rows are recovered by explicit backfill
  passes, never by silently mutating on read.

## 4. Tooling: patterns, not platforms

Our problem is **ingestion / extraction quality plus entity resolution** (an MDM / data-stewardship shape), _not_ warehouse analytics DQ. That rules out most of
the well-known tooling at our scale (~hundreds of events) and stack (reactive Kotlin, operational Postgres):

| Tool                                  | What it is                                    | Verdict                                                                     |
| ------------------------------------- | --------------------------------------------- | --------------------------------------------------------------------------- |
| Great Expectations / Soda             | Python declarative "expectations" + data docs | Great _pattern_, wrong runtime (Python, batch)                              |
| dbt tests                             | SQL tests in a warehouse                      | Wrong shape — we have operational Postgres, not a warehouse                 |
| AWS Deequ / PyDeequ                   | JVM "unit tests for data"                     | Closest JVM fit, but a Spark dependency is massive overkill here            |
| Apache Griffin / Monte Carlo / Bigeye | Big-data DQ / observability SaaS              | Enterprise overkill                                                         |
| OpenRefine                            | interactive cleaning + clustering for dedup   | Not adopted — but its clustering UX is the reference for our curation queue |

**Decision: don't adopt a DQ platform — adopt its patterns natively.** We already have the pieces (Jakarta Bean Validation, Kotest, a clean mapping boundary,
the canonicalizers). Borrow three ideas:

1. **Declarative expectations** — express quality rules as data/config, not scattered `if`s.
2. **DQ-dimensions taxonomy** (§2.1) — for categorizing and reporting.
3. **Quality-as-observability** — track metrics _over time_, not just a snapshot.

For **dashboards & trends**, reuse an external BI/observability tool rather than building a bespoke UI (this is [issue #386](https://github.com/enorm-labs/event-junkie/issues/386), _"A dashboard for analysing
the data"_):

- **SQL-based BI** (Apache Superset / Metabase) pointed straight at the Postgres
  `events` schema and a metrics-snapshot table — best for data-level dashboards.
- **Metrics observability** (Micrometer → Prometheus → Grafana via the Actuator already in the importer) — best for operational trend lines and alerting.

See the Pillar 1 plan for how the metrics are exposed to feed these.

## 5. The four pillars

Ordered deliberately: get a baseline and a safety net _before_ changing extraction.

### Pillar 1 — Measure (make quality visible) 🟢 low effort, unblocks the rest

The keystone. Everything else is judged against these numbers.

- **Data-quality report.** A `GET /api/admin/data-quality` endpoint (plus a scheduled summary log and Micrometer gauges) reporting, per event source:
  concerts with no artist, events typed `OTHER`, events missing genre / promoter / price / start time, and titles that look like non-artist names still stored
  as artists.
- **Curation queue (API-first).** Promote the existing drop/degrade signals (`Dropping non-genre token`, artist-less events, detail-fetch fallbacks) into an
  explicit, queryable **worklist endpoint** — the raw material for growing
  `NON_ARTIST_NAMES`, `NAME_CORRECTIONS`, and the genre synonym map. No bespoke frontend yet (see §7): stewards act on the worklist via the existing
  `PUT /api/admin/events/{id}` API / Swagger / `.http` files.

_Exit criterion:_ a per-source number for each headline metric, chartable in an external BI tool (§4), so Pillars 3–4 can be judged by whether those numbers
move.

_Implementation plan:_ [DATA_QUALITY_PILLAR_1_PLAN.md](DATA_QUALITY_PILLAR_1_PLAN.md).

### Pillar 2 — Prevent (stop regressions) 🟠 medium effort, low risk

- **Golden fixture tests.** Freeze real scraped HTML snippets whose current output is correct (`THE BUTLERS - 40 YEARS … → The Butlers`,
  `GREEN LUNG → Green Lung`,
  `Tango or NonTango → Tango`) so a normalizer tweak that breaks them fails CI. These become the regression net for all four normalizers.
- **Validation gate at the boundary.** A lightweight check in the mapping boundary that flags/rejects obviously-bad output instead of persisting it silently:
  empty artist after stripping, artist identical to a known non-artist pattern, a genre token that is a whole event title. Flagged rows feed the Pillar 1
  curation queue.

### Pillar 3 — Fix (recover missing / bad data) 🔴 highest user-visible payoff

- **Title-as-headliner extraction** for Privatclub, Cassiopeia, and Badehaus — the
  work in [issue #321](https://github.com/enorm-labs/event-junkie/issues/321) that reclaims the ~40% of artist-less concerts. Now safe:
  `isNonArtistName` + `stripArtistSuffix` guard against non-artist titles, and Astra/Lido already do exactly this via `buildArtistsForEventType`.
- **One-off backfill pass** over existing rows for the same recoverable fields (artist from title, event type from title heuristics), run once after the
  extraction ships so historical rows benefit too.

### Pillar 4 — Systematize (escape the curated-list treadmill) 🔵 biggest lever

The general answer to weakness #1. An **LLM-assisted enrichment stage that runs _after_ the deterministic normalizers**, handling only the long tail they can't:
title → artist extraction, event-type validation, genre / missing-field enrichment, and bad-value correction (artist and promoter names).

- Deterministic rules stay the fast, free, first pass; the model is the fallback, not the front door.
- Human-in-the-loop: a steward confirms/corrects model output (via the API now, a frontend later — §7); confirmed corrections feed back into the curated
  vocabulary — closing the loop Pillar 1 opened. This is where the _curated-vocab storage_ decision (§6) bites: live steward fixes need the vocab to be data.
- **Requires its own ADR — _AI-Assisted Data Quality_** (unnumbered until written; see the numbering note in [AGENTS.md](../AGENTS.md)): it introduces a new
  external dependency, per-import cost and latency, and non-deterministic output, all of which interact with the scraping-pipeline decisions in ADR-007. Scope
  for the ADR: model/provider choice, where the stage sits in the pipeline, caching/idempotency, cost controls, and how model output is reconciled with the
  deterministic layer and the human review step.

## 6. Open decisions

These are recorded, not yet resolved — settle them before the pillar that needs each.

- **Curated-vocabulary storage — code vs. data (ADR candidate).** Today the denylists / synonym maps / corrections (`NON_ARTIST_NAMES`, `NAME_CORRECTIONS`,
  genre synonyms, `ACRONYMS`) are hardcoded Kotlin `Set`/`Map`s. A steward fixing an issue therefore means a code edit + PR + redeploy.
    - _Keep as code:_ versioned, unit-tested, PR-reviewed — but every fix is a deploy.
    - _Promote to DB tables (steward-editable):_ fixes land instantly and close the loop — but loses PR review/testing of vocab changes and adds
      cache-invalidation.
    - _Direction:_ undecided; spike + ADR before Pillar 4's human-in-the-loop needs live editing. Blocks nothing in Pillars 1–3.
- **Fix / curation surface — API-only for now.** Ship the DQ report + worklist endpoints; stewards fix via the existing `PUT /api/admin/events/{id}` API,
  Swagger, and `.http` files. A dedicated review frontend is deferred to the backlogged _"Admin frontend to review, enrich & fix event data"_ item — the DQ work
  provides the _signal_, that frontend will provide the _fix surface_. Avoid building a second admin app.
- **Dashboard — external BI tool, not a bespoke UI** (§4). Reuse the backlogged Superset/Grafana/Kibana item; Pillar 1 exposes metrics in a shape those tools
  consume.

## 7. Sequencing

1. **Pillar 1** — data-quality report + worklist endpoints. Fast, low-risk, and it baselines everything after it.
2. **Pillar 2** — golden fixture tests + boundary validation gate. The safety net that de-risks touching the normalizers.
3. **Pillar 3** — title-as-headliner extraction + backfill. Highest immediate user-visible gain; safe once Pillar 2 exists.
4. **Pillar 4** — AI-assisted enrichment + steward review. Largest lever and most effort; gated on the _AI-Assisted Data Quality_ ADR (§5) and the §6
   vocab-storage decision.

## 8. Success metrics

Tracked via the Pillar 1 report, per source and overall, and charted over time in an external BI tool (§4):

- **Concert headliner coverage** — % of `CONCERT` events with ≥1 artist (baseline ~60%; target the Privatclub/Cassiopeia/Badehaus recovery).
- **Event-type classification** — % of events _not_ typed `OTHER`.
- **Field completeness** — % with genre / promoter / price where the source exposes them.
- **Curation-queue burn-down** — dropped/flagged items reviewed vs. outstanding.

## 9. How this maps to the backlog

The four pillars are issues [#319](https://github.com/enorm-labs/event-junkie/issues/319) (Measure),
[#320](https://github.com/enorm-labs/event-junkie/issues/320) (Prevent), [#321](https://github.com/enorm-labs/event-junkie/issues/321) (Fix) and
[#322](https://github.com/enorm-labs/event-junkie/issues/322) (Systematize). The admin review frontend and imports-status dashboard are
[#340](https://github.com/enorm-labs/event-junkie/issues/340) and its sub-issues; the Superset/Grafana dashboard (§4) is
[#386](https://github.com/enorm-labs/event-junkie/issues/386). This doc is the _why and in what order_; the issues are the _what_.
