package de.norm.events.dataquality

import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle

/**
 * The authoritative test for Pillar 1, and the one that proves the part nothing else can.
 *
 * **The R2DBC projection maps by column *label*.** `aggregatePerSource` computes seven expressions
 * and aliases each one in `snake_case` to match a constructor property of `SourceQualityRow`;
 * nothing checks that correspondence at compile time, and a mismatch surfaces at runtime as a
 * mapping exception or — worse — a silently wrong number. A unit test with a mocked repository
 * cannot see it, because the mock returns the projection it was handed.
 *
 * So this seeds a corpus with a **known answer for every metric** and asserts each count against it.
 * The seed is deliberately awkward rather than tidy: two sources with different failure profiles,
 * one hand-created event with no source at all, and at least one event that is fine — so a metric
 * that accidentally counts everything, or nothing, cannot pass.
 */
class DataQualityReportIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var service: DataQualityService

    /**
     * Two sources and one manual event.
     *
     * `alpha` — 3 events: one clean concert, one concert with no artist, one typed OTHER.
     * `beta`  — 2 events: both concerts with no artist, one with no start time, one free.
     * manual  — 1 event: a concert with an artist whose name the vocabulary rejects.
     */
    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            val venueId = insertVenue()
            val alpha = insertSource("alpha", venueId)
            val beta = insertSource("beta", venueId)

            // A complete concert: artist, promoter, genre, price and start time. Nothing may count it.
            val complete = insertEvent(alpha, venueId, "alpha-complete", genre = "Rock", price = "12.00", startTime = "20:00")
            linkArtist(complete, insertArtist("Die Nerven"))
            linkPromoter(complete, insertPromoter("Some Promoter"))

            // A concert with no artist — the headline metric.
            insertEvent(alpha, venueId, "alpha-no-artist", genre = "Rock", price = "12.00", startTime = "20:00")

            // Typed OTHER, and therefore NOT counted by concertsWithoutArtist even though it has none.
            insertEvent(alpha, venueId, "alpha-other", eventType = "OTHER", genre = "Rock", price = "12.00", startTime = "20:00")

            insertEvent(beta, venueId, "beta-one", startTime = "22:00")
            // Free, so `missingPrice` must NOT count it — open decision A.
            insertEvent(beta, venueId, "beta-two", free = true)

            val manual = insertEvent(null, venueId, "manual-one", genre = "Pop", price = "5.00", startTime = "19:00")
            linkArtist(manual, insertArtist("TBA"))
            linkPromoter(manual, insertPromoter("Another Promoter"))
        }

    @Test
    fun `every metric is counted per source, and the aliases map`(): Unit =
        runBlocking {
            val report = service.report()
            val bySource = report.perSource.associateBy { it.source }

            bySource.keys shouldBe setOf("alpha", "beta", "manual")

            val alpha = bySource.getValue("alpha")
            alpha.totalEvents shouldBe 3L
            // Only the artist-less CONCERT. The OTHER-typed one also has no artist and must not count.
            alpha.concertsWithoutArtist shouldBe 1L
            alpha.eventsTypedOther shouldBe 1L
            alpha.missingGenre shouldBe 0L
            alpha.missingPromoter shouldBe 2L
            alpha.missingPrice shouldBe 0L
            alpha.missingStartTime shouldBe 0L

            val beta = bySource.getValue("beta")
            beta.totalEvents shouldBe 2L
            beta.concertsWithoutArtist shouldBe 2L
            beta.missingGenre shouldBe 2L
            // `beta-two` is free, so it is not missing a price — only `beta-one` is.
            beta.missingPrice shouldBe 1L
            beta.missingStartTime shouldBe 1L
        }

    /**
     * Events created through the admin API have no source. They get a named bucket rather than
     * being dropped, so the report's own total agrees with the table's.
     */
    @Test
    fun `events with no source are reported as manual rather than dropped`(): Unit =
        runBlocking {
            val report = service.report()
            val manual = report.perSource.single { it.source == "manual" }

            manual.totalEvents shouldBe 1L
            manual.concertsWithoutArtist shouldBe 0L
            report.overall.totalEvents shouldBe 6L
        }

    /**
     * The one metric SQL cannot express. `TBA` is in the curated non-artist vocabulary; `Die Nerven`
     * is not — so this fails if the filter is inverted, absent, or applied to the wrong column.
     */
    @Test
    fun `suspect artist names are counted per source, and real ones are not`(): Unit =
        runBlocking {
            val report = service.report()

            report.perSource.single { it.source == "manual" }.suspectNonArtistTitles shouldBe 1L
            report.perSource.single { it.source == "alpha" }.suspectNonArtistTitles shouldBe 0L
            report.overall.suspectNonArtistTitles shouldBe 1L
        }

    @Test
    fun `the worklist returns the events the report counted, and nothing else`(): Unit =
        runBlocking {
            val list = service.worklist(QualityIssue.CONCERTS_WITHOUT_ARTIST, source = null, limit = 50, offset = 0)

            list.count shouldBe 3
            list.entries.map { it.slug }.toSet() shouldBe setOf("alpha-no-artist", "beta-one", "beta-two")
            // The count and the list are the same predicate, and this is what keeps them that way.
            list.count.toLong() shouldBe service.report().overall.concertsWithoutArtist
        }

    @Test
    fun `the worklist filters by source, including the manual bucket`(): Unit =
        runBlocking {
            service
                .worklist(QualityIssue.CONCERTS_WITHOUT_ARTIST, source = "beta", limit = 50, offset = 0)
                .entries
                .map { it.slug }
                .toSet() shouldBe setOf("beta-one", "beta-two")

            service
                .worklist(QualityIssue.MISSING_PROMOTER, source = "manual", limit = 50, offset = 0)
                .entries
                .shouldBeEmptyBecauseTheManualEventHasAPromoter()
        }

    private fun List<WorklistEntryResponse>.shouldBeEmptyBecauseTheManualEventHasAPromoter() = size shouldBe 0

    // --- seeding ---------------------------------------------------------------------------------
    //
    // Raw SQL rather than the repositories, deliberately: this test is about what the aggregate
    // query counts, and seeding through the same mapping layer it is meant to verify would let one
    // bug hide another.

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

    private suspend fun insertArtist(name: String): Long =
        databaseClient
            .sql("INSERT INTO events.artist (name, slug) VALUES ('$name', '${name.lowercase().replace(' ', '-')}') RETURNING id")
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertPromoter(name: String): Long =
        databaseClient
            .sql("INSERT INTO events.promoter (name, slug) VALUES ('$name', '${name.lowercase().replace(' ', '-')}') RETURNING id")
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    @Suppress("LongParameterList")
    private suspend fun insertEvent(
        sourceId: Long?,
        venueId: Long,
        slug: String,
        eventType: String = "CONCERT",
        genre: String? = null,
        price: String? = null,
        startTime: String? = null,
        free: Boolean = false
    ): Long =
        databaseClient
            .sql(
                """
                INSERT INTO events.event (venue_id, event_source_id, title, event_type, slug, event_date,
                                          start_time, source_id, genre, price_presale, free)
                VALUES ($venueId, ${sourceId ?: "NULL"}, '$slug', '$eventType', '$slug', DATE '2026-09-12',
                        ${startTime?.let { "TIME '$it'" } ?: "NULL"}, '$slug', ${genre?.let { "'$it'" } ?: "NULL"},
                        ${price ?: "NULL"}, $free)
                RETURNING id
                """.trimIndent()
            ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun linkArtist(
        eventId: Long,
        artistId: Long
    ) = databaseClient.sql("INSERT INTO events.event_artist (event_id, artist_id) VALUES ($eventId, $artistId)").await()

    private suspend fun linkPromoter(
        eventId: Long,
        promoterId: Long
    ) = databaseClient.sql("INSERT INTO events.event_promoter (event_id, promoter_id) VALUES ($eventId, $promoterId)").await()
}
