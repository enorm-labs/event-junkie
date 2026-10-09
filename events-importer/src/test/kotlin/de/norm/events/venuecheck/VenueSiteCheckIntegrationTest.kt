package de.norm.events.venuecheck

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.BaseControllerTest
import de.norm.events.scraper.ScraperHttpClientConfig
import de.norm.events.scraper.ScraperProperties
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.flow
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.time.LocalDate

/**
 * [VenueSiteCheckService] and the admin list against a real database and a local server (#2812): who is probed,
 * how the run of failures counts, when a venue reaches the list, and what takes it off again. The schedule is off
 * in tests (`app.scheduling.enabled: false`), so each test builds the service.
 */
class VenueSiteCheckIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var store: VenueSiteCheckStore

    private lateinit var server: MockWebServer

    @Volatile
    private var deadStatus = 404

    private lateinit var appender: ListAppender<ILoggingEvent>
    private val serviceLogger = LoggerFactory.getLogger(VenueSiteCheckService::class.java) as Logger

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server = MockWebServer()
            server.dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.target == "/dead") MockResponse.Builder().code(deadStatus).build() else MockResponse.Builder().code(200).build()
                }
            server.start()
            appender = ListAppender<ILoggingEvent>().apply { start() }
            serviceLogger.addAppender(appender)

            insertVenue("dead-club", website = url("/dead"))
            insertVenue("live-club", website = url("/"), programme = "https://ra.co/clubs/1")
            insertVenue("ra-only", website = null, programme = "https://ra.co/clubs/2")
            val imported = insertVenue("imported-club", website = url("/dead"))
            databaseClient
                .sql("INSERT INTO events.event_source (venue_id, name, slug, url, source_type) VALUES ($imported, 'i', 'i', 'https://i.test/', 'CASSIOPEIA')")
                .await()
            insertVenue("closed-club", website = url("/dead"), closedOn = LocalDate.parse("2026-01-31"))
        }

    @AfterEach
    fun tearDown() {
        serviceLogger.detachAppender(appender)
        server.close()
    }

    @Test
    fun `probes only venues without an importer or a closure, and skips a forbidden host without counting it`(): Unit =
        runBlocking {
            val summary = service().runOnce().shouldNotBeNull()

            summary shouldBe SiteCheckSummary(checked = 3, ok = 1, failed = 1, skipped = 1, refused = 0, needsReview = 0)
            check("ra-only").shouldNotBeNull().let {
                it.first shouldBe "SKIPPED"
                it.second shouldBe 0
            }
            check("imported-club").shouldBeNull()
            check("closed-club").shouldBeNull()
            server.requestCount shouldBe 2
        }

    @Test
    fun `three failures in a row log a WARN and put the venue on the list the admin API returns`(): Unit =
        runBlocking {
            val service = service()
            repeat(2) { service.runOnce() }
            store.findNeedsReview(VenueSiteCheckService.REVIEW_THRESHOLD).shouldBeEmpty()
            appender.list.filter { it.level == Level.WARN }.shouldBeEmpty()

            service.runOnce().shouldNotBeNull().needsReview shouldBe 1

            val warn = appender.list.single { it.level == Level.WARN }
            warn.formattedMessage shouldContain "dead-club"
            warn.formattedMessage shouldContain "HTTP"
            warn.keyValuePairs.associate { it.key to it.value }["httpStatus"] shouldBe 404

            webTestClient
                .get()
                .uri("/api/admin/venues/needs-review")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].slug")
                .isEqualTo("dead-club")
                .jsonPath("$[0].outcome")
                .isEqualTo("HTTP")
                .jsonPath("$[0].httpStatus")
                .isEqualTo(404)
                .jsonPath("$[0].consecutiveFailures")
                .isEqualTo(3)
                .jsonPath("$[0].url")
                .isEqualTo(url("/dead"))
        }

    @Test
    fun `a review after the failures began takes the venue off the list`(): Unit =
        runBlocking {
            val service = service()
            repeat(3) { service.runOnce() }
            store.findNeedsReview(VenueSiteCheckService.REVIEW_THRESHOLD) shouldHaveSize 1

            databaseClient.sql("UPDATE events.venue SET reviewed_at = now() WHERE slug = 'dead-club'").await()

            store.findNeedsReview(VenueSiteCheckService.REVIEW_THRESHOLD).shouldBeEmpty()
        }

    @Test
    fun `a site that answers again ends the run of failures`(): Unit =
        runBlocking {
            val service = service()
            repeat(3) { service.runOnce() }
            deadStatus = 200

            service.runOnce()

            check("dead-club").shouldNotBeNull().let {
                it.first shouldBe "OK"
                it.second shouldBe 0
            }
            failingSince("dead-club").shouldBeNull()
            store.findNeedsReview(VenueSiteCheckService.REVIEW_THRESHOLD).shouldBeEmpty()
        }

    @Test
    fun `a site that answers 403 every month is skipped, never listed, and counted in the summary line`(): Unit =
        runBlocking {
            deadStatus = 403
            val service = service()

            val summaries = List(3) { service.runOnce().shouldNotBeNull() }

            summaries.last() shouldBe SiteCheckSummary(checked = 3, ok = 1, failed = 0, skipped = 2, refused = 1, needsReview = 0)
            check("dead-club").shouldNotBeNull().let {
                it.first shouldBe "SKIPPED"
                it.second shouldBe 0
            }
            httpStatus("dead-club") shouldBe 403
            failingSince("dead-club").shouldBeNull()
            store.findNeedsReview(VenueSiteCheckService.REVIEW_THRESHOLD).shouldBeEmpty()
            appender.list.filter { it.level == Level.WARN }.shouldBeEmpty()
            appender.list.filter { it.formattedMessage.contains("dead-club refused the probe with 403") } shouldHaveSize 3
            appender.list.last().formattedMessage shouldContain "2 skipped (1 refused with 403 or 429)"
        }

    @Test
    fun `a 403 between failures neither counts nor ends the run, like any skip`(): Unit =
        runBlocking {
            val service = service()
            service.runOnce()
            val runStart = failingSince("dead-club").shouldNotBeNull()

            deadStatus = 403
            service.runOnce()
            check("dead-club").shouldNotBeNull().second shouldBe 1
            failingSince("dead-club") shouldBe runStart

            deadStatus = 404
            service.runOnce()
            store.findNeedsReview(VenueSiteCheckService.REVIEW_THRESHOLD).shouldBeEmpty()

            service.runOnce().shouldNotBeNull().needsReview shouldBe 1
            check("dead-club").shouldNotBeNull().second shouldBe 3
            failingSince("dead-club") shouldBe runStart
        }

    @Test
    fun `no pass writes closed_on or reviewed_at`(): Unit =
        runBlocking {
            val service = service()
            repeat(3) { service.runOnce() }

            databaseClient
                .sql("SELECT count(*) AS n FROM events.venue WHERE reviewed_at IS NOT NULL OR (closed_on IS NOT NULL AND slug <> 'closed-club')")
                .map { row, _ -> row.get("n", Number::class.java)!!.toLong() }
                .awaitSingle() shouldBe 0L
        }

    private fun service() = VenueSiteCheckService(store, VenueSiteProber(testWebClient()))

    private fun url(path: String) = server.url(path).toString()

    /** Outcome and run of failures for [slug], or null when the venue has no check row. */
    private suspend fun check(slug: String): Pair<String, Int>? =
        databaseClient
            .sql(
                "SELECT c.outcome, c.consecutive_failures FROM events.venue_site_check c " +
                    "JOIN events.venue v ON v.id = c.venue_id WHERE v.slug = :slug"
            ).bind("slug", slug)
            .map { row, _ -> row.get("outcome", String::class.java)!! to row.get("consecutive_failures", Number::class.java)!!.toInt() }
            .flow()
            .toList()
            .singleOrNull()

    private suspend fun httpStatus(slug: String): Int? =
        databaseClient
            .sql("SELECT c.http_status FROM events.venue_site_check c JOIN events.venue v ON v.id = c.venue_id WHERE v.slug = :slug")
            .bind("slug", slug)
            .map { row, _ -> java.util.Optional.ofNullable(row.get("http_status", Number::class.java)?.toInt()) }
            .awaitSingle()
            .orElse(null)

    private suspend fun failingSince(slug: String): Instant? =
        databaseClient
            .sql("SELECT c.failing_since FROM events.venue_site_check c JOIN events.venue v ON v.id = c.venue_id WHERE v.slug = :slug")
            .bind("slug", slug)
            .map { row, _ -> java.util.Optional.ofNullable(row.get("failing_since", Instant::class.java)) }
            .awaitSingle()
            .orElse(null)

    private suspend fun insertVenue(
        slug: String,
        website: String?,
        programme: String? = null,
        closedOn: LocalDate? = null
    ): Long {
        var spec =
            databaseClient.sql(
                "INSERT INTO events.venue (name, slug, website_url, programme_url, closed_on) " +
                    "VALUES (:slug, :slug, :website, :programme, :closedOn) RETURNING id"
            )
        spec = spec.bind("slug", slug)
        spec = website?.let { spec.bind("website", it) } ?: spec.bindNull("website", String::class.java)
        spec = programme?.let { spec.bind("programme", it) } ?: spec.bindNull("programme", String::class.java)
        spec = closedOn?.let { spec.bind("closedOn", it) } ?: spec.bindNull("closedOn", LocalDate::class.java)
        return spec.map { row, _ -> row.get("id", Number::class.java)!!.toLong() }.awaitSingle()
    }

    /** The scraper client without the robots check: MockWebServer's robots path is not what this tests. */
    private fun testWebClient(): WebClient {
        val properties = ScraperProperties(politeDelayMillis = 0)
        val config = ScraperHttpClientConfig()
        return config.scraperBaseWebClient(
            WebClient.builder(),
            properties,
            config.scraperConnectionProvider(properties),
            config.perHostThrottlingFilter(properties)
        )
    }
}
