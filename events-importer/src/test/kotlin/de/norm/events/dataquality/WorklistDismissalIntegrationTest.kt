package de.norm.events.dataquality

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle

/**
 * A dismissal hides a worklist row only while the values the check flagged stay the same (ADR-052).
 *
 * Each test dismisses through the API with the fingerprint `GET /worklist` returned, then changes one
 * flagged value in the database and expects the row back. That is the rule the design must keep.
 */
class WorklistDismissalIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var service: DataQualityService

    @Autowired
    private lateinit var dismissals: WorklistDismissalService

    private var venueId = 0L
    private var sourceId = 0L

    @BeforeEach
    fun seed(): Unit =
        runBlocking {
            venueId =
                id(
                    "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                        "VALUES ('Test Venue', 'test-venue', 'Somewhere 1', 'Berlin', '10999') RETURNING id"
                )
            sourceId =
                id(
                    "INSERT INTO events.event_source (venue_id, name, slug, url, source_type) " +
                        "VALUES ($venueId, 'alpha', 'alpha', 'https://alpha.example', 'CASSIOPEIA') RETURNING id"
                )
        }

    @Test
    fun `a dismissed row leaves the worklist, and a changed title brings it back`(): Unit =
        runBlocking {
            val event = insertEvent("Die Nerven")

            dismiss("missingGenre", event).expectStatus().isCreated
            service.worklist(QualityIssue.MISSING_GENRE, "alpha", 50, 0).let {
                it.entries.shouldBeEmpty()
                it.dismissed shouldBe 1L
            }

            sql("UPDATE events.event SET title = 'Die Nerven (verlegt)' WHERE id = $event")
            service.worklist(QualityIssue.MISSING_GENRE, "alpha", 50, 0).let {
                it.entries.map { e -> e.id } shouldBe listOf(event)
                it.dismissed shouldBe 0L
            }
            dismissals.dismissals(QualityIssue.MISSING_GENRE).single().active shouldBe false
        }

    @Test
    fun `a new import flag brings a dismissed flagged row back`(): Unit =
        runBlocking {
            val event = insertEvent("Kein Bock auf Nazis")
            sql("INSERT INTO events.event_quality_flag (event_id, kind, value) VALUES ($event, 'NON_ARTIST_NAME', 'Special Guest')")

            dismiss("flaggedAtImport", event).expectStatus().isCreated
            service.worklist(QualityIssue.FLAGGED_AT_IMPORT, null, 50, 0).entries.shouldBeEmpty()

            sql("INSERT INTO events.event_quality_flag (event_id, kind, value) VALUES ($event, 'NON_GENRE_TOKEN', 'Party')")
            service.worklist(QualityIssue.FLAGGED_AT_IMPORT, null, 50, 0).entries.map { it.id } shouldBe listOf(event)
        }

    @Test
    fun `a renamed title-derived act brings a dismissed singleton back`(): Unit =
        runBlocking {
            val event = insertEvent("Sadtember")
            val artist = id("INSERT INTO events.artist (name, slug) VALUES ('Sadtember', 'sadtember') RETURNING id")
            sql("INSERT INTO events.event_artist (event_id, artist_id, title_derived) VALUES ($event, $artist, true)")

            dismiss("titleDerivedSingletons", event).expectStatus().isCreated
            service.worklist(QualityIssue.TITLE_DERIVED_SINGLETONS, null, 50, 0).entries.shouldBeEmpty()

            // Still the singleton shape, with other values: a new judgment is due.
            sql("UPDATE events.event SET title = 'Octoberfail' WHERE id = $event")
            sql("UPDATE events.artist SET name = 'Octoberfail' WHERE id = $artist")
            service.worklist(QualityIssue.TITLE_DERIVED_SINGLETONS, null, 50, 0).entries.map { it.id } shouldBe listOf(event)
        }

    @Test
    fun `a fingerprint read before a change is a 409, and nothing is stored`(): Unit =
        runBlocking {
            val event = insertEvent("Die Nerven")
            val seen = fingerprint("missingGenre", event)
            sql("UPDATE events.event SET title = 'Die Nerven live' WHERE id = $event")

            post("missingGenre", event, seen).expectStatus().isEqualTo(409)
            dismissals.dismissals(QualityIssue.MISSING_GENRE).shouldBeEmpty()
        }

    @Test
    fun `an event that is not on the worklist is a 409`(): Unit =
        runBlocking {
            val event = insertEvent("Die Nerven", genre = "Rock")
            post("missingGenre", event, "0".repeat(32)).expectStatus().isEqualTo(409)
        }

    @Test
    fun `the licence queue cannot be dismissed`(): Unit =
        runBlocking {
            val event = insertEvent("Die Nerven")
            post("unreviewedLicence", event, "0".repeat(32)).expectStatus().isBadRequest
            webTestClient
                .get()
                .uri("/api/admin/data-quality/worklist/dismissals?issue=unreviewedLicence")
                .exchange()
                .expectStatus()
                .isBadRequest
        }

    @Test
    fun `undo puts the row back, and an unknown id is a 404`(): Unit =
        runBlocking {
            val event = insertEvent("Die Nerven")
            val id =
                dismiss("missingGenre", event)
                    .expectStatus()
                    .isCreated
                    .expectBody(WorklistDismissalResponse::class.java)
                    .returnResult()
                    .responseBody!!
                    .id

            webTestClient
                .delete()
                .uri("/api/admin/data-quality/worklist/dismissals/$id")
                .exchange()
                .expectStatus()
                .isNoContent
            service.worklist(QualityIssue.MISSING_GENRE, null, 50, 0).entries.map { it.id } shouldBe listOf(event)

            webTestClient
                .delete()
                .uri("/api/admin/data-quality/worklist/dismissals/$id")
                .exchange()
                .expectStatus()
                .isNotFound
        }

    @Test
    fun `dismissing again replaces the fingerprint`(): Unit =
        runBlocking {
            val event = insertEvent("Die Nerven")
            dismiss("missingGenre", event).expectStatus().isCreated
            sql("UPDATE events.event SET title = 'Die Nerven live' WHERE id = $event")
            dismiss("missingGenre", event).expectStatus().isCreated

            dismissals.dismissals(QualityIssue.MISSING_GENRE).single().active shouldBe true
            service.worklist(QualityIssue.MISSING_GENRE, null, 50, 0).entries.shouldBeEmpty()
        }

    @Test
    fun `the purge deletes the dismissals of past events only`(): Unit =
        runBlocking {
            val past = insertEvent("Gestern", date = "2000-01-01")
            val coming = insertEvent("Morgen", date = "2099-01-01")
            dismiss("missingGenre", past).expectStatus().isCreated
            dismiss("missingGenre", coming).expectStatus().isCreated

            dismissals.purgePastDismissals() shouldBe 1L
            dismissals.dismissals(QualityIssue.MISSING_GENRE).map { it.eventId } shouldBe listOf(coming)
        }

    @Test
    fun `every fingerprint is valid SQL, and an issue without one has no fingerprint per entry`(): Unit =
        runBlocking {
            insertEvent("Die Nerven")
            QualityIssue.entries.forEach { issue ->
                val page = service.worklist(issue, "alpha", 50, 0)
                page.entries.forEach { if (issue.fingerprint == null) it.fingerprint shouldBe null else it.fingerprint shouldNotBe null }
                if (issue.fingerprint != null) dismissals.dismissals(issue)
            }
        }

    private fun dismiss(
        issue: String,
        eventId: Long
    ) = post(issue, eventId, fingerprint(issue, eventId))

    private fun fingerprint(
        issue: String,
        eventId: Long
    ): String =
        webTestClient
            .get()
            .uri("/api/admin/data-quality/worklist?issue=$issue&limit=500")
            .exchange()
            .expectBody(WorklistResponse::class.java)
            .returnResult()
            .responseBody!!
            .entries
            .single { it.id == eventId }
            .fingerprint!!

    private fun post(
        issue: String,
        eventId: Long,
        fingerprint: String
    ) = webTestClient
        .post()
        .uri("/api/admin/data-quality/worklist/dismissals")
        .bodyValue(mapOf("issue" to issue, "eventId" to eventId, "fingerprint" to fingerprint))
        .exchange()

    private suspend fun insertEvent(
        title: String,
        genre: String? = null,
        date: String = "2099-09-12"
    ): Long =
        id(
            """
            INSERT INTO events.event (venue_id, event_source_id, title, event_type, slug, event_date, source_id, genre)
            VALUES ($venueId, $sourceId, '$title', 'CONCERT', '${title.lowercase().replace(' ', '-')}-$date', DATE '$date',
                    'alpha:$title:$date', ${genre?.let { "'$it'" } ?: "NULL"})
            RETURNING id
            """.trimIndent()
        )

    private suspend fun id(statement: String): Long =
        databaseClient
            .sql(statement)
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun sql(statement: String) = databaseClient.sql(statement).await()
}
