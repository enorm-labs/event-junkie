package de.norm.events.dataquality

import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The history half of Pillar 1: the daily row that makes a trend possible.
 *
 * `app.scheduling.enabled` is `true` here and `false` for every other test, so the bean exists and
 * can be invoked directly — the cron expression is not what is under test, the write is.
 *
 * **Idempotence is the assertion that matters.** The unique constraint is
 * `(snapshot_date, source_slug, metric)`, so a second run on the same day either updates the row or
 * fails the whole job — and the job has to be safe to trigger by hand, because that is how a missing
 * day gets backfilled after an outage.
 */
@TestPropertySource(properties = ["app.scheduling.enabled=true"])
class DataQualitySnapshotIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var snapshots: DataQualitySnapshotRepository

    @Autowired
    private lateinit var service: DataQualityService

    @Autowired
    private lateinit var metrics: DataQualityMetrics

    @Autowired
    private lateinit var registry: MeterRegistry

    private val today = LocalDate.of(2026, 8, 19)
    private val clock = Clock.fixed(Instant.parse("2026-08-19T03:00:00Z"), ZoneOffset.UTC)

    private fun logger() = DataQualityReportLogger(service, snapshots, metrics, clock)

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
            // totalEvents + six QualityIssue metrics + suspectNonArtistTitles.
            rows.size shouldBe 8
            rows.single { it.metric == "totalEvents" }.metricCount shouldBe 1L
            rows.single { it.metric == "concertsWithoutArtist" }.metricCount shouldBe 1L
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
}
