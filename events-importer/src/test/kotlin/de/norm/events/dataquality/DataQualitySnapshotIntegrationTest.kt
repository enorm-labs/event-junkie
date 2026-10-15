package de.norm.events.dataquality

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import de.norm.events.BaseControllerTest
import de.norm.events.importing.EventSourceRepository
import de.norm.events.scraper.LogContext
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The history half of Pillar 1: the daily row that makes a trend possible.
 *
 * **It builds its own [DataQualityReportLogger]** rather than autowiring one, because it needs a
 * fixed [Clock] anyway. That is also what keeps this class inside the shared Spring context: it used
 * to set `app.scheduling.enabled=true` so the bean would exist, which forked a whole context and a
 * container for a bean it never asked for (#965). The cron expression is not what is under test, the
 * write is.
 *
 * **Idempotence is the assertion that matters.** The unique constraint is
 * `(snapshot_date, source_slug, metric)`, so a second run on the same day either updates the row or
 * fails the whole job — and the job has to be safe to trigger by hand, because that is how a missing
 * day gets backfilled after an outage.
 */
class DataQualitySnapshotIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var snapshots: DataQualitySnapshotRepository

    @Autowired
    private lateinit var service: DataQualityService

    @Autowired
    private lateinit var metrics: DataQualityMetrics

    @Autowired
    private lateinit var registry: MeterRegistry

    @Autowired
    private lateinit var eventSources: EventSourceRepository

    private val today = LocalDate.of(2026, 8, 19)
    private val clock = Clock.fixed(Instant.parse("2026-08-19T03:00:00Z"), ZoneOffset.UTC)

    private fun logger(withMetrics: DataQualityMetrics = metrics) = DataQualityReportLogger(service, snapshots, withMetrics, eventSources, clock)

    private fun captureLogs(block: suspend () -> Unit): List<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        val logbackLogger = LoggerFactory.getLogger(DataQualityReportLogger::class.java) as Logger
        logbackLogger.addAppender(appender)
        try {
            runBlocking { block() }
        } finally {
            logbackLogger.detachAppender(appender)
            appender.stop()
        }
        return appender.list
    }

    private fun row(
        daysAgo: Long,
        issue: QualityIssue,
        count: Long
    ) = DataQualitySnapshotEntity(
        snapshotDate = today.minusDays(daysAgo),
        sourceSlug = "alpha",
        metric = issue.key,
        metricCount = count,
        totalEvents = 10
    )

    private suspend fun seedOneImperfectEvent() {
        val venueId =
            databaseClient
                .sql(
                    "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                        "VALUES ('V', 'v', 'A 1', 'Berlin', '10999') RETURNING id"
                ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
                .awaitSingle()
        databaseClient
            .sql(
                "INSERT INTO events.event_source (venue_id, name, slug, url, source_type) " +
                    "VALUES ($venueId, 'alpha', 'alpha', 'https://a.example', 'CASSIOPEIA')"
            ).await()
        databaseClient
            .sql(
                "INSERT INTO events.event (venue_id, event_source_id, title, slug, event_date, source_id) " +
                    "SELECT $venueId, s.id, 'T', 'alpha-t', DATE '2026-09-01', 'alpha-t' " +
                    "FROM events.event_source s WHERE s.slug = 'alpha'"
            ).await()
    }

    @Test
    fun `a run writes one row per source and metric, and publishes the gauges`(): Unit =
        runBlocking {
            seedOneImperfectEvent()

            logger().snapshot()

            val rows = snapshots.findBySnapshotDate(today).toList()
            rows.map { it.sourceSlug }.toSet() shouldBe setOf("alpha")
            // totalEvents + every QualityIssue metric + flaggedAtImportKeptOut + suspectNonArtistTitles.
            rows.size shouldBe QualityIssue.entries.size + 3
            // The regression gate skips the two review queues, and the snapshot still counts them (#3314).
            rows.single { it.metric == "titleDerivedSingletons" }.metricCount shouldBe 0L
            rows.single { it.metric == "titleDerivedUnmatched" }.metricCount shouldBe 0L
            rows.single { it.metric == "totalEvents" }.metricCount shouldBe 1L
            rows.single { it.metric == "concertsWithoutArtist" }.metricCount shouldBe 1L
            // The seeded source has no licence_reviewed_at, which is the state every source is in
            // until somebody reviews it (#283).
            rows.single { it.metric == "unreviewedLicence" }.metricCount shouldBe 1L
            // Every row carries the denominator, so a percentage can be recomputed from history
            // alone without joining back to a second row.
            rows.all { it.totalEvents == 1L } shouldBe true

            registry
                .find(DataQualityMetrics.GAUGE)
                .tags(DataQualityMetrics.TAG_SOURCE, "alpha", DataQualityMetrics.TAG_METRIC, "concertsWithoutArtist")
                .gauge()!!
                .value() shouldBe 1.0
        }

    @Test
    fun `running twice on the same day updates rather than colliding with the unique constraint`(): Unit =
        runBlocking {
            seedOneImperfectEvent()

            logger().snapshot()
            val first = snapshots.findBySnapshotDate(today).toList()

            // A second event, then the same day's job again — the classic backfill-after-an-outage
            // shape, and the one that a plain insert would fail on.
            databaseClient
                .sql(
                    "INSERT INTO events.event (venue_id, event_source_id, title, slug, event_date, source_id) " +
                        "SELECT e.venue_id, e.event_source_id, 'T2', 'alpha-t2', DATE '2026-09-02', 'alpha-t2' " +
                        "FROM events.event e WHERE e.slug = 'alpha-t'"
                ).await()

            logger().snapshot()
            val second = snapshots.findBySnapshotDate(today).toList()

            second.size shouldBe first.size
            second.single { it.metric == "totalEvents" }.metricCount shouldBe 2L
            // The same rows, updated in place — not a second set for the same day.
            second.map { it.id }.toSet() shouldBe first.map { it.id }.toSet()
        }

    @Test
    fun `a share ten points over the week's median sets the regression gauge and logs a WARN, unless the source declares it`(): Unit =
        runBlocking {
            seedOneImperfectEvent()
            // Ten events: the least a source needs to be judged. None has a genre.
            databaseClient
                .sql(
                    "INSERT INTO events.event (venue_id, event_source_id, title, slug, event_date, source_id) " +
                        "SELECT e.venue_id, e.event_source_id, 'T' || n, 'alpha-t' || n, DATE '2026-09-01' + n, 'alpha-t' || n " +
                        "FROM events.event e, generate_series(2, 10) n WHERE e.slug = 'alpha-t'"
                ).await()
            // Three earlier days at 0 % without a genre or a price: the least history that makes a baseline.
            // CASSIOPEIA declares PRICE, so its price share is measured but never a regression (#3195).
            snapshots
                .saveAll(
                    listOf(QualityIssue.MISSING_GENRE, QualityIssue.MISSING_PRICE).flatMap { issue ->
                        (1L..3L).map {
                            DataQualitySnapshotEntity(
                                snapshotDate = today.minusDays(it),
                                sourceSlug = "alpha",
                                metric = issue.key,
                                metricCount = 0,
                                totalEvents = 10
                            )
                        }
                    }
                ).toList()

            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logbackLogger = LoggerFactory.getLogger(DataQualityReportLogger::class.java) as Logger
            logbackLogger.addAppender(appender)
            try {
                logger().snapshot()
            } finally {
                logbackLogger.detachAppender(appender)
                appender.stop()
            }

            fun regression(metric: String) =
                registry
                    .find(DataQualityMetrics.REGRESSION_GAUGE)
                    .tags(DataQualityMetrics.TAG_SOURCE, "alpha", DataQualityMetrics.TAG_METRIC, metric)
                    .gauge()!!
                    .value()
            regression(QualityIssue.MISSING_GENRE.key) shouldBe 1.0
            regression(QualityIssue.MISSING_PRICE.key) shouldBe 0.0
            // No history for this one, so it is published as 0 rather than left absent.
            regression(QualityIssue.CONCERTS_WITHOUT_ARTIST.key) shouldBe 0.0

            val warning = appender.list.single { it.level == ch.qos.logback.classic.Level.WARN }
            warning.formattedMessage shouldBe
                "Data quality regressed: missingGenre of alpha is at 100.0% against a 7-day median of 0.0% (10 events)"
            warning.keyValuePairs.single { it.key == LogContext.SOURCE_SLUG }.value shouldBe "alpha"
            appender.list.single { it.formattedMessage.contains("accepted limitation") }.formattedMessage shouldBe
                "Data quality got worse on an accepted limitation, not a regression: " +
                "missingPrice of alpha is at 100.0% against a 7-day median of 0.0% (10 events)"
        }

    @Test
    fun `at start, today's stored rows set the regression gauge without logging a WARN`(): Unit =
        runBlocking {
            seedOneImperfectEvent()
            // Three earlier days at 0 %, then today at 100 %: missingGenre regressed, and missingPrice
            // got worse on a limitation CASSIOPEIA declares (#3195).
            val issues = listOf(QualityIssue.MISSING_GENRE, QualityIssue.MISSING_PRICE)
            snapshots
                .saveAll(issues.flatMap { issue -> (1L..3L).map { row(it, issue, 0) } + row(0, issue, 10) })
                .toList()
            // A registry of its own, so a series left by another test cannot pass this one.
            val registry = SimpleMeterRegistry()

            val lines = captureLogs { logger(DataQualityMetrics(registry)).publishStoredRegressions() }

            fun regression(metric: String) =
                registry
                    .find(DataQualityMetrics.REGRESSION_GAUGE)
                    .tags(DataQualityMetrics.TAG_SOURCE, "alpha", DataQualityMetrics.TAG_METRIC, metric)
                    .gauge()!!
                    .value()
            regression(QualityIssue.MISSING_GENRE.key) shouldBe 1.0
            regression(QualityIssue.MISSING_PRICE.key) shouldBe 0.0
            registry.find(DataQualityMetrics.REGRESSION_GAUGE).gauges().size shouldBe 2
            // The 03:00 run logged the per-verdict lines already; a rollout must not repeat them.
            lines.none { it.level == ch.qos.logback.classic.Level.WARN } shouldBe true
            lines.map { it.formattedMessage } shouldBe
                listOf("Republished the data-quality regression gauge from $today's snapshot: 2 series, 1 regressed")
            // Republishing must not write: the stored rows are what it read.
            snapshots.findBySnapshotDate(today).toList().size shouldBe issues.size
        }

    @Test
    fun `at start, no rows for today publish no regression series`(): Unit =
        runBlocking {
            seedOneImperfectEvent()
            snapshots.saveAll((1L..3L).map { row(it, QualityIssue.MISSING_GENRE, 0) }).toList()
            val registry = SimpleMeterRegistry()

            val lines = captureLogs { logger(DataQualityMetrics(registry)).publishStoredRegressions() }

            registry.find(DataQualityMetrics.REGRESSION_GAUGE).gauges() shouldBe emptyList()
            lines.none { it.level == ch.qos.logback.classic.Level.WARN } shouldBe true
            lines.map { it.formattedMessage } shouldBe
                listOf("No data-quality snapshot for $today yet; the regression gauge waits for the nightly run")
        }
}
