package de.norm.events.mail

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.web.reactive.function.client.WebClient
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

class MailQuotaRefreshTest {
    private val server = MockWebServer().apply { start() }
    private val registry = SimpleMeterRegistry()
    private val now = Instant.parse("2026-10-29T18:00:00Z")

    private val refresh =
        MailQuotaRefresh(
            WebClient.builder(),
            JsonMapper(),
            Clock.fixed(now, ZoneOffset.UTC),
            MailQuotaMetrics(registry, 10000),
            KEY,
            PROJECT,
            server.url("/regions/fr-par").toString()
        )

    @AfterEach
    fun stop() = server.close()

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

    private companion object {
        const val KEY = "scw-test-key"
        const val PROJECT = "11111111-2222-3333-4444-555555555555"
    }
}
