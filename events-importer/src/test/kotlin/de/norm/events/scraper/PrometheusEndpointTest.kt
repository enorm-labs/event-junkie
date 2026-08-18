package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import org.junit.jupiter.api.Test
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.test.web.reactive.server.expectBody

/**
 * `/actuator/prometheus` serves the importer's own business meters.
 *
 * The BFF has the same test for the same reason; this one additionally asserts that the **gauges
 * exist before anything has run**. That is the property the zero-events alert depends on: a gauge
 * registered only after the first import would be absent for up to 24 hours after a restart, and an
 * alert cannot evaluate a series that is not there.
 *
 * **`@AutoConfigureMetrics` is not decoration** — Spring Boot forces
 * `management.defaults.metrics.export.enabled` to false in tests, so without it the endpoint 404s in
 * a way that looks exactly like a wrong exposure list. Tests only; production is unaffected.
 */
@AutoConfigureMetrics
class PrometheusEndpointTest : BaseControllerTest() {
    private fun exposition(): String =
        webTestClient
            .get()
            .uri("/actuator/prometheus")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<String>()
            .returnResult()
            .responseBody!!

    @Test
    fun `the endpoint is exposed and carries the free JVM meters`() {
        val body = exposition()

        assert(body.contains("jvm_memory_used_bytes")) {
            "expected the registry to be wired up; got:\n${body.take(2000)}"
        }
    }

    /**
     * Exposition names and labels, not meter names — and the difference is not cosmetic here. This
     * assertion is what caught `db.events.total` being published as `db_events`: Prometheus reserves
     * `_total` for counters, so Micrometer strips the suffix, silently, while every rule written from
     * the documented name matched nothing. Asserting the Kotlin-side constants would have passed.
     */
    @Test
    fun `the polled gauges are present before any import has run`() {
        val body = exposition()

        // `db_events` with a `horizon` label, not `db_events_total`: Prometheus reserves the
        // `_total` suffix for counters and Micrometer strips it, so the documented name could never
        // have appeared here. See ImporterMetrics.DB_EVENTS.
        listOf("""db_events{horizon="all"}""", """db_events{horizon="future"}""", "importer_source_running").forEach {
            assert(body.contains(it)) { "expected gauge '$it' in the exposition; got:\n${body.take(3000)}" }
        }
    }
}
