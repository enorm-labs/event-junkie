package de.norm.events.scraper

import de.norm.events.BaseControllerTest
import de.norm.events.licence.SourceLicence
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle

/**
 * Recording a prohibition deletes what is already stored (#807).
 *
 * **Against a real database on purpose.** The clearing is two `@Modifying` statements in
 * annotations, so a mock can only assert that a method was called. What matters is which rows
 * PostgreSQL actually changes — and in particular that a *past* event is reached. A past event is
 * dropped from every scrape by `dropPastEvents`, so the import path can never repair it and this is
 * the only mechanism that does.
 *
 * The read gate keeps the field out of a response either way. That is § 19a UrhG. These assertions
 * are about § 16 UrhG, which is a different act and needs the row to actually change.
 */
class ProhibitedContentClearingIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var eventSourceService: EventSourceService

    private var sourceId: Long = 0

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            val venueId = insertVenue()
            sourceId = insertSource(venueId)
            // One in the future and one in the past. The past row is the one nothing else reaches.
            insertEvent(venueId, "future", "CURRENT_DATE + 30")
            insertEvent(venueId, "past", "CURRENT_DATE - 30")
        }

    @Test
    fun `prohibiting the description clears it from every event, past included`(): Unit =
        runBlocking {
            eventSourceService.update(SLUG, EventSourceUpdateRequest(descriptionLicence = SourceLicence.PROHIBITED))

            storedCount("description") shouldBe 0
            // The other field is a separate right and a separate answer. It stays.
            storedCount("image_url") shouldBe 2
        }

    @Test
    fun `prohibiting the image clears the URL and leaves the description`(): Unit =
        runBlocking {
            eventSourceService.update(SLUG, EventSourceUpdateRequest(imageLicence = SourceLicence.PROHIBITED))

            storedCount("image_url") shouldBe 0
            storedCount("description") shouldBe 2
        }

    @Test
    fun `an update that records no prohibition changes no stored content`(): Unit =
        runBlocking {
            // Fail-open, on the write path as well as the read path. UNCLEAR is not a refusal, so a
            // review that finds nothing must not blank the venue's listing.
            eventSourceService.update(
                SLUG,
                EventSourceUpdateRequest(descriptionLicence = SourceLicence.UNCLEAR, imageLicence = SourceLicence.UNCLEAR)
            )

            storedCount("description") shouldBe 2
            storedCount("image_url") shouldBe 2
        }

    @Test
    fun `an unrelated update leaves stored content alone`(): Unit =
        runBlocking {
            eventSourceService.update(SLUG, EventSourceUpdateRequest(enabled = false))

            storedCount("description") shouldBe 2
            storedCount("image_url") shouldBe 2
        }

    private suspend fun storedCount(column: String): Long =
        databaseClient
            .sql("SELECT COUNT(*) AS c FROM events.event WHERE event_source_id = $sourceId AND $column IS NOT NULL")
            .map { row, _ -> row.get("c", Number::class.java)!!.toLong() }
            .awaitSingle()

    // --- seeding -------------------------------------------------------------------------------
    //
    // Raw SQL rather than the repository, for the reason FindDueForImportIntegrationTest gives:
    // seeding through the mapping layer under test would let one bug hide another.

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
                """
                INSERT INTO events.event_source (venue_id, name, slug, url, source_type)
                VALUES ($venueId, '$SLUG', '$SLUG', 'https://$SLUG.example/events', 'CASSIOPEIA') RETURNING id
                """.trimIndent()
            ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun insertEvent(
        venueId: Long,
        key: String,
        dateExpression: String
    ) = databaseClient
        .sql(
            """
            INSERT INTO events.event
                (venue_id, event_source_id, source_id, title, slug, event_date, description, image_url, event_type, status)
            VALUES ($venueId, $sourceId, '$SLUG:$key', 'Berliner Weisse', '$key-berliner-weisse', $dateExpression,
                    'Ein Abend mit Aussicht', 'https://example.test/$key.jpg', 'CONCERT', 'SCHEDULED')
            """.trimIndent()
        ).await()

    private companion object {
        const val SLUG = "prohibited-source"
    }
}
