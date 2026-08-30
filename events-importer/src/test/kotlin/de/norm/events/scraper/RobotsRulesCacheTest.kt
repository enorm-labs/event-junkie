package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * Tests for [RobotsRulesCache] against a local [MockWebServer].
 *
 * The status-code rules are the half most worth asserting. RFC 9309 gives 4xx and 5xx opposite
 * meanings — no rules exist, versus rules exist and could not be read — and getting them the wrong
 * way round produces a check that passes everything, silently.
 */
class RobotsRulesCacheTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    private fun cache(
        clock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        ttl: Duration = Duration.ofHours(24)
    ): RobotsRulesCache {
        val properties = ScraperProperties(politeDelayMillis = 0, robotsCacheTtl = ttl)
        val config = ScraperHttpClientConfig()
        return RobotsRulesCache(
            webClient =
                config.scraperBaseWebClient(
                    webClientBuilder = WebClient.builder(),
                    scraperProperties = properties,
                    throttle = config.perHostThrottlingFilter(properties)
                ),
            scraperProperties = properties,
            clock = clock
        )
    }

    private fun enqueueRobots(body: String) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body(body)
                .build()
        )
    }

    private fun url(path: String) = server.url(path).toString()

    @Nested
    inner class Parsing {
        @Test
        fun `honours a disallow that names our product token`() =
            runTest {
                enqueueRobots("User-agent: EventJunkie\nDisallow: /private\n")

                cache().check(url("/private")).allowed shouldBe false
            }

        @Test
        fun `permits a path outside the disallowed prefix`() =
            runTest {
                enqueueRobots("User-agent: EventJunkie\nDisallow: /private\n")

                cache().check(url("/events")).allowed shouldBe true
            }

        @Test
        fun `honours a wildcard group when no group names us`() =
            runTest {
                enqueueRobots("User-agent: *\nDisallow: /calendarfile\n")

                cache().check(url("/calendarfile/12")).allowed shouldBe false
            }

        @Test
        fun `lets an Allow beat a broader Disallow`() =
            runTest {
                enqueueRobots("User-agent: *\nDisallow: /\nAllow: /events\n")

                val rules = cache()
                rules.check(url("/events/tonight")).allowed shouldBe true
            }

        @Test
        fun `records which robots txt answered`() =
            runTest {
                enqueueRobots("User-agent: *\nDisallow:\n")

                cache().check(url("/events")).robotsTxtUrl shouldBe "${server.url("/")}robots.txt"
            }
    }

    @Nested
    inner class StatusCodes {
        @Test
        fun `a 404 means no rules exist, so everything is permitted`() =
            runTest {
                server.enqueue(MockResponse.Builder().code(404).build())

                val check = cache().check(url("/anything"))

                check.allowed shouldBe true
                // Nothing answered, so there is no file to cite as the source of the decision.
                check.robotsTxtUrl shouldBe null
            }

        @Test
        fun `a 500 means the rules exist and could not be read, which disallows everything`() =
            runTest {
                server.enqueue(MockResponse.Builder().code(500).build())

                cache().check(url("/anything")).allowed shouldBe false
            }

        @Test
        fun `a 5xx reports the status, so the refusal is not mistaken for a venue's rule`() =
            runTest {
                server.enqueue(MockResponse.Builder().code(503).build())

                val check = cache().check(url("/anything"))

                check.allowed shouldBe false
                // The whole point of the field: the venue forbade nothing, its server is broken.
                check.unreadableStatus shouldBe 503
                check.robotsTxtUrl shouldBe null
            }

        @Test
        fun `a real Disallow reports no status, because a file was read`() =
            runTest {
                enqueueRobots("User-agent: *\nDisallow: /private\n")

                val check = cache().check(url("/private"))

                check.allowed shouldBe false
                check.unreadableStatus shouldBe null
                check.robotsTxtUrl shouldNotBe null
            }
    }

    @Nested
    inner class Caching {
        @Test
        fun `reads robots txt once for many requests to the same host`() =
            runTest {
                enqueueRobots("User-agent: *\nDisallow: /private\n")
                val rules = cache()

                rules.check(url("/events"))
                rules.check(url("/events/one"))
                rules.check(url("/events/two"))

                // One robots.txt fetch, and nothing else: a per-detail-page re-read is exactly the
                // load this cache exists to keep off a venue's server.
                server.requestCount shouldBe 1
            }

        @Test
        fun `reads it again once the ttl has passed`() =
            runTest {
                enqueueRobots("User-agent: *\nDisallow: /private\n")
                enqueueRobots("User-agent: *\nDisallow: /private\n")

                // A clock that has already moved past the TTL when the second call reads it.
                var current = Instant.EPOCH
                val movingClock =
                    object : Clock() {
                        override fun getZone() = ZoneOffset.UTC

                        override fun withZone(zone: java.time.ZoneId?) = this

                        override fun instant(): Instant = current
                    }
                val rules = cache(clock = movingClock, ttl = Duration.ofHours(1))

                rules.check(url("/events"))
                current = Instant.EPOCH.plus(Duration.ofHours(2))
                rules.check(url("/events"))

                server.requestCount shouldBe 2
            }
    }

    @Nested
    inner class Failures {
        @Test
        fun `permits everything when the host cannot be reached at all`() =
            runTest {
                server.close()

                // Silence is not a prohibition: treating an unreachable host as disallowed would
                // stop imports on any network blip.
                cache().check(url("/events")).allowed shouldBe true
            }

        @Test
        fun `permits a url it cannot parse rather than failing the import`() =
            runTest {
                cache().check("not-a-url").allowed shouldBe true
            }
    }
}
