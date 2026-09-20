package de.norm.events.event

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * `bff.events.served`: how many events the public API hands out, by endpoint. Events, not
 * requests: `http.server.requests` already counts those, and what no free meter says is how
 * much data each endpoint returns, which distinguishes "the calendar is used" from "the
 * calendar has returned nothing for a week", the same argument as the importer's
 * `events.written` (PLATFORM_SETUP.md §7). The endpoint tag is a fixed set of constants: a tag
 * fed by anything a caller controls is unbounded.
 */
@Component
class BffMetrics(
    private val registry: MeterRegistry
) {
    /**
     * Records that [count] events were returned by [endpoint]. Zero is recorded rather than skipped:
     * an endpoint returning nothing is the state worth alerting on, and incrementing by zero keeps
     * the series present so `rate()` stays defined.
     */
    fun recordServed(
        endpoint: String,
        count: Int
    ) = registry
        .counter(EVENTS_SERVED, TAG_ENDPOINT, endpoint)
        .increment(count.toDouble())

    companion object {
        const val EVENTS_SERVED = "bff.events.served"
        const val TAG_ENDPOINT = "endpoint"

        /** `GET /events` — the filtered search the SPA's list view uses. */
        const val ENDPOINT_SEARCH = "search"

        /** `GET /events/today` — the landing page. */
        const val ENDPOINT_TODAY = "today"

        /** `GET /events/calendar` — the month view. */
        const val ENDPOINT_CALENDAR = "calendar"

        /** `GET /events/{slug}` — one event, so this advances by one or throws. */
        const val ENDPOINT_DETAIL = "detail"
    }
}
