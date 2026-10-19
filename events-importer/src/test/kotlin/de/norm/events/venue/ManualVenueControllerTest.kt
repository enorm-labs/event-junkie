package de.norm.events.venue

import de.norm.events.BaseControllerTest
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.test.web.reactive.server.expectBody
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** `GET /api/admin/venues/manual` and `PUT /api/admin/venues/{id}/programme-checked` against a real database (#2993). */
class ManualVenueControllerTest : BaseControllerTest() {
    private fun manual(): List<ManualVenueResponse> =
        webTestClient
            .get()
            .uri("/api/admin/venues/manual")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody<List<ManualVenueResponse>>()
            .returnResult()
            .responseBody!!

    @Test
    fun `lists open venues without a source, never checked first, then the oldest check`(): Unit =
        runBlocking {
            insertVenue("checked-late", checkedAt = Instant.parse("2026-10-10T08:00:00Z"))
            insertVenue("checked-early", checkedAt = Instant.parse("2026-09-01T08:00:00Z"))
            insertVenue("never-checked", programme = "https://never.example/programm")
            insertVenue("closed-club", closedOn = LocalDate.parse("2026-08-30"))
            val imported = insertVenue("imported-club")
            databaseClient
                .sql("INSERT INTO events.event_source (venue_id, name, slug, url, source_type) VALUES ($imported, 'i', 'i', 'https://i.test/', 'CASSIOPEIA')")
                .await()

            val rows = manual()

            rows.map { it.slug } shouldBe listOf("never-checked", "checked-early", "checked-late")
            rows.first().programmeUrl shouldBe "https://never.example/programm"
            rows.first().programmeCheckedAt.shouldBeNull()
            rows[1].programmeCheckedAt shouldBe Instant.parse("2026-09-01T08:00:00Z")
        }

    @Test
    fun `carries when the newest event at the venue was entered, and null without one`(): Unit =
        runBlocking {
            val withEvents = insertVenue("with-events")
            insertVenue("no-events")
            insertEvent(withEvents, "manual:1", Instant.parse("2026-10-01T10:00:00Z"))
            insertEvent(withEvents, "manual:2", Instant.parse("2026-10-14T19:30:00Z"))

            val rows = manual().associateBy { it.slug }

            rows.getValue("with-events").newestEventCreatedAt shouldBe Instant.parse("2026-10-14T19:30:00Z")
            rows.getValue("no-events").newestEventCreatedAt.shouldBeNull()
        }

    @Test
    fun `PUT programme-checked sets the time to now and leaves the other fields`(): Unit =
        runBlocking {
            val id = insertVenue("hinterhof", programme = "https://hinterhof.example/programm")
            val before = Instant.now().truncatedTo(ChronoUnit.SECONDS)

            val venue =
                webTestClient
                    .put()
                    .uri("/api/admin/venues/$id/programme-checked")
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody<VenueResponse>()
                    .returnResult()
                    .responseBody!!

            venue.id shouldBe id
            venue.programmeUrl shouldBe "https://hinterhof.example/programm"
            val checkedAt = venue.programmeCheckedAt.shouldNotBeNull()
            (checkedAt >= before) shouldBe true
            manual().single().programmeCheckedAt shouldBe checkedAt
        }

    @Test
    fun `a venue PUT keeps the programme check`(): Unit =
        runBlocking {
            val checkedAt = Instant.parse("2026-10-12T09:00:00Z")
            val id = insertVenue("astra-kulturhaus", checkedAt = checkedAt)

            webTestClient
                .put()
                .uri("/api/admin/venues/$id")
                .bodyValue(VenueRequestFixtures.astra())
                .exchange()
                .expectStatus()
                .isOk
                .expectBody<VenueResponse>()
                .returnResult()
                .responseBody!!
                .programmeCheckedAt shouldBe checkedAt
        }

    @Test
    fun `PUT programme-checked on an unknown id is a 404`() {
        webTestClient
            .put()
            .uri("/api/admin/venues/999999/programme-checked")
            .exchange()
            .expectStatus()
            .isNotFound
    }

    private suspend fun insertVenue(
        slug: String,
        programme: String? = null,
        checkedAt: Instant? = null,
        closedOn: LocalDate? = null
    ): Long {
        var spec =
            databaseClient
                .sql(
                    "INSERT INTO events.venue (name, slug, programme_url, programme_checked_at, closed_on) " +
                        "VALUES (:slug, :slug, :programme, :checkedAt, :closedOn) RETURNING id"
                ).bind("slug", slug)
        spec = programme?.let { spec.bind("programme", it) } ?: spec.bindNull("programme", String::class.java)
        spec = checkedAt?.let { spec.bind("checkedAt", it) } ?: spec.bindNull("checkedAt", Instant::class.java)
        spec = closedOn?.let { spec.bind("closedOn", it) } ?: spec.bindNull("closedOn", LocalDate::class.java)
        return spec.map { row, _ -> row.get("id", Number::class.java)!!.toLong() }.awaitSingle()
    }

    private suspend fun insertEvent(
        venueId: Long,
        sourceId: String,
        createdAt: Instant
    ) {
        databaseClient
            .sql(
                "INSERT INTO events.event (venue_id, title, slug, event_date, source_id, created_at) " +
                    "VALUES (:venueId, :sourceId, :sourceId, DATE '2026-11-01', :sourceId, :createdAt)"
            ).bind("venueId", venueId)
            .bind("sourceId", sourceId)
            .bind("createdAt", createdAt)
            .await()
    }
}
