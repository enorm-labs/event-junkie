package de.norm.events.event

import de.norm.events.BaseControllerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.expectBody

/**
 * `/actuator/prometheus` actually serves, and serves this application's own meters.
 *
 * The exposure list is one line of YAML, and a typo in it fails in the least visible way available:
 * the application starts, every endpoint works, and the scrape target simply 404s — so the first
 * symptom is an empty dashboard, discovered whenever somebody next looks at it. Asserting it here
 * costs a request.
 *
 * **This test cannot pass without `@AutoConfigureMetrics`, which now sits on [BaseControllerTest]
 * (#965).** Spring Boot forces metrics *export* off in tests, so without it the endpoint 404s in a
 * way that looks exactly like a wrong exposure list — a slow hour. Do not remove it from the base
 * class on the grounds that nothing there obviously uses it: this is what uses it.
 *
 * It also pins the **exposition names**, which are not the meter names: Micrometer converts
 * `bff.events.served` to `bff_events_served_total`, and a Prometheus rule is written against the
 * latter. Testing only the Java-side name would leave the half the alerts actually match unchecked.
 */
class PrometheusEndpointTest : BaseControllerTest() {
    @Test
    fun `the prometheus endpoint is exposed`(): Unit =
        runBlocking {
            rootClient
                .get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk
        }

    @Test
    fun `the free JVM and HTTP meters are present, so the registry is really wired up`(): Unit =
        runBlocking {
            val body =
                rootClient
                    .get()
                    .uri("/actuator/prometheus")
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody<String>()
                    .returnResult()
                    .responseBody!!

            // These come from Spring Boot rather than from this codebase, so their absence means the
            // registry itself is missing rather than that an instrument was not called.
            //
            // **Gauges only, deliberately.** `jvm_gc_pause_seconds` was here first and is flaky by
            // construction: it is a Timer, so it does not appear in the exposition until a GC has
            // actually happened, and a short test run may never trigger one. It passed in isolation
            // and failed in the full build — the classic shape of an assertion on an event rather
            // than on a state. A gauge is registered at startup and is always present.
            listOf("jvm_memory_used_bytes", "jvm_threads_live_threads").forEach {
                assert(body.contains(it)) { "expected the free meter '$it' in the exposition, got:\n${body.take(2000)}" }
            }
        }

    @Test
    fun `bff_events_served appears once an endpoint has served something`(): Unit =
        runBlocking {
            // A counter has no series until it is incremented, so the endpoint is called first. `today`
            // over an empty database returns nothing and still records a zero — which is the property
            // that keeps the series present for an alert to evaluate.
            webTestClient
                .get()
                .uri("/events/today")
                .exchange()
                .expectStatus()
                .isOk

            val body =
                rootClient
                    .get()
                    .uri("/actuator/prometheus")
                    .exchange()
                    .expectBody<String>()
                    .returnResult()
                    .responseBody!!

            // The exposition name, not the meter name: `.` becomes `_` and a counter gains `_total`.
            assert(body.contains("bff_events_served_total")) {
                "expected bff_events_served_total in the exposition, got:\n${body.take(2000)}"
            }
            assert(body.contains("""endpoint="today"""")) {
                "expected the endpoint tag to be present, got:\n${body.take(2000)}"
            }
        }
}
