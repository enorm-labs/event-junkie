package de.norm.events

import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.Tags
import io.micrometer.core.instrument.config.MeterFilter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Tunes the auto-configured HTTP client metrics for the scraper workload.
 *
 * The importer fetches an unbounded set of external venue URLs through the
 * instrumented [org.springframework.web.reactive.function.client.WebClient]. Two
 * tags on the `http.client.requests` meter grow with that set, and both are dropped:
 *
 * - `uri`, one value per distinct URL. One venue import alone produces dozens, and
 *   left unchecked they trip Micrometer's default 100-tag cap (the
 *   `MaximumAllowableTagsMeterFilter` "Reached the maximum number of 'uri' tags" warning).
 * - `client.name`, one value per venue host. With six series families per host it was
 *   ~900 samples, and the importer passed the scrape's sample limit, which drops the
 *   whole scrape (#4326). `importer_run_duration_seconds{source}` and the import logs
 *   already say how each source's fetch went, by our slug rather than by host.
 *
 * `http.client.requests.active`, the in-flight long-task timer the observation adds,
 * is denied outright: nothing reads it, and it repeats every series above three times.
 *
 * Every other meter is untouched — notably `http.server.requests`, whose `uri` is the
 * low-cardinality templated route (e.g. `/api/admin/venues/{id}`) and is worth keeping.
 */
@Configuration
class MetricsConfiguration {
    @Bean
    fun dropHttpClientPerTargetTags(): MeterFilter =
        object : MeterFilter {
            override fun map(id: Meter.Id): Meter.Id =
                if (id.name == HTTP_CLIENT_REQUESTS) {
                    id.replaceTags(Tags.of(id.tags.filterNot { it.key in PER_TARGET_TAGS }))
                } else {
                    id
                }
        }

    @Bean
    fun denyHttpClientActiveRequests(): MeterFilter = MeterFilter.denyNameStartsWith(HTTP_CLIENT_REQUESTS_ACTIVE)

    internal companion object {
        const val HTTP_CLIENT_REQUESTS = "http.client.requests"
        const val HTTP_CLIENT_REQUESTS_ACTIVE = "http.client.requests.active"
        val PER_TARGET_TAGS = setOf("uri", "client.name")
    }
}
