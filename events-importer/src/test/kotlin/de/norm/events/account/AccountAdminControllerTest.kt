package de.norm.events.account

import de.norm.events.BaseControllerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle

/** The operator's list of the visitor accounts (#5317). */
class AccountAdminControllerTest : BaseControllerTest() {
    @BeforeEach
    fun seed() =
        runBlocking {
            sql("TRUNCATE TABLE accounts.session, accounts.account CASCADE")
            val venue =
                id(
                    "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                        "VALUES ('Test Venue', 'test-venue', 'Somewhere 1', 'Berlin', '10999') RETURNING id"
                )
            val event =
                id(
                    "INSERT INTO events.event (venue_id, title, event_type, slug, event_date, source_id) " +
                        "VALUES ($venue, 'Tonight', 'CONCERT', 'tonight', DATE '2026-11-02', 'manual:tonight') RETURNING id"
                )
            // Created out of address order, so the order the test reads is the creation order.
            sql(
                "INSERT INTO accounts.account (id, email, locale, can_report, created_at, last_sign_in_at) VALUES " +
                    "('00000000-0000-4000-8000-000000000001', 'zoe@example.org', 'de', true, " +
                    "TIMESTAMPTZ '2026-10-01T10:00:00Z', TIMESTAMPTZ '2026-11-19T08:00:00Z'), " +
                    "('00000000-0000-4000-8000-000000000002', 'Anna@example.org', 'en', false, " +
                    "TIMESTAMPTZ '2026-10-15T10:00:00Z', TIMESTAMPTZ '2026-10-15T10:00:00Z')"
            )
            sql(
                "INSERT INTO accounts.follow (account_id, kind, entity_id, query, name) VALUES " +
                    "('00000000-0000-4000-8000-000000000001', 'venue', $venue, NULL, NULL), " +
                    "('00000000-0000-4000-8000-000000000001', 'search', NULL, 'genre=techno', 'Techno')"
            )
            // The second report's event is gone, so the queue does not show it and the count leaves it out.
            sql(
                "INSERT INTO accounts.event_report (account_id, event_id, problems) VALUES " +
                    "('00000000-0000-4000-8000-000000000001', $event, ARRAY ['TITLE']), " +
                    "('00000000-0000-4000-8000-000000000001', ${event + 1000}, ARRAY ['TITLE'])"
            )
        }

    @Test
    fun `every account, oldest first, with its follows and its open reports`() {
        webTestClient
            .get()
            .uri("/api/admin/accounts")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[*].email")
            .isEqualTo(listOf("zoe@example.org", "Anna@example.org"))
            .jsonPath("$[0].locale")
            .isEqualTo("de")
            .jsonPath("$[0].createdAt")
            .isEqualTo("2026-10-01T10:00:00Z")
            .jsonPath("$[0].lastSignInAt")
            .isEqualTo("2026-11-19T08:00:00Z")
            .jsonPath("$[0].canReport")
            .isEqualTo(true)
            .jsonPath("$[0].follows")
            .isEqualTo(2)
            .jsonPath("$[0].openReports")
            .isEqualTo(1)
            .jsonPath("$[1].locale")
            .isEqualTo("en")
            .jsonPath("$[1].canReport")
            .isEqualTo(false)
            .jsonPath("$[1].follows")
            .isEqualTo(0)
            .jsonPath("$[1].openReports")
            .isEqualTo(0)
    }

    private suspend fun id(statement: String): Long =
        databaseClient
            .sql(statement)
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun sql(statement: String) = databaseClient.sql(statement).await()
}
