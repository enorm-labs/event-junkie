package de.norm.events.dataquality

import de.norm.events.BaseControllerTest
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await

/**
 * The suggestion store's HTTP contract (#474 part A).
 *
 * No check writes suggestions yet, so each test inserts its rows with SQL, as a check will.
 * The assertions that matter most: the filters narrow, a typo in `status` is a 400 rather than an
 * empty page, dismiss refuses an accepted suggestion, and nothing here changes the event.
 */
class EventSuggestionControllerTest : BaseControllerTest() {
    @Test
    fun `the list pages newest first and carries the evidence on every item`(): Unit =
        runBlocking {
            val event = insertEvent("first")
            val older = insertSuggestion(event, field = "startTime", createdAt = "2026-10-01T10:00:00Z")
            val newer = insertSuggestion(event, field = "pricePresale", createdAt = "2026-10-02T10:00:00Z")
            val newest = insertSuggestion(event, field = "genre", createdAt = "2026-10-03T10:00:00Z")

            webTestClient
                .get()
                .uri("/api/admin/data-quality/suggestions?size=2")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(3)
                .jsonPath("$.totalPages")
                .isEqualTo(2)
                .jsonPath("$.content.length()")
                .isEqualTo(2)
                .jsonPath("$.content[0].id")
                .isEqualTo(newest)
                .jsonPath("$.content[1].id")
                .isEqualTo(newer)
                .jsonPath("$.content[0].evidence")
                .isEqualTo("Einlass 19 Uhr, Beginn 20 Uhr")
                .jsonPath("$.content[0].storedValue")
                .doesNotExist()
                .jsonPath("$.content[0].model")
                .isEqualTo("claude-haiku-4-5")
                .jsonPath("$.content[0].status")
                .isEqualTo("OPEN")

            webTestClient
                .get()
                .uri("/api/admin/data-quality/suggestions?size=2&page=1")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content.length()")
                .isEqualTo(1)
                .jsonPath("$.content[0].id")
                .isEqualTo(older)
        }

    @Test
    fun `eventId and status filter the list, alone and together`(): Unit =
        runBlocking {
            val first = insertEvent("first")
            val second = insertEvent("second")
            insertSuggestion(first)
            val dismissed = insertSuggestion(first, status = "DISMISSED")
            insertSuggestion(second)

            expectIds("eventId=$first", count = 2)
            expectIds("status=OPEN", count = 2)
            expectIds("eventId=$first&status=DISMISSED", count = 1, firstId = dismissed)
            expectIds("eventId=$second&status=DISMISSED", count = 0)
        }

    @Test
    fun `an unknown status is a 400 naming the valid values, not an empty page`() {
        webTestClient
            .get()
            .uri("/api/admin/data-quality/suggestions?status=open")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.detail")
            .value<String> { detail ->
                check(detail.contains("OPEN") && detail.contains("DISMISSED") && detail.contains("ACCEPTED")) {
                    "the error must list the valid values: $detail"
                }
            }
    }

    @Test
    fun `dismiss sets DISMISSED, is repeatable, and leaves the event alone`(): Unit =
        runBlocking {
            val event = insertEvent("first")
            val id = insertSuggestion(event)
            val before = eventSnapshot(event)

            repeat(2) {
                webTestClient
                    .post()
                    .uri("/api/admin/data-quality/suggestions/$id/dismiss")
                    .exchange()
                    .expectStatus()
                    .isOk
                    .expectBody()
                    .jsonPath("$.id")
                    .isEqualTo(id)
                    .jsonPath("$.status")
                    .isEqualTo("DISMISSED")
            }

            check(eventSnapshot(event) == before) { "dismissing a suggestion must not change the event" }
        }

    @Test
    fun `dismissing an unknown suggestion is a 404 Problem Detail`() {
        webTestClient
            .post()
            .uri("/api/admin/data-quality/suggestions/999999/dismiss")
            .exchange()
            .expectStatus()
            .isNotFound
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo(404)
            .jsonPath("$.detail")
            .isEqualTo("Suggestion with id 999999 not found")
    }

    @Test
    fun `an accepted suggestion is a 409 and stays accepted`(): Unit =
        runBlocking {
            val id = insertSuggestion(insertEvent("first"), status = "ACCEPTED")

            webTestClient
                .post()
                .uri("/api/admin/data-quality/suggestions/$id/dismiss")
                .exchange()
                .expectStatus()
                .isEqualTo(409)

            expectIds("status=ACCEPTED", count = 1, firstId = id)
        }

    @Test
    fun `a long proposal and its evidence come back whole`(): Unit =
        runBlocking {
            val evidence = "Beginn 20 Uhr. ".repeat(500)
            insertSuggestion(insertEvent("x".repeat(200)), evidence = evidence)

            webTestClient
                .get()
                .uri("/api/admin/data-quality/suggestions")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.content[0].evidence")
                .isEqualTo(evidence)
        }

    @Test
    fun `deleting the event deletes its suggestions`(): Unit =
        runBlocking {
            val event = insertEvent("first")
            insertSuggestion(event)
            databaseClient.sql("DELETE FROM events.event WHERE id = $event").await()

            expectIds("", count = 0)
        }

    private fun expectIds(
        query: String,
        count: Int,
        firstId: Long? = null
    ) {
        val body =
            webTestClient
                .get()
                .uri("/api/admin/data-quality/suggestions?$query")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.totalElements")
                .isEqualTo(count)
        if (firstId != null) body.jsonPath("$.content[0].id").isEqualTo(firstId)
    }

    private suspend fun eventSnapshot(id: Long): String =
        databaseClient
            .sql("SELECT row_to_json(e)::text AS j FROM events.event e WHERE id = $id")
            .map { row, _ -> row.get("j", String::class.java)!! }
            .one()
            .awaitSingle()

    private suspend fun insertEvent(title: String): Long {
        val venue =
            databaseClient
                .sql(
                    "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                        "VALUES ('V $title', 'v-${title.take(40)}', 'Somewhere 1', 'Berlin', '10999') RETURNING id"
                ).map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
                .one()
                .awaitSingle()
        return databaseClient
            .sql(
                "INSERT INTO events.event (venue_id, title, slug, event_date, source_id) " +
                    "VALUES ($venue, :title, :slug, DATE '2026-10-24', :slug) RETURNING id"
            ).bind("title", title)
            .bind("slug", "e-${title.take(40)}")
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .one()
            .awaitSingle()
    }

    private suspend fun insertSuggestion(
        eventId: Long,
        field: String = "startTime",
        status: String = "OPEN",
        evidence: String = "Einlass 19 Uhr, Beginn 20 Uhr",
        createdAt: String = "2026-10-09T10:00:00Z"
    ): Long =
        databaseClient
            .sql(
                """
                INSERT INTO events.event_suggestion
                    (event_id, field, stored_value, proposed_value, evidence, confidence, check_name, model, status, created_at)
                VALUES (:eventId, :field, NULL, '20:00', :evidence, 0.92, 'description-start-time', 'claude-haiku-4-5',
                        :status, CAST(:createdAt AS timestamptz))
                RETURNING id
                """.trimIndent()
            ).bind("eventId", eventId)
            .bind("field", field)
            .bind("evidence", evidence)
            .bind("status", status)
            .bind("createdAt", createdAt)
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .one()
            .awaitSingle()
}
