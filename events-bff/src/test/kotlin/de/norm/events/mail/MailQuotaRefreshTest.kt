package de.norm.events.mail

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class MailQuotaRefreshTest {
    private val server = MockWebServer().apply { start() }
    private val registry = SimpleMeterRegistry()
    private val now = Instant.parse("2026-10-29T18:00:00Z")
    private val logger = LoggerFactory.getLogger(MailQuotaRefresh::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    private fun readerWith(clock: Clock) =
        MailQuotaRefresh(
            WebClient.builder(),
            JsonMapper(),
            clock,
            MailQuotaMetrics(registry, 10000),
            KEY,
            PROJECT,
            server.url("/regions/fr-par").toString()
        )

    private val refresh = readerWith(Clock.fixed(now, ZoneOffset.UTC))

    @BeforeEach
    fun attachAppender() = logger.addAppender(appender)

    @AfterEach
    fun stop() {
        logger.detachAppender(appender)
        server.close()
    }

    private fun warnings() = appender.list.filter { it.level == Level.WARN }

    private fun gauge(name: String) =
        registry
            .get(name)
            .gauge()
            .value()
            .toLong()

    private fun answer(body: String) =
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body(body)
                .build()
        )

    @Test
    fun `every gauge is registered before the first read`() {
        listOf(MailQuotaMetrics.SENT, MailQuotaMetrics.INCLUDED, MailQuotaMetrics.LAST_SUCCESS).forEach { gauge(it) shouldBe 0 }
        gauge(MailQuotaMetrics.LIMIT) shouldBe 10000
    }

    @Test
    fun `a read sets the count, the included mails and the time`() =
        runTest {
            answer("""{"project_id": "$PROJECT", "monthly_emails_count": 19}""")
            answer("""{"offer_subscriptions": [{"included_monthly_emails": 300}], "total_count": 1}""")

            refresh.refresh()

            gauge(MailQuotaMetrics.SENT) shouldBe 19
            gauge(MailQuotaMetrics.INCLUDED) shouldBe 300
            gauge(MailQuotaMetrics.LAST_SUCCESS) shouldBe now.epochSecond
            val consumption = server.takeRequest(1, TimeUnit.SECONDS).shouldNotBeNull()
            consumption.url.encodedPath shouldBe "/regions/fr-par/project-consumption"
            consumption.url.queryParameter("project_id") shouldBe PROJECT
            consumption.headers["X-Auth-Token"] shouldBe KEY
            consumption.url.toString() shouldNotContain KEY
            server
                .takeRequest(1, TimeUnit.SECONDS)
                .shouldNotBeNull()
                .url.encodedPath shouldBe "/regions/fr-par/offer-subscriptions"
        }

    @Test
    fun `a refused read keeps the last values`() =
        runTest {
            answer("""{"monthly_emails_count": 19}""")
            answer("""{"offer_subscriptions": [{"included_monthly_emails": 300}]}""")
            refresh.refresh()

            server.enqueue(
                MockResponse
                    .Builder()
                    .code(403)
                    .body("""{"type": "permissions_denied"}""")
                    .build()
            )
            refresh.refresh()

            gauge(MailQuotaMetrics.SENT) shouldBe 19
            gauge(MailQuotaMetrics.LAST_SUCCESS) shouldBe now.epochSecond
        }

    @Test
    fun `no subscription reads as no included mails`() =
        runTest {
            answer("""{"monthly_emails_count": 5}""")
            answer("""{"offer_subscriptions": [], "total_count": 0}""")

            refresh.refresh()

            gauge(MailQuotaMetrics.SENT) shouldBe 5
            gauge(MailQuotaMetrics.INCLUDED) shouldBe 0
        }

    /** A stray cancellation, such as a cancelled future's, must not escape: it would end the schedule for good (see #3541). */
    @Test
    fun `a stray cancellation is a failed read with a warning, and refresh returns`() =
        runTest {
            answer("""{"monthly_emails_count": 19}""")
            answer("""{"offer_subscriptions": [{"included_monthly_emails": 300}]}""")

            readerWith(FaultyClock { throw CancellationException("a cancelled future") }).refresh()

            val warning = warnings().single()
            warning.formattedMessage shouldStartWith "Mail quota: reading the Scaleway count failed"
            warning.throwableProxy.shouldNotBeNull().className shouldBe CancellationException::class.java.name
            gauge(MailQuotaMetrics.LAST_SUCCESS) shouldBe 0
        }

    @Test
    fun `a cancelled job stops without a warning`() =
        runTest {
            answer("""{"monthly_emails_count": 19}""")
            answer("""{"offer_subscriptions": [{"included_monthly_emails": 300}]}""")

            lateinit var job: Job
            job =
                launch {
                    readerWith(
                        FaultyClock {
                            job.cancel()
                            throw CancellationException("cancelled")
                        }
                    ).refresh()
                }
            job.join()

            job.isCancelled shouldBe true
            warnings().shouldBeEmpty()
        }

    /** Without the key there is no reader, so staging and a laptop never call Scaleway. */
    @Test
    fun `no reader without a key`() {
        val runner =
            ApplicationContextRunner()
                .withUserConfiguration(MailQuotaRefresh::class.java)
                .withBean(WebClient.Builder::class.java, { WebClient.builder() })
                .withBean(JsonMapper::class.java, { JsonMapper() })
                .withBean(Clock::class.java, { Clock.systemUTC() })
                .withBean(MailQuotaMetrics::class.java, { MailQuotaMetrics(SimpleMeterRegistry(), 10000) })
                .withPropertyValues("app.mail.quota.project-id=$PROJECT", "app.mail.quota.base-url=http://localhost")
        runner.run { it.getBeansOfType(MailQuotaRefresh::class.java).values.shouldBeEmpty() }
        runner
            .withPropertyValues("app.mail.quota.api-key=$KEY")
            .run { it.getBeansOfType(MailQuotaRefresh::class.java).values shouldHaveSize 1 }
    }

    /** A clock whose read runs [onRead]: the last call of a refresh, after both reads succeeded. */
    private class FaultyClock(
        private val onRead: () -> Nothing
    ) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = onRead()
    }

    private companion object {
        const val KEY = "scw-test-key"
        const val PROJECT = "11111111-2222-3333-4444-555555555555"
    }
}
