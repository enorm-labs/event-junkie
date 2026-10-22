package de.norm.events.scraper

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import ch.qos.logback.classic.Level as LogbackLevel

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

    private fun properties(
        ttl: Duration = Duration.ofHours(24),
        maxCrawlDelay: Duration = Duration.ofSeconds(300)
    ): ScraperProperties = ScraperProperties(politeDelayMillis = 0, robotsCacheTtl = ttl, maxCrawlDelay = maxCrawlDelay)

    private fun cache(
        clock: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
        ttl: Duration = Duration.ofHours(24),
        maxCrawlDelay: Duration = Duration.ofSeconds(300),
        properties: ScraperProperties = properties(ttl, maxCrawlDelay)
    ): RobotsRulesCache {
        val config = ScraperHttpClientConfig()
        return RobotsRulesCache(
            webClient =
                config.scraperBaseWebClient(
                    webClientBuilder = WebClient.builder(),
                    scraperProperties = properties,
                    connectionProvider = config.scraperConnectionProvider(properties),
                    throttle = config.perHostThrottlingFilter(properties)
                ),
            scraperProperties = properties,
            clock = clock
        )
    }

    /** A clock the test moves by hand, so a cached entry can be read before and after its TTL. */
    private class MovingClock(
        var current: Instant = Instant.EPOCH
    ) : Clock() {
        override fun getZone(): ZoneOffset = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?): Clock = this

        override fun instant(): Instant = current

        fun advance(by: Duration) {
            current = current.plus(by)
        }
    }

    private fun enqueueStatus(code: Int) = server.enqueue(MockResponse.Builder().code(code).build())

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
    inner class CrawlDelay {
        @Test
        fun `reads a Crawl-delay from the wildcard group`() =
            runTest {
                enqueueRobots("User-agent: *\nCrawl-delay: 20\nDisallow: /admin/\n")

                cache().check(url("/api/events")).crawlDelay shouldBe 20.seconds
            }

        @Test
        fun `prefers the delay in the group that names us`() =
            runTest {
                enqueueRobots("User-agent: *\nCrawl-delay: 20\n\nUser-agent: EventJunkie\nCrawl-delay: 5\n")

                cache().check(url("/events")).crawlDelay shouldBe 5.seconds
            }

        @Test
        fun `ignores a delay set only for another user agent`() =
            runTest {
                enqueueRobots("User-agent: Googlebot\nCrawl-delay: 30\n\nUser-agent: *\nDisallow: /admin/\n")

                cache().check(url("/events")).crawlDelay shouldBe null
            }

        @Test
        fun `reads a fractional delay`() =
            runTest {
                enqueueRobots("User-agent: *\nCrawl-delay: 0.5\n")

                cache().check(url("/events")).crawlDelay shouldBe 500.milliseconds
            }

        @Test
        fun `reports no delay where the file sets none`() =
            runTest {
                enqueueRobots("User-agent: *\nDisallow: /private\n")

                cache().check(url("/events")).crawlDelay shouldBe null
            }

        @Test
        fun `honours a delay up to the configured maximum in full`() =
            runTest {
                enqueueRobots("User-agent: *\nCrawl-delay: 60\n")

                cache(maxCrawlDelay = Duration.ofSeconds(60)).check(url("/events")).crawlDelay shouldBe 60.seconds
            }

        @Test
        fun `reads a delay above the maximum as a complete disallow`() =
            runTest {
                // Waiting less than the host asks is not honouring it, so a delay we will not wait
                // is a delay we do not fetch under.
                enqueueRobots("User-agent: *\nCrawl-delay: 3600\n")

                cache().check(url("/events")).allowed shouldBe false
            }

        @Test
        fun `reports no delay where robots txt could not be read`() =
            runTest {
                server.enqueue(MockResponse.Builder().code(503).build())

                cache().check(url("/events")).crawlDelay shouldBe null
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

                val clock = MovingClock()
                val rules = cache(clock = clock, ttl = Duration.ofHours(1))

                rules.check(url("/events"))
                clock.advance(Duration.ofHours(2))
                rules.check(url("/events"))

                server.requestCount shouldBe 2
            }

        @Test
        fun `keeps a file it read for the whole day`() =
            runTest {
                enqueueRobots("User-agent: *\nDisallow: /private\n")
                val clock = MovingClock()
                val rules = cache(clock = clock)

                rules.check(url("/events"))
                clock.advance(Duration.ofHours(23))
                rules.check(url("/events"))

                server.requestCount shouldBe 1
            }

        @Test
        fun `keeps a 404 for the whole day, because no rules is an answer`() =
            runTest {
                enqueueStatus(404)
                val clock = MovingClock()
                val rules = cache(clock = clock)

                rules.check(url("/events"))
                clock.advance(Duration.ofHours(23))
                rules.check(url("/events"))

                server.requestCount shouldBe 1
            }

        @Test
        fun `reads a 5xx again after an hour, so the next retry is not failed by the cache`() =
            runTest {
                // #3754: a 503 held for 24 h failed every six-hourly retry, though the venue recovered.
                enqueueStatus(503)
                enqueueRobots("User-agent: *\nDisallow:\n")
                val clock = MovingClock()
                val rules = cache(clock = clock)

                rules.check(url("/events")).allowed shouldBe false
                clock.advance(Duration.ofMinutes(59))
                // Still inside the hour: one run's detail pages do not each re-read the file.
                rules.check(url("/events")).allowed shouldBe false
                server.requestCount shouldBe 1

                clock.advance(Duration.ofMinutes(2))
                val recovered = rules.check(url("/events"))

                server.requestCount shouldBe 2
                recovered.allowed shouldBe true
                recovered.unreadableStatus shouldBe null
            }

        @Test
        fun `holds a 5xx for an hour unless configured otherwise`() {
            ScraperProperties().robotsUnreadableCacheTtl shouldBe Duration.ofHours(1)
        }
    }

    @Nested
    inner class Logging {
        private lateinit var appender: ListAppender<ILoggingEvent>
        private val cacheLogger = LoggerFactory.getLogger(RobotsRulesCache::class.java) as Logger

        @BeforeEach
        fun attach() {
            appender = ListAppender<ILoggingEvent>().apply { start() }
            cacheLogger.addAppender(appender)
        }

        @AfterEach
        fun detach() {
            cacheLogger.detachAppender(appender)
            appender.stop()
            MDC.clear()
        }

        @Test
        fun `logs a 5xx met through the scraper client as a WARN with the status and the source`() =
            runTest {
                // Through the client a scraper uses, because that is where the first fetch of a run
                // happens, behind the filter's own coroutine.
                enqueueStatus(503)
                val properties = properties()
                val config = ScraperHttpClientConfig()
                val client =
                    config.scraperWebClient(
                        webClientBuilder = WebClient.builder(),
                        scraperProperties = properties,
                        connectionProvider = config.scraperConnectionProvider(properties),
                        throttle = config.perHostThrottlingFilter(properties),
                        rulesCache = cache(properties = properties)
                    )

                withContext(LogContext.forImportRun("tipi-am-kanzleramt")) {
                    shouldThrow<RobotsDisallowedException> {
                        client
                            .get()
                            .uri(url("/events"))
                            .retrieve()
                            .awaitBody<String>()
                    }
                }

                val line = appender.list.single { it.level == LogbackLevel.WARN }
                line.formattedMessage shouldContain "HTTP 503"
                line.formattedMessage shouldContain "read again after PT1H"
                line.keyValuePairs.associate { it.key to it.value }[LogFields.HTTP_STATUS] shouldBe 503
                line.mdcPropertyMap[LogContext.SOURCE_SLUG] shouldBe "tipi-am-kanzleramt"
            }

        @Test
        fun `logs a 404 at INFO, because no rules is not a problem`() =
            runTest {
                enqueueStatus(404)

                cache().check(url("/events"))

                appender.list.single().level shouldBe LogbackLevel.INFO
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
