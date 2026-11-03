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

    @Autowired
    private lateinit var dismissals: WorklistDismissalService

    private val today = LocalDate.of(2026, 8, 19)
    private val clock = Clock.fixed(Instant.parse("2026-08-19T03:00:00Z"), ZoneOffset.UTC)

    private fun logger(withMetrics: DataQualityMetrics = metrics) = DataQualityReportLogger(service, snapshots, withMetrics, eventSources, dismissals, clock)

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
        metric: String,
        count: Long,
        source: String = "alpha"
    ) = DataQualitySnapshotEntity(
        snapshotDate = today.minusDays(daysAgo),
        sourceSlug = source,
        metric = metric,
        metricCount = count,
        totalEvents = 10
    )

    private val priceUpcoming = DataQualityRegressionCheck.MISSING_PRICE_UPCOMING

    /** A Sisyphos source, which declares no `PRICE` limitation, so its price share can regress. */
    private suspend fun seedSisyphos() {
        val venueId =
            databaseClient
                .sql(
                    "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                        "VALUES ('S', 's', 'A 1', 'Berlin', '10999') RETURNING id"
                ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
                .awaitSingle()
        databaseClient
            .sql(
                "INSERT INTO events.event_source (venue_id, name, slug, url, source_type) " +
                    "VALUES ($venueId, 'sisyphos', 'sisyphos', 'https://s.example', 'SISYPHOS')"
            ).await()
    }

    /** [count] Sisyphos events, one a day from [firstDay] days after today; [priced] ones carry a presale price. */
    private suspend fun seedSisyphosEvents(
        prefix: String,
        firstDay: Long,
        count: Int,
        priced: Boolean,
        status: String = "SCHEDULED",
        endDays: Long? = null
    ) {
        val price = if (priced) "12.00" else "NULL"
        val endDate = endDays?.let { "DATE '${today.plusDays(it)}'" } ?: "NULL"
        databaseClient
            .sql(
                "INSERT INTO events.event (venue_id, event_source_id, title, slug, event_date, end_date, source_id, price_presale, status) " +
                    "SELECT s.venue_id, s.id, '$prefix' || n, '$prefix-' || n, DATE '$today' + $firstDay + n, $endDate, " +
                    "'$prefix-' || n, $price, '$status' " +
                    "FROM events.event_source s, generate_series(0, ${count - 1}) n WHERE s.slug = 'sisyphos'"
            ).await()
    }

    /** Three earlier days with no unpriced event in the window and none overall: the least history that makes a baseline. */
    private suspend fun seedSisyphosPriceHistory() {
        snapshots
            .saveAll(
                listOf(QualityIssue.MISSING_PRICE.key, priceUpcoming).flatMap { metric ->
                    (1L..3L).map { row(it, metric, 0, source = "sisyphos") }
                }
            ).toList()
    }

    private fun regressionGauge(
        registry: MeterRegistry,
        source: String,
        metric: String
    ): Double? =
        registry
            .find(DataQualityMetrics.REGRESSION_GAUGE)
            .tags(DataQualityMetrics.TAG_SOURCE, source, DataQualityMetrics.TAG_METRIC, metric)
            .gauge()
            ?.value()

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
            // totalEvents + every QualityIssue metric + flaggedAtImportKeptOut + missingPriceUpcoming + suspectNonArtistTitles.
            rows.size shouldBe QualityIssue.entries.size + 4
            // The seeded event is 13 days ahead, so the gate's price series counts it too (#4420).
            rows.single { it.metric == priceUpcoming }.let { it.metricCount to it.totalEvents } shouldBe (1L to 1L)
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
            // The gate judges the price on the next 30 days' series, where all ten events fall (#4420).
            // CASSIOPEIA declares PRICE, so its price share is measured but never a regression (#3195).
            snapshots
                .saveAll(
                    listOf(QualityIssue.MISSING_GENRE.key, priceUpcoming).flatMap { metric ->
                        (1L..3L).map { row(it, metric, 0) }
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
                "missingPrice of alpha is at 100.0% against a 7-day median of 0.0% (10 events in the next 30 days)"
        }

    @Test
    fun `at start, today's stored rows set the regression gauge without logging a WARN`(): Unit =
        runBlocking {
            seedOneImperfectEvent()
            // Three earlier days at 0 %, then today at 100 %: missingGenre regressed, and missingPrice
            // got worse on a limitation CASSIOPEIA declares (#3195).
            val series = listOf(QualityIssue.MISSING_GENRE.key, priceUpcoming)
            snapshots
                .saveAll(series.flatMap { metric -> (1L..3L).map { row(it, metric, 0) } + row(0, metric, 10) })
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
            snapshots.findBySnapshotDate(today).toList().size shouldBe series.size
        }

    @Test
    fun `at start, no rows for today publish no regression series`(): Unit =
        runBlocking {
            seedOneImperfectEvent()
            snapshots.saveAll((1L..3L).map { row(it, QualityIssue.MISSING_GENRE.key, 0) }).toList()
            val registry = SimpleMeterRegistry()

            val lines = captureLogs { logger(DataQualityMetrics(registry)).publishStoredRegressions() }

            registry.find(DataQualityMetrics.REGRESSION_GAUGE).gauges() shouldBe emptyList()
            lines.none { it.level == ch.qos.logback.classic.Level.WARN } shouldBe true
            lines.map { it.formattedMessage } shouldBe
                listOf("No data-quality snapshot for $today yet; the regression gauge waits for the nightly run")
        }

    @Test
    fun `unpriced events beyond the next 30 days do not regress the price share`(): Unit =
        runBlocking {
            seedSisyphos()
            seedSisyphosEvents("near", firstDay = 1, count = 10, priced = true)
            seedSisyphosPriceHistory()
            // Sisyphos on 2026-11-03: a batch of far-ahead nights, none with a presale yet.
            seedSisyphosEvents("far", firstDay = DataQualityRegressionCheck.PRICE_WINDOW_DAYS, count = 31, priced = false)
            val registry = SimpleMeterRegistry()

            val lines = captureLogs { logger(DataQualityMetrics(registry)).snapshot() }

            regressionGauge(registry, "sisyphos", QualityIssue.MISSING_PRICE.key) shouldBe 0.0
            lines.none { it.level == ch.qos.logback.classic.Level.WARN } shouldBe true
            val rows = snapshots.findBySnapshotDate(today).toList().filter { it.sourceSlug == "sisyphos" }
            // The dashboard's series still counts every event.
            rows.single { it.metric == QualityIssue.MISSING_PRICE.key }.let { it.metricCount to it.totalEvents } shouldBe (31L to 41L)
            rows.single { it.metric == priceUpcoming }.let { it.metricCount to it.totalEvents } shouldBe (0L to 10L)
        }

    @Test
    fun `unpriced events inside the next 30 days still regress the price share`(): Unit =
        runBlocking {
            seedSisyphos()
            seedSisyphosEvents("near", firstDay = 1, count = 10, priced = true)
            seedSisyphosPriceHistory()
            seedSisyphosEvents("lost", firstDay = 11, count = 5, priced = false)
            val registry = SimpleMeterRegistry()

            val lines = captureLogs { logger(DataQualityMetrics(registry)).snapshot() }

            regressionGauge(registry, "sisyphos", QualityIssue.MISSING_PRICE.key) shouldBe 1.0
            val warning = lines.single { it.level == ch.qos.logback.classic.Level.WARN }
            warning.formattedMessage shouldBe
                "Data quality regressed: missingPrice of sisyphos is at 33.3% against a 7-day median of 0.0% " +
                "(15 events in the next 30 days)"
            warning.keyValuePairs.single { it.key == LogContext.SOURCE_SLUG }.value shouldBe "sisyphos"
        }

    @Test
    fun `the window takes today to the 29th day ahead, by start date, whatever the status`(): Unit =
        runBlocking {
            seedSisyphos()
            // Counted: today, the 29th day ahead, a cancelled night, and one that runs over midnight into tomorrow.
            seedSisyphosEvents("today", firstDay = 0, count = 1, priced = false, endDays = 1)
            seedSisyphosEvents("edge-in", firstDay = DataQualityRegressionCheck.PRICE_WINDOW_DAYS - 1, count = 1, priced = false)
            seedSisyphosEvents("cancelled", firstDay = 2, count = 1, priced = false, status = "CANCELLED")
            seedSisyphosEvents("priced", firstDay = 3, count = 1, priced = true)
            // Not counted: the 30th day ahead, and a festival that started yesterday and runs three more days.
            seedSisyphosEvents("edge-out", firstDay = DataQualityRegressionCheck.PRICE_WINDOW_DAYS, count = 1, priced = false)
            seedSisyphosEvents("started", firstDay = -1, count = 1, priced = false, endDays = 3)

            logger().snapshot()

            val rows = snapshots.findBySnapshotDate(today).toList().filter { it.sourceSlug == "sisyphos" }
            rows.single { it.metric == priceUpcoming }.let { it.metricCount to it.totalEvents } shouldBe (3L to 4L)
            rows.single { it.metric == QualityIssue.MISSING_PRICE.key }.let { it.metricCount to it.totalEvents } shouldBe (5L to 6L)
        }

    @Test
    fun `a source whose events all lie beyond the next 30 days gets no price verdict`(): Unit =
        runBlocking {
            seedSisyphos()
            seedSisyphosEvents("far", firstDay = 40, count = 12, priced = false)
            seedSisyphosPriceHistory()
            val registry = SimpleMeterRegistry()

            val lines = captureLogs { logger(DataQualityMetrics(registry)).snapshot() }

            // Zero of zero has no share, so the gate skips the series and publishes nothing for it.
            regressionGauge(registry, "sisyphos", QualityIssue.MISSING_PRICE.key) shouldBe null
            lines.none { it.level == ch.qos.logback.classic.Level.WARN } shouldBe true
            snapshots
                .findBySnapshotDate(today)
                .toList()
                .single { it.sourceSlug == "sisyphos" && it.metric == priceUpcoming }
                .let { it.metricCount to it.totalEvents } shouldBe (0L to 0L)
        }
}
