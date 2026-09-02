package de.norm.events.scraper

/**
 * Field names for values named at a **call site** rather than carried by the run (#945).
 *
 * Passed as `logger.at(Level.INFO) { payload = … }`, carried by SLF4J as key-value pairs, and
 * written by Spring's ECS formatter to the same top level [LogContext.SOURCE_SLUG] lands in —
 * `LogContextTest.LoggedPayload` asserts that position rather than assuming it.
 *
 * **Constants because every failure here is silent**: a misspelt name is no compile error and no
 * log error, so the line appears without the field. These strings are repeated in
 * `transform/parse_structured_logs` in `deploy/clusters/base/collector.yaml` and in the BFF's
 * `LogContextConfiguration`, and nothing checks they agree — see `PLATFORM_SETUP.md` §7.
 */
object LogFields {
    const val URL = "url"

    /**
     * The HTTP status, as an **Int** — OpenObserve types a column from its first row, so one
     * logged as a string breaks every later range query. Spans both directions: here a venue
     * answering us, in the BFF us answering a browser, separated by `service_name`.
     */
    const val HTTP_STATUS = "httpStatus"

    /** Our database id, and in practice always an event we **removed** — see §7 (#984). */
    const val EVENT_ID = "eventId"

    /** The venue's id for an event, not ours. On a duplicate skipped and a stale removal (#984). */
    const val EVENT_SOURCE_ID = "eventSourceId"
}
