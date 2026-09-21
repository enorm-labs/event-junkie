package de.norm.events.scraper

import de.norm.events.artist.ArtistRepository
import de.norm.events.event.EventRepository
import de.norm.events.event.SourceFutureEventsRow
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
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
    private val artistRepository: ArtistRepository = mockk(relaxed = true)
    private val today = LocalDate.of(2026, 6, 15)
    private val clock = Clock.fixed(today.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC)

    private lateinit var registry: SimpleMeterRegistry
    private lateinit var metrics: ImporterMetrics
    private lateinit var service: MetricsRefreshService

    @BeforeEach
    fun setUp() {
        registry = SimpleMeterRegistry()
        metrics = ImporterMetrics(registry)
        service = MetricsRefreshService(eventRepository, eventSourceRepository, artistRepository, metrics, clock)

        coEvery { eventRepository.count() } returns 0
        coEvery { eventRepository.countByEventDateGreaterThanEqual(any()) } returns 0
        coEvery { eventRepository.countFuturePerSource(any()) } returns emptyFlow()
        coEvery { eventSourceRepository.countByStatus(any()) } returns 0
        coEvery { eventSourceRepository.findByEnabledTrue() } returns emptyFlow()
        coEvery { artistRepository.countUncheckedByMusicBrainz() } returns 0
        coEvery { eventSourceRepository.countFailedPerReason() } returns emptyFlow()
    }

    @Test
    fun `publishes how many artist rows the MusicBrainz sweep still owes`() =
        runTest {
            coEvery { artistRepository.countUncheckedByMusicBrainz() } returns 5730

            service.refreshGauges()

            registry.get(ImporterMetrics.MUSICBRAINZ_UNCHECKED).gauge().value() shouldBe 5730.0
        }

    /**
     * The #618 property on the per-reason gauge (#708): the query returns a row per reason in use, and
     * the rule on `reason="dns"` needs a series at 0 during a quiet week, not an absence.
     */
    @Test
    fun `every failure reason publishes how many sources sit on it, zero included`() =
        runTest {
            coEvery { eventSourceRepository.countFailedPerReason() } returns
                listOf(FailedSourcesRow(reason = "dns", sources = 12), FailedSourcesRow(reason = "parse", sources = 1)).asFlow()

            service.refreshGauges()

            failedSources("dns") shouldBe 12.0
            failedSources("parse") shouldBe 1.0
            failedSources("http_forbidden") shouldBe 0.0
            ScrapeFailureReason.ALL.forEach { reason -> registry.find(ImporterMetrics.SOURCES_FAILED).tag("reason", reason).gauge() shouldNotBe null }
        }

    @Test
    fun `a reason whose last source recovers falls back to zero on the next tick`() =
        runTest {
            coEvery { eventSourceRepository.countFailedPerReason() } returns
                listOf(FailedSourcesRow(reason = "dns", sources = 3)).asFlow()
            service.refreshGauges()
            failedSources("dns") shouldBe 3.0

            coEvery { eventSourceRepository.countFailedPerReason() } returns emptyFlow()
            service.refreshGauges()

            failedSources("dns") shouldBe 0.0
        }

    private fun failedSources(reason: String) =
        registry
            .find(ImporterMetrics.SOURCES_FAILED)
            .tag("reason", reason)
            .gauge()!!
            .value()

    private fun source(
        slug: String,
        status: ImportStatus,
        lastImportAt: Instant? = null,
        lastSuccessAt: Instant? = null,
        id: Long = 1L,
        createdAt: Instant? = null
    ) = EventSourceEntity(
        id = id,
        venueId = 1L,
        name = slug,
        slug = slug,
        url = "https://$slug.example/events",
        sourceType = "CASSIOPEIA",
        status = status.name,
        lastImportAt = lastImportAt,
        lastSuccessAt = lastSuccessAt,
        createdAt = createdAt
    )

    private fun daysSince(source: String): Double =
        registry
            .find(ImporterMetrics.SOURCE_DAYS_SINCE_FUTURE_EVENT)
            .tag("source", source)
            .gauge()!!
            .value()

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
    fun `last_success is published from last_success_at, for every source that has ever succeeded`() =
        runTest {
            val succeeded = Instant.parse("2026-06-15T04:00:00Z")
            coEvery { eventSourceRepository.findByEnabledTrue() } returns
                listOf(
                    source("good", ImportStatus.SUCCESS, lastImportAt = succeeded, lastSuccessAt = succeeded),
                    source("never-run", ImportStatus.IDLE, lastImportAt = null, lastSuccessAt = null)
                ).asFlow()

            service.refreshGauges()

            registry
                .find("importer.source.last_success")
                .tag("source", "good")
                .gauge()!!
                .value() shouldBe
                succeeded.epochSecond.toDouble()
            // Never succeeded, so there is no true value to assert. A zero here would read as 1970 to
            // every rule written on this gauge, which is worse than an absent series.
            registry.find("importer.source.last_success").tag("source", "never-run").gauge() shouldBe null
        }

    /**
     * #618: `last_success` appears only on a source's first success, so a never-run venue has no series.
     *
     * A source with no series cannot read as stale, late or failing on any dashboard.
     * `has_succeeded` therefore exists for every enabled source, and holds 0 until the first success.
     */
    @Test
    fun `every source publishes has_succeeded, including one that never has`() =
        runTest {
            val succeeded = Instant.parse("2026-06-15T04:00:00Z")
            coEvery { eventSourceRepository.findByEnabledTrue() } returns
                listOf(
                    source("good", ImportStatus.SUCCESS, lastImportAt = succeeded, lastSuccessAt = succeeded),
                    source("never-run", ImportStatus.IDLE, lastImportAt = null, lastSuccessAt = null),
                    source("tried-and-failed", ImportStatus.FAILED, lastImportAt = succeeded, lastSuccessAt = null)
                ).asFlow()

            service.refreshGauges()

            registry
                .find("importer.source.has_succeeded")
                .tag("source", "good")
                .gauge()!!
                .value() shouldBe 1.0
            // The two that matter: a series exists, and its value says which fact this is.
            registry
                .find("importer.source.has_succeeded")
                .tag("source", "never-run")
                .gauge()!!
                .value() shouldBe 0.0
            registry
                .find("importer.source.has_succeeded")
                .tag("source", "tried-and-failed")
                .gauge()!!
                .value() shouldBe 0.0
        }

    /**
     * The two gauges have one shared invariant — `has_succeeded = 1` exactly when `last_success`
     * exists — and it is asserted here because they are published from two different call sites.
     */
    @Test
    fun `a source that starts succeeding flips has_succeeded without waiting for a restart`() =
        runTest {
            val slug = "late-bloomer"
            coEvery { eventSourceRepository.findByEnabledTrue() } returns
                listOf(source(slug, ImportStatus.FAILED, lastSuccessAt = null)).asFlow()
            service.refreshGauges()
            registry
                .find("importer.source.has_succeeded")
                .tag("source", slug)
                .gauge()!!
                .value() shouldBe 0.0

            val firstSuccess = Instant.parse("2026-06-15T04:00:00Z")
            coEvery { eventSourceRepository.findByEnabledTrue() } returns
                listOf(source(slug, ImportStatus.SUCCESS, lastImportAt = firstSuccess, lastSuccessAt = firstSuccess)).asFlow()
            service.refreshGauges()

            registry
                .find("importer.source.has_succeeded")
                .tag("source", slug)
                .gauge()!!
                .value() shouldBe 1.0
            registry
                .find("importer.source.last_success")
                .tag("source", slug)
                .gauge()!!
                .value() shouldBe firstSuccess.epochSecond.toDouble()
        }

    /**
     * The regression this column exists to close (#415).
     *
     * Before `last_success_at`, this service filtered on `status == SUCCESS` and read `last_import_at`
     * — so the moment a source started failing, its `last_success` series **disappeared**. That is the
     * exact instant the staleness alert is supposed to fire, and an alert on
     * `time() - importer_source_last_success_seconds > 3 * interval` has nothing to evaluate when the
     * series is gone. The gauge went quiet precisely because the thing it watches broke.
     */
    @Test
    fun `a source that is failing now still publishes the success it had before`() =
        runTest {
            val lastGoodRun = Instant.parse("2026-06-13T04:00:00Z")
            val failedAttempt = Instant.parse("2026-06-15T04:00:00Z")
            coEvery { eventSourceRepository.findByEnabledTrue() } returns
                listOf(
                    source("broken", ImportStatus.FAILED, lastImportAt = failedAttempt, lastSuccessAt = lastGoodRun)
                ).asFlow()

            service.refreshGauges()

            registry
                .find("importer.source.last_success")
                .tag("source", "broken")
                .gauge()!!
                .value() shouldBe
                lastGoodRun.epochSecond.toDouble()
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

    /**
     * #700: the per-source count, published from the database rather than accumulated in-process.
     *
     * `db.events{horizon="future"}` is the whole catalogue, so one venue of eighty-six going silent
     * moves it by a rounding error. This is the number that sees it.
     */
    @Test
    fun `every source publishes the future events it holds`() =
        runTest {
            coEvery { eventSourceRepository.findByEnabledTrue() } returns
                listOf(
                    source("busy", ImportStatus.SUCCESS, id = 7L),
                    source("quiet", ImportStatus.SUCCESS, id = 8L)
                ).asFlow()
            coEvery { eventRepository.countFuturePerSource(today) } returns
                listOf(
                    SourceFutureEventsRow(eventSourceId = 7L, futureEvents = 41, newestEventDate = today.plusDays(30)),
                    SourceFutureEventsRow(eventSourceId = 8L, futureEvents = 3, newestEventDate = today.plusDays(30))
                ).asFlow()

            service.refreshGauges()

            registry
                .find("importer.source.events_future")
                .tag("source", "busy")
                .gauge()!!
                .value() shouldBe 41.0
            registry
                .find("importer.source.events_future")
                .tag("source", "quiet")
                .gauge()!!
                .value() shouldBe 3.0
        }

    /**
     * **The case the metric exists for**, and the one #618 already paid for once on another gauge.
     *
     * A source holding no future events returns no row from the `GROUP BY`, so publishing what the
     * query returned would leave exactly the broken venues out of the exposition — where they cannot
     * be alerted on and read as healthy. Zero is a value here, not an absence.
     */
    @Test
    fun `a source with no future events publishes zero rather than nothing`() =
        runTest {
            coEvery { eventSourceRepository.findByEnabledTrue() } returns
                listOf(
                    source("busy", ImportStatus.SUCCESS, id = 7L),
                    source("emptied-out", ImportStatus.SUCCESS, id = 9L)
                ).asFlow()
            coEvery { eventRepository.countFuturePerSource(today) } returns
                listOf(SourceFutureEventsRow(eventSourceId = 7L, futureEvents = 41, newestEventDate = today.plusDays(30))).asFlow()

            service.refreshGauges()

            registry
                .find("importer.source.events_future")
                .tag("source", "emptied-out")
                .gauge()!!
                .value() shouldBe 0.0
        }

    /**
     * The horizon comes from the injected clock, like the aggregate gauge above — a per-source count
     * taken against the wall clock would disagree with the catalogue count once a day, around
     * midnight, and look like a bug in one of them.
     */
    @Test
    fun `the per-source count is taken from the clock's today`() =
        runTest {
            coEvery { eventSourceRepository.findByEnabledTrue() } returns
                listOf(source("busy", ImportStatus.SUCCESS, id = 7L)).asFlow()
            coEvery { eventRepository.countFuturePerSource(today) } returns
                listOf(SourceFutureEventsRow(eventSourceId = 7L, futureEvents = 12, newestEventDate = today.plusDays(30))).asFlow()

            service.refreshGauges()

            coVerify { eventRepository.countFuturePerSource(today) }
            registry
                .find("importer.source.events_future")
                .tag("source", "busy")
                .gauge()!!
                .value() shouldBe 12.0
        }

    /**
     * The duration `events_future` cannot express (#1498): a source at zero reads the same on its
     * first day and its four-hundredth, and `ej-source-emptied` only ever sees a week back.
     */
    @Test
    fun `every source publishes how many days it has held no future event`() =
        runTest {
            coEvery { eventSourceRepository.findByEnabledTrue() } returns
                listOf(
                    source("busy", ImportStatus.SUCCESS, id = 7L),
                    source("emptied-out", ImportStatus.SUCCESS, id = 8L),
                    source("amt", ImportStatus.SUCCESS, id = 9L)
                ).asFlow()
            coEvery { eventRepository.countFuturePerSource(today) } returns
                listOf(
                    SourceFutureEventsRow(eventSourceId = 7L, futureEvents = 3, newestEventDate = today.plusDays(12)),
                    SourceFutureEventsRow(eventSourceId = 8L, futureEvents = 0, newestEventDate = today.minusDays(40)),
                    SourceFutureEventsRow(eventSourceId = 9L, futureEvents = 0, newestEventDate = today.minusDays(365))
                ).asFlow()

            service.refreshGauges()

            // A future event floors the duration at zero; the day of the newest event counts as held.
            daysSince("busy") shouldBe 0.0
            daysSince("emptied-out") shouldBe 40.0
            registry
                .find(ImporterMetrics.SOURCE_DAYS_SINCE_FUTURE_EVENT)
                .tag("source", "emptied-out")
                .tag("known_quiet", "false")
                .gauge()
                .shouldNotBeNull()
            // The venue-side ones are marked from KNOWN_QUIET_SOURCES, so the rule can leave them out.
            registry
                .find(ImporterMetrics.SOURCE_DAYS_SINCE_FUTURE_EVENT)
                .tag("source", "amt")
                .tag("known_quiet", "true")
                .gauge()!!
                .value() shouldBe 365.0
        }

    // A source with no event at all has been quiet for as long as it has existed — a value, not an absence.
    @Test
    fun `a source with no event counts from the day its row was created`() =
        runTest {
            coEvery { eventSourceRepository.findByEnabledTrue() } returns
                listOf(
                    source("never-ran", ImportStatus.SUCCESS, id = 7L, createdAt = today.minusDays(9).atStartOfDay().toInstant(ZoneOffset.UTC)),
                    source("just-added", ImportStatus.SUCCESS, id = 8L)
                ).asFlow()

            service.refreshGauges()

            daysSince("never-ran") shouldBe 9.0
            daysSince("just-added") shouldBe 0.0
        }
}
