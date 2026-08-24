package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.micrometer.metrics.test.autoconfigure.AutoConfigureMetrics
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.test.web.reactive.server.expectBody

/**
 * `importer.source.events_future` against a real database and a real exposition (#700).
 *
 * Two things here cannot be tested any other way, and both fail silently:
 *
 * 1. **The projection maps by column *label*.** `countFuturePerSource` aliases its expressions in
 *    `snake_case` to match [de.norm.events.event.SourceFutureEventsRow]'s constructor properties,
 *    nothing checks that at compile time, and a mismatch surfaces at runtime as a mapping exception
 *    or a wrong number. A mocked repository returns the projection it was handed and sees none of
 *    it — the same argument `DataQualityReportIntegrationTest` makes.
 * 2. **The published name is not the meter name.** Micrometer rewrites on the way out; `db.events.total`
 *    reached `/actuator/prometheus` as `db_events` because `_total` is Prometheus' reserved counter
 *    suffix, silently, while every rule written from the documented name matched nothing. Only the
 *    exposition text settles what an alert can select on.
 *
 * **`@AutoConfigureMetrics` is not decoration**: Spring Boot forces
 * `management.defaults.metrics.export.enabled` to false in tests, so without it the endpoint 404s in
 * a way that looks exactly like a wrong exposure list.
 */
@AutoConfigureMetrics
class PerSourceEventsGaugeIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var refreshService: MetricsRefreshService

    @Autowired
    private lateinit var registry: MeterRegistry

    /**
     * Three sources with different shapes, and one event nobody imported.
     *
     * `busy`        — two future events and one past one, so a query that forgot the horizon reads 3.
     * `emptied-out` — one past event only: **the source this metric exists for.** Its scraper can
     *                 still be returning 200 and reporting success while the listing is gone.
     * `never-ran`   — no events at all, which is a different fact from having lost them and must
     *                 still produce a series.
     * manual        — an event with no source, created by hand through the admin API. It belongs to
     *                 no scraper and must not appear as one.
     */
    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            val venueId = insertVenue()
            val busy = insertSource("busy", venueId)
            val emptied = insertSource("emptied-out", venueId)
            insertSource("never-ran", venueId)

            insertEvent(busy, venueId, "busy-future-one", daysFromToday = 30)
            insertEvent(busy, venueId, "busy-future-two", daysFromToday = 3)
            insertEvent(busy, venueId, "busy-past", daysFromToday = -30)
            insertEvent(emptied, venueId, "emptied-past", daysFromToday = -7)
            insertEvent(null, venueId, "manual-future", daysFromToday = 14)

            refreshService.refreshGauges()
        }

    @Test
    fun `each source publishes the future events it holds, counted from today`() {
        gauge("busy") shouldBe 2.0
    }

    /**
     * **The case the metric exists for.** `emptied-out` returns no row from the `GROUP BY` — a
     * source with nothing has nothing to group — and a source absent from the exposition cannot be
     * alerted on and reads exactly like a healthy one. That is the #618 failure, one metric later,
     * so the zero has to be published rather than inferred from an absence.
     */
    @Test
    fun `a source that holds nothing publishes zero rather than no series`() {
        gauge("emptied-out") shouldBe 0.0
        gauge("never-ran") shouldBe 0.0
    }

    /** Events created through the admin API belong to no scraper, so there is nothing to alert on. */
    @Test
    fun `events with no source produce no series`() {
        registry.find(ImporterMetrics.SOURCE_EVENTS_FUTURE).tag("source", "manual").gauge() shouldBe null
    }

    /**
     * The name and label an alert rule actually selects on, read off the wire rather than off the
     * Kotlin constant. `gen_alerts.py` queries `importer_source_events_future`; this is what proves
     * that string exists.
     */
    @Test
    fun `the gauge reaches the exposition under the name the alert rule uses`() {
        val body =
            webTestClient
                .get()
                .uri("/actuator/prometheus")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<String>()
                .returnResult()
                .responseBody!!

        listOf(
            """importer_source_events_future{source="busy"}""",
            """importer_source_events_future{source="emptied-out"}"""
        ).forEach {
            assert(body.contains(it)) { "expected '$it' in the exposition; got:\n${body.take(3000)}" }
        }
    }

    private fun gauge(source: String): Double =
        registry
            .find(ImporterMetrics.SOURCE_EVENTS_FUTURE)
            .tag("source", source)
            .gauge()!!
            .value()

    // --- seeding ---------------------------------------------------------------------------------
    //
    // Raw SQL rather than the repositories, for the reason DataQualityReportIntegrationTest gives:
    // seeding through the mapping layer this test is meant to verify would let one bug hide another.
    // Dates are relative to the database's CURRENT_DATE because the running application's clock is
    // the system one, and a fixed date would stop being "future" on its own.

    private suspend fun insertVenue(): Long =
        databaseClient
            .sql(
                "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                    "VALUES ('Test Venue', 'test-venue', 'Somewhere 1', 'Berlin', '10999') RETURNING id"
            ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertSource(
        slug: String,
        venueId: Long
    ): Long =
        databaseClient
            .sql(
                "INSERT INTO events.event_source (venue_id, name, slug, url, source_type) " +
                    "VALUES ($venueId, '$slug', '$slug', 'https://$slug.example/events', 'CASSIOPEIA') RETURNING id"
            ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertEvent(
        sourceId: Long?,
        venueId: Long,
        slug: String,
        daysFromToday: Long
    ) = databaseClient
        .sql(
            """
            INSERT INTO events.event (venue_id, event_source_id, title, event_type, slug, event_date, source_id)
            VALUES ($venueId, ${sourceId ?: "NULL"}, '$slug', 'CONCERT', '$slug',
                    CURRENT_DATE + ($daysFromToday), '$slug')
            """.trimIndent()
        ).await()
}
