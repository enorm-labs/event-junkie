package de.norm.events.event

import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Component

/**
 * `bff.events.served` — how many events the public API actually hands out, by endpoint.
 *
 * **It counts events, not requests, and that is the whole reason it is worth adding.** Micrometer
 * already gives `http.server.requests` for free, tagged by URI, status and outcome, so a
 * hand-written request counter would be a worse copy of a meter that already exists. What no free
 * meter can say is how much *data* each endpoint returns — which is what distinguishes "the calendar
 * is being used" from "the calendar is being used and has been returning nothing for a week".
 *
 * That is the same argument the importer's `events.written` makes from the other end
 * (PLATFORM_SETUP.md §7): a pipeline can fail while every HTTP-level signal stays perfectly healthy,
 * and the only thing that notices is a business metric.
 *
 * The endpoint tag is a **fixed set of constants** rather than the request path. A tag fed by
 * anything a caller controls is unbounded, and Prometheus creates one time series per distinct
 * combination — the cardinality failure is gradual and looks like the monitoring being slow rather
 * than like a bug here.
 */
@Component
class BffMetrics(
    private val registry: MeterRegistry
) {
    /**
     * Records that [count] events were returned by [endpoint].
     *
     * Zero is recorded rather than skipped, because on this meter zero is the interesting value: an
     * endpoint that is being called and returning nothing is exactly the state worth alerting on,
     * and a counter that simply does not advance cannot be told apart from one nobody is calling.
     * Incrementing by zero keeps the series present so `rate()` stays defined.
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
