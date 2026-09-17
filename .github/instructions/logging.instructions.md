---
applyTo: "events-core/**,events-bff/**,events-importer/**"
paths:
    - "events-core/**"
    - "events-bff/**"
    - "events-importer/**"
---

# Logging

What a log line is for here, which level it takes, and how a value in it becomes a column. The wiring — kotlin-logging, the ECS switch in the chart, the
`RequestLoggingFilter`, the `local` profile — is in [architecture.instructions.md](architecture.instructions.md) § Logging. The store's side — the collector's
field list, the lower-cased query names, the full field table — is in
[docs/ops/PLATFORM_SETUP.md § 7](../../docs/ops/PLATFORM_SETUP.md#7-instrumentation--logging-and-metrics-in-the-applications).

- **A log line is part of the change, not a follow-up.** The reader is someone on the cluster with one `sourceslug` or one `requestid` and a question, so ask
  what they would need before the change is done. A new path that is caught and continued gets a line. A new call out of the process — a venue's page, object
  storage, the translation API — gets its outcome. A new scheduled pass logs its start, its result and its failure. A new scraper gets nothing of its own: the
  funnel in `EventImportService.importFromSource` already puts the source and the run id on every line below it. A line that stops having a reader is deleted.
- **Levels, by who has to act.**
    - `ERROR` — a run, a pass or a request failed as a whole, or a configuration the code cannot heal by retrying (`MISCONFIGURED`). Someone reads it.
    - `WARN` — the run continued with less: an event dropped, a duplicate skipped, a translation refused, an object unreadable. Bounded by the data, and
      normal on every run of some venues.
    - `INFO` — the narrative of a run or a request: start, counts, outcome, one access line. Bounded per run, never per event. The cluster runs at INFO, so
      this is what production shows.
    - `DEBUG` — per event, per item. It never reaches the cluster: the created-or-updated line is DEBUG on purpose (#984), because one venue alone would add
      227 lines to a run. A value a production question needs does not go here.
    - `TRACE` is unused.
- **The exception is the argument, never `${e.message}` alone.** `logger.warn(e) { "…" }`: the ECS formatter writes `errorType` and `stackTrace` as fields of
  their own, and a message that interpolates the text keeps neither the type nor the trace. A line without an exception is right only where there is none.
- **Context is set once, at the funnel. A value about one line is a payload.** `LogContext.forImportRun` and `forPage` in the importer and
  `RequestLoggingFilter` in the BFF put `sourceSlug`, `importRunId`, `url` and `requestId` in the MDC for everything below them. `forPage` wraps one parse,
  never a fetch, and nothing else touches the MDC.
  A value that belongs to one line — a status, an id, the URL you fetched — goes through `logger.at(Level.X) { message = …; payload = mapOf(LogFields.X to v) }`.
  Never `MDC.put`, which is thread-local and gone at the next suspension. Never `logger.info("… {} …", v)`, which formats the argument into the text and
  produces the unfilterable line the payload exists to avoid. Never one key both ways: the formatter throws.
- **A field name is a constant, and it lives in three places.** `LogFields` (importer) or `LogContextConfiguration` (BFF), the `transform/parse_structured_logs`
  list in `deploy/clusters/base/collector.yaml`, and the table in PLATFORM_SETUP § 7. A name the collector does not list produces no error and no column, and
  nothing checks that the three agree, so a new field is a change to all three in one commit. Type a number as a number: OpenObserve settles a column's type
  from its first row. Reuse a name across the two services only when the value means the same thing (`httpStatus` does); a new meaning gets a new name. High
  cardinality is acceptable on a line that fires rarely (`storageKey`), never on one per request or per event.
- **What never goes in a line.** A client IP, a query string or any other user-typed input as a field, a credential, a page body.
  [docs/LEGAL.md § 7.5](../../docs/LEGAL.md#75-logging) is the standing instruction, and a change to what is logged is a change to the privacy notice — see
  [AGENTS.md § Privacy & GDPR](../../AGENTS.md#privacy--gdpr--re-check-when-infrastructure-or-features-change).
- **An alert reads a meter. A log line answers the question that comes after the alarm.** Every rule in `deploy/alerts/alerts.json` reads a metrics stream and
  none reads a log level. A condition an operator must hear about gets a counter or a gauge (`ImporterMetrics`, `BffMetrics`) and a rule; the line beside it
  carries what the rule cannot — the URL, the key, the id.
- **A line that is the deliverable is tested.** `LogContextTest` and `LogContextPropagationTest` show the shape: a Logback `ListAppender` on the logger, then
  assert the level, the MDC map and the key-value pairs. The access line, each payload field and each context boundary already have one; a new field, or a new
  line whose absence nobody would notice, gets the same.
