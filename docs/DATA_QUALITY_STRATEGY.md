# Importer — Data Quality Strategy

How we fix the data-quality gaps we have and prevent new ones from accumulating. This is the **strategy / plan**. The
actionable backlog lives in the [issue tracker](https://github.com/enorm-labs/event-junkie/issues), and each
importer's accepted limitations in its own scraper KDoc. Where this doc names work to do, **the authoritative task is
the issue** and this doc points at it. The two must not drift.

Related: [ADR-007 Web Scraping Strategy](adr/ADR-007_WEB_SCRAPING_STRATEGY.md) ·
[EVENT_DATA_SOURCES.md](EVENT_DATA_SOURCES.md) · [DATA_MODEL.md](DATA_MODEL.md).

## The short version

Four pillars, in order: **Measure**, **Prevent**, **Fix**, **Systematize** (§5). Pillar 1 shipped, so the numbers now
exist per source. Nothing yet _fails_ when a metric regresses, and that gate is the next thing worth building.

The measuring came first on purpose, and §1.3 is why. This document asserted in prose that ~40% of concerts carried no
artist. The first real query put it at **3.5%**. A strategy whose headline problem is ten times smaller than stated
prioritises the wrong pillar.

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

1. **It is reactive.** Every mechanism catches only a value we saw before. The phrasings give it away: "handled
   case-by-case as they surface", "new ones need an entry", "slip through until denylisted". Newly-seen bad data
   lands in the DB first, and is corrected later, if ever.
2. **The feedback loop is open.** The curation signal already exists: the `Dropping non-genre token '…'` logs
   (`GenreNormalizer.kt`), artist-less concerts, `OTHER`-typed events. Nothing routes it back to a human. The curation
   queue is invisible, so the curated lists only grow when someone happens to notice a bad row.
3. ~~**There is no measurement and no gate.**~~ **Half-resolved 2026-08-19.** Pillar 1 shipped as
   `de.norm.events.dataquality`. The numbers exist per source, with a `data_quality_snapshot` history, so a trend is
   visible. **The gate half is still open.** Nothing fails when a change regresses a metric, so quality is now
   _observed_ but still not _enforced_.

~~The single largest _fix_ opportunity is already identified: **~40% of `CONCERT` events carry no artist**~~ — **superseded by measurement, 2026-08-21.**

Counted against staging's 3,409 events, the number is **3.5%**: 74 artist-less concerts out of 2,128, with 119 events typed `OTHER`. Title-as-headliner
extraction is now used by 49 scrapers rather than being disabled at the three venues this paragraph named.

**The order-of-magnitude gap between the estimate and the measurement is the point of §1.3**, not a footnote to it.
This document asserted ~40% in prose, that figure survived unchallenged into planning, and the first thing an actual
query did was refute it. A strategy whose headline problem is ten times smaller than stated prioritises the wrong
pillar. That is precisely the argument for measuring first.

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

The lesson: deterministic-and-ready work goes first, as the headliner extraction did. Classifier-needed work waits
for Pillar 4. A source-limited item is _accepted_, not chased.

## 3. Principles

- **Deterministic-first, model-second.** Curated rules are fast, free, and auditable — keep them as the first pass. Escalate to AI only for the long tail they
  cannot cover (Pillar 4).
- **Measure before you change.** No normalizer change ships without a baseline number and a golden test that would catch its regression.
- **Close the loop.** Every value the pipeline drops or can't classify becomes a visible curation item, not a silent log line.
- **Preserve the raw.** Normalization is additive — the raw scraped text stays on the event (as `genre` already does), so re-processing is always possible.
- **Fix at the boundary, backfill separately.** New imports are corrected at the mapping boundary. An
  already-persisted bad row is recovered by an explicit backfill pass, never by silently mutating on read.

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

- **Data-quality report.** A `GET /api/admin/data-quality` endpoint, plus a scheduled summary log and Micrometer
  gauges. Per event source it reports concerts with no artist, events typed `OTHER`, and events missing genre,
  promoter, price or start time. It also reports titles that look like non-artist names and are still stored as
  artists. A last metric counts events whose source has no copyright position yet.
- **Curation queue (API-first).** Promote the existing drop and degrade signals into an explicit, queryable **worklist
  endpoint** — `Dropping non-genre token`, artist-less events, detail-fetch fallbacks. That is the raw material for
  growing `NON_ARTIST_NAMES`, `NAME_CORRECTIONS` and the genre synonym map. No bespoke frontend yet (see §7). A
  steward acts on the worklist through the existing `PUT /api/admin/events/{id}` API, Swagger and `.http` files.

_Exit criterion:_ a per-source number for each headline metric, chartable in an external BI tool (§4). Pillars 3–4 are
then judged by whether those numbers move.

_Shipped 2026-08-19_ as `de.norm.events.dataquality` in `events-importer`, closing
[#319](https://github.com/enorm-labs/event-junkie/issues/319). Three definitions were settled in the build and are
worth knowing before reading a number:

- **`missingPrice` excludes a free event and a `price_note`-only one.** Neither is missing a price. Both state one.
- **The worklist returns a lean projection, not an `EventResponse`.** Assembling one resolves artists, promoters and
  genre tags per event — three extra round-trips to attach associations to events selected _because they lack them_.
  `WorklistEntryResponse` carries what a steward needs to decide whether to open something: when it is, where it is,
  and what it is called.
- **A source id that does not resolve gets its own `unresolved-source-<id>` label**, not `manual`.
  `ON DELETE SET NULL` means it should not happen. Folding it into `manual` would attribute a deleted source's events
  to hand curation, and nobody would question that number.

**`unreviewedLicence` joined the pillar on 2026-08-28, and it is the odd one out.**
[#283](https://github.com/enorm-labs/event-junkie/issues/283) added a per-source copyright position. This metric counts
the events whose source has none yet.

Every other metric here measures data a venue published and we mishandled. This one measures work of ours that nobody
did yet. It lives here anyway, for two reasons. A second reporting mechanism for one number is worse than a slightly
wider definition of the pillar. And [#790](https://github.com/enorm-labs/event-junkie/issues/790) showed the cost of an
evidence gap that nothing counts: three of eighty importers recorded a `robots.txt` check, and nobody noticed the rest.

**It counts events and not sources**, which makes it comparable with its neighbours and is the more useful number. A
source with 200 unreviewed events is a different finding from one with 2. Events created by hand have no source, so
they are excluded rather than counted as unreviewed.

`?issue=unreviewedLicence` also works on the worklist endpoint, because the predicate lives on the enum. That answers
"which events am I about to affect" before a review sets a source to `PROHIBITED`.

### Pillar 2 — Prevent (stop regressions) 🟠 medium effort, low risk

- **Golden fixture tests.** Freeze real scraped HTML snippets whose current output is correct (`THE BUTLERS - 40 YEARS … → The Butlers`,
  `GREEN LUNG → Green Lung`,
  `Tango or NonTango → Tango`) so a normalizer tweak that breaks them fails CI. These become the regression net for all four normalizers.
- **Validation gate at the boundary.** A lightweight check in the mapping boundary flags or rejects obviously-bad
  output instead of persisting it silently. An empty artist after stripping, an artist identical to a known non-artist
  pattern, a genre token that is a whole event title. Flagged rows feed the Pillar 1 curation queue.

### Pillar 3 — Fix (recover missing / bad data) 🔴 highest user-visible payoff

- **Title-as-headliner extraction** for Privatclub, Cassiopeia, and Badehaus — the
  work in [issue #321](https://github.com/enorm-labs/event-junkie/issues/321) that reclaims the ~40% of artist-less concerts. Now safe:
  `isNonArtistName` + `stripArtistSuffix` guard against non-artist titles, and Astra/Lido already do exactly this via `buildArtistsForEventType`.
- **One-off backfill pass** over existing rows for the same recoverable fields — artist from title, event type from
  title heuristics. Run it once after the extraction ships, so historical rows benefit too.

### Pillar 4 — Systematize (escape the curated-list treadmill) 🔵 biggest lever

The general answer to weakness #1. An **LLM-assisted enrichment stage that runs _after_ the deterministic
normalizers**, handling only the long tail they cannot. That tail is title → artist extraction, event-type validation,
genre and missing-field enrichment, and bad-value correction for artist and promoter names.

- Deterministic rules stay the fast, free, first pass. The model is the fallback, not the front door.
- Human-in-the-loop. A steward confirms or corrects model output, through the API now and a frontend later (§7). A
  confirmed correction feeds back into the curated vocabulary, which closes the loop Pillar 1 opened. This is where
  the _curated-vocab storage_ decision (§6) bites: a live steward fix needs the vocab to be data.
- **Requires its own ADR — _AI-Assisted Data Quality_**, unnumbered until written (see the numbering note in
  [AGENTS.md](../AGENTS.md)). It introduces a new external dependency, per-import cost and latency, and
  non-deterministic output. All three interact with the scraping-pipeline decisions in ADR-007. The ADR's scope is
  the model and provider choice, where the stage sits in the pipeline, caching and idempotency, and cost controls. It
  also covers how model output is reconciled with the deterministic layer and the human review step.

## 6. Open decisions

These are recorded, not yet resolved — settle them before the pillar that needs each.

- **Curated-vocabulary storage — code vs. data (ADR candidate).** Today the denylists / synonym maps / corrections (`NON_ARTIST_NAMES`, `NAME_CORRECTIONS`,
  genre synonyms, `ACRONYMS`) are hardcoded Kotlin `Set`/`Map`s. A steward fixing an issue therefore means a code edit + PR + redeploy.
    - _Keep as code:_ versioned, unit-tested and PR-reviewed, but every fix is a deploy.
    - _Promote to DB tables (steward-editable):_ a fix lands instantly and closes the loop. It loses PR review and
      testing of vocab changes, and it adds cache invalidation.
    - _Direction:_ undecided. Spike and write the ADR before Pillar 4's human-in-the-loop needs live editing. Blocks
      nothing in Pillars 1–3.
- **Fix / curation surface — API-only for now.** Ship the DQ report and worklist endpoints. A steward fixes through
  the existing `PUT /api/admin/events/{id}` API, Swagger and `.http` files. A dedicated review frontend is deferred to
  the backlogged _"Admin frontend to review, enrich & fix event data"_ item. The DQ work provides the _signal_, and
  that frontend will provide the _fix surface_. Avoid building a second admin app.
- **Dashboard — external BI tool, not a bespoke UI** (§4). Reuse the backlogged Superset/Grafana/Kibana item. Pillar 1
  exposes metrics in a shape those tools consume.

## 7. Sequencing

1. **Pillar 1** — data-quality report + worklist endpoints. Fast, low-risk, and it baselines everything after it.
2. **Pillar 2** — golden fixture tests + boundary validation gate. The safety net that de-risks touching the normalizers.
3. **Pillar 3** — title-as-headliner extraction + backfill. The highest immediate user-visible gain, and safe once
   Pillar 2 exists.
4. **Pillar 4** — AI-assisted enrichment + steward review. The largest lever and the most effort. Gated on the
   _AI-Assisted Data Quality_ ADR (§5) and the §6
   vocab-storage decision.

## 8. Success metrics

Tracked via the Pillar 1 report, per source and overall, and charted over time in an external BI tool (§4):

- **Concert headliner coverage** — % of `CONCERT` events with ≥1 artist. The measured baseline is **96.5%** (§1), not
  the ~60% this section carried before anything was counted.
- **Event-type classification** — % of events _not_ typed `OTHER`.
- **Field completeness** — % with genre / promoter / price where the source exposes them.
- **Curation-queue burn-down** — dropped/flagged items reviewed vs. outstanding.

## 9. How this maps to the backlog

The four pillars are issues [#319](https://github.com/enorm-labs/event-junkie/issues/319) (Measure),
[#320](https://github.com/enorm-labs/event-junkie/issues/320) (Prevent), [#321](https://github.com/enorm-labs/event-junkie/issues/321) (Fix) and
[#322](https://github.com/enorm-labs/event-junkie/issues/322) (Systematize). The admin review frontend and imports-status dashboard are
[#340](https://github.com/enorm-labs/event-junkie/issues/340) and its sub-issues. The Superset/Grafana dashboard (§4)
is [#386](https://github.com/enorm-labs/event-junkie/issues/386). This doc is the _why and in what order_, and the
issues are the _what_.
