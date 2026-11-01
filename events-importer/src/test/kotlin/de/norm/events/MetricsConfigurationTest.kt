package de.norm.events

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.observation.ObservationRegistry
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody

/** A real instrumented [WebClient] call through the filters, so the meter and tag names are Spring's, not ours. */
class MetricsConfigurationTest {
    private lateinit var server: MockWebServer
    private val registry = SimpleMeterRegistry()

    @BeforeEach
    fun start() {
        val configuration = MetricsConfiguration()
        registry.config().meterFilter(configuration.dropHttpClientPerTargetTags())
        registry.config().meterFilter(configuration.denyHttpClientActiveRequests())
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
    }

    private suspend fun fetch(registry: SimpleMeterRegistry = this.registry) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("ok")
                .build()
        )
        val observations = ObservationRegistry.create()
        observations.observationConfig().observationHandler(DefaultMeterObservationHandler(registry))
        WebClient
            .builder()
            .observationRegistry(observations)
            .build()
            .get()
            .uri(server.url("/programme/2026-11-01").toString())
            .retrieve()
            .awaitBody<String>()
    }

    @Test
    fun `http client requests keep their outcome but carry no uri and no client name`() =
        runTest {
            fetch()

            val timer = registry.find(MetricsConfiguration.HTTP_CLIENT_REQUESTS).timer().shouldNotBeNull()
            val keys = timer.id.tags.map { it.key }
            keys shouldNotContain "uri"
            keys shouldNotContain "client.name"
            keys shouldContain "status"
            keys shouldContain "outcome"
            timer.count() shouldBe 1
        }

    @Test
    fun `the in-flight long-task timer is never registered`() =
        runTest {
            fetch()

            registry.meters.filter { it.id.name == MetricsConfiguration.HTTP_CLIENT_REQUESTS_ACTIVE }.shouldBeEmpty()
        }

    @Test
    fun `without the filters both exist, so the two tests above prove something`() =
        runTest {
            val unfiltered = SimpleMeterRegistry()
            fetch(unfiltered)

            val names = unfiltered.meters.map { it.id.name }
            names shouldContain MetricsConfiguration.HTTP_CLIENT_REQUESTS_ACTIVE
            unfiltered
                .find(MetricsConfiguration.HTTP_CLIENT_REQUESTS)
                .timer()
                .shouldNotBeNull()
                .id
                .tags
                .map { it.key } shouldContain "client.name"
        }

    @Test
    fun `server requests keep their templated uri`() {
        registry.timer("http.server.requests", "uri", "/api/admin/venues/{id}")

        registry
            .find("http.server.requests")
            .timer()
            .shouldNotBeNull()
            .id
            .getTag("uri") shouldBe "/api/admin/venues/{id}"
    }
}
