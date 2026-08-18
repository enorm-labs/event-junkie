package de.norm.events.scraper

import de.norm.events.event.EventRepository
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The gauges that have to be polled, because Micrometer reads a gauge synchronously and every query
 * here suspends.
 */
class MetricsRefreshServiceTest {
    private val eventRepository: EventRepository = mockk(relaxed = true)
    private val eventSourceRepository: EventSourceRepository = mockk(relaxed = true)
    private val today = LocalDate.of(2026, 6, 15)
    private val clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: ImporterMetrics
    private lateinit var service: MetricsRefreshService

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        metrics = ImporterMetrics(registry)
        service = MetricsRefreshService(eventRepository, eventSourceRepository, metrics, clock)

        coEvery { eventRepository.count() } returns 0
        coEvery { eventRepository.countByEventDateGreaterThanEqual(any()) } returns 0
        coEvery { eventSourceRepository.countByStatus(any()) } returns 0
        coEvery { eventSourceRepository.findByEnabledTrue() } returns emptyFlow()
    }

    private fun source(
        slug: String,
        status: ImportStatus,
        lastImportAt: Instant? = null
    ) = EventSourceEntity(
        id = 1L,
        venueId = 1L,
        name = slug,
        slug = slug,
        url = "https://$slug.example/events",
        sourceType = "CASSIOPEIA",
        status = status.name,
        lastImportAt = lastImportAt
    )

    @Test
    fun `a refresh reads the counts and publishes them`() =
        runTest {
            coEvery { eventRepository.count() } returns 2965
            coEvery { eventRepository.countByEventDateGreaterThanEqual(today) } returns 1204
            coEvery { eventSourceRepository.countByStatus(ImportStatus.RUNNING.name) } returns 3

            service.refreshGauges()

            registry
                .find("db.events")
                .tag("horizon", "all")
                .gauge()!!
                .value() shouldBe 2965.0
            registry
                .find("db.events")
                .tag("horizon", "future")
                .gauge()!!
                .value() shouldBe 1204.0
            registry.find("importer.source.running").gauge()!!.value() shouldBe 3.0
        }

    /**
     * "Future" is relative to the injected clock, not to the wall clock — so this asserts the cutoff
     * is the clock's today rather than whatever day the test happens to run on.
     */
    @Test
    fun `future events are counted from the clock's today`() =
        runTest {
            coEvery { eventRepository.countByEventDateGreaterThanEqual(today) } returns 42

            service.refreshGauges()

            registry
                .find("db.events")
                .tag("horizon", "future")
                .gauge()!!
                .value() shouldBe 42.0
        }

    @Test
    fun `last_success is published only for sources whose last run actually succeeded`() =
        runTest {
            val succeeded = Instant.parse("2026-06-15T04:00:00Z")
            coEvery { eventSourceRepository.findByEnabledTrue() } returns
                listOf(
                    source("good", ImportStatus.SUCCESS, succeeded),
                    source("broken", ImportStatus.FAILED, Instant.parse("2026-06-15T05:00:00Z")),
                    source("never-run", ImportStatus.IDLE, null)
                ).asFlow()

            service.refreshGauges()

            registry
                .find("importer.source.last_success")
                .tag("source", "good")
                .gauge()!!
                .value() shouldBe
                succeeded.epochSecond.toDouble()
            // The known limit, asserted so it stays deliberate: `last_import_at` is written on failure
            // too, so a failed source's timestamp is a last *attempt* and must not be published as a
            // success. An absent series is alertable; a wrong one is not.
            registry.find("importer.source.last_success").tag("source", "broken").gauge() shouldBe null
            registry.find("importer.source.last_success").tag("source", "never-run").gauge() shouldBe null
        }

    /**
     * Monitoring must not be able to take down the scheduler it shares with the imports. The visible
     * result of this failing is a gauge that stops moving, which is itself detectable — and strictly
     * better than a dead importer.
     */
    @Test
    fun `a database failure leaves the previous values in place instead of propagating`() =
        runTest {
            coEvery { eventRepository.count() } returns 100
            coEvery { eventRepository.countByEventDateGreaterThanEqual(any()) } returns 10
            service.refreshGauges()

            coEvery { eventRepository.count() } throws IllegalStateException("connection pool exhausted")

            service.refreshGauges() // must not throw

            registry
                .find("db.events")
                .tag("horizon", "all")
                .gauge()!!
                .value() shouldBe 100.0
        }
}
