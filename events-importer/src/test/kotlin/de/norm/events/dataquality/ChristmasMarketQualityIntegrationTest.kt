package de.norm.events.dataquality

import de.norm.events.BaseControllerTest
import de.norm.events.event.EventFeature
import de.norm.events.importing.EventSourceRepository
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * A Christmas market is typed `OTHER`, with no start time and no genre, on purpose (#4972). The report, the snapshot and the worklist
 * leave it out of those three checks, and keep it in every other one (#5038).
 *
 * Each market has a concert beside it that fails the same three checks, so a predicate that drops too much fails as well.
 */
class ChristmasMarketQualityIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var service: DataQualityService

    @Autowired
    private lateinit var snapshots: DataQualitySnapshotRepository

    @Autowired
    private lateinit var metrics: DataQualityMetrics

    @Autowired
    private lateinit var eventSources: EventSourceRepository

    @Autowired
    private lateinit var dismissals: WorklistDismissalService

    private val excluded = listOf(QualityIssue.EVENTS_TYPED_OTHER, QualityIssue.MISSING_GENRE, QualityIssue.MISSING_START_TIME)

    /**
     * `manual` — a concert, a market that runs for five weeks, and a market with a kind and a second feature. All three are `OTHER`,
     *            with no start time, no genre, no promoter and no price.
     * `alpha`  — a scraped source with the same concert and one market.
     */
    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            val venueId = insertVenue()
            val alpha = insertSource(venueId)

            insertEvent(null, venueId, "manual-concert")
            val longMarket = insertEvent(null, venueId, "manual-market-long", endDate = "2026-12-31")
            feature(longMarket, EventFeature.CHRISTMAS_MARKET)
            val markedMarket = insertEvent(null, venueId, "manual-market-marked")
            feature(markedMarket, EventFeature.CHRISTMAS_MARKET)
            feature(markedMarket, EventFeature.CHRISTMAS_MARKET_CRAFT)
            feature(markedMarket, EventFeature.FOR_CHILDREN)

            insertEvent(alpha, venueId, "alpha-concert")
            feature(insertEvent(alpha, venueId, "alpha-market"), EventFeature.CHRISTMAS_MARKET)
        }

    @Test
    fun `the predicates name the feature's own slug`() {
        CHRISTMAS_MARKET_FEATURE shouldBe EventFeature.CHRISTMAS_MARKET.slug
    }

    @Test
    fun `the report counts the concert and not the markets in the three checks, and the markets in every other`(): Unit =
        runBlocking {
            val bySource = service.report().perSource.associateBy { it.source }

            val manual = bySource.getValue("manual")
            manual.totalEvents shouldBe 3L
            manual.eventsTypedOther shouldBe 1L
            manual.missingGenre shouldBe 1L
            manual.missingStartTime shouldBe 1L
            manual.missingPromoter shouldBe 3L
            manual.missingPrice shouldBe 3L

            val alpha = bySource.getValue("alpha")
            alpha.totalEvents shouldBe 2L
            alpha.eventsTypedOther shouldBe 1L
            alpha.missingGenre shouldBe 1L
            alpha.missingStartTime shouldBe 1L
            alpha.missingPromoter shouldBe 2L
        }

    @Test
    fun `the worklist lists the concerts and not the markets in the three checks`(): Unit =
        runBlocking {
            excluded.forEach { issue ->
                service
                    .worklist(issue, source = null, limit = 50, offset = 0)
                    .entries
                    .map { it.slug }
                    .toSet() shouldBe
                    setOf("manual-concert", "alpha-concert")
            }
            service
                .worklist(QualityIssue.MISSING_PROMOTER, source = "manual", limit = 50, offset = 0)
                .entries
                .map { it.slug }
                .toSet() shouldBe
                setOf("manual-concert", "manual-market-long", "manual-market-marked")
        }

    @Test
    fun `the snapshot the regression gate reads counts the concert only`(): Unit =
        runBlocking {
            val today = LocalDate.of(2026, 11, 15)
            val clock = Clock.fixed(Instant.parse("2026-11-15T04:00:00Z"), ZoneOffset.UTC)
            DataQualityReportLogger(service, snapshots, metrics, eventSources, dismissals, clock).snapshot()

            val manual =
                snapshots
                    .findBySnapshotDate(today)
                    .toList()
                    .filter { it.sourceSlug == "manual" }
                    .associateBy { it.metric }
            manual.getValue("totalEvents").metricCount shouldBe 3L
            excluded.forEach { manual.getValue(it.key).metricCount shouldBe 1L }
            manual.getValue(QualityIssue.MISSING_PROMOTER.key).metricCount shouldBe 3L
        }

    // Raw SQL, as in DataQualityReportIntegrationTest: the seed must not go through the mapping the test checks.

    private suspend fun insertVenue(): Long =
        databaseClient
            .sql(
                "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                    "VALUES ('Test Venue', 'test-venue', 'Somewhere 1', 'Berlin', '10999') RETURNING id"
            ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertSource(venueId: Long): Long =
        databaseClient
            .sql(
                "INSERT INTO events.event_source (venue_id, name, slug, url, source_type) " +
                    "VALUES ($venueId, 'alpha', 'alpha', 'https://alpha.example/events', 'CASSIOPEIA') RETURNING id"
            ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    /** An `OTHER` event with no start time, genre, promoter or price. */
    private suspend fun insertEvent(
        sourceId: Long?,
        venueId: Long,
        slug: String,
        endDate: String? = null
    ): Long =
        databaseClient
            .sql(
                """
                INSERT INTO events.event (venue_id, event_source_id, title, event_type, slug, event_date, end_date, source_id)
                VALUES ($venueId, ${sourceId ?: "NULL"}, '$slug', 'OTHER', '$slug', DATE '2026-11-24',
                        ${endDate?.let { "DATE '$it'" } ?: "NULL"}, '$slug')
                RETURNING id
                """.trimIndent()
            ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun feature(
        eventId: Long,
        feature: EventFeature
    ) = databaseClient
        .sql("INSERT INTO events.event_feature (event_id, feature, matched_phrase) VALUES ($eventId, '${feature.slug}', 'entered by hand')")
        .await()
}
