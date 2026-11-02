package de.norm.events.account

import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle

/** The operator's side of "Report a problem" (#4399): the queue, resolving it, and the allowlist. */
class ReportAdminControllerTest : BaseControllerTest() {
    private var tonight = 0L
    private var later = 0L

    @BeforeEach
    fun seed() =
        runBlocking {
            sql("TRUNCATE TABLE accounts.session, accounts.account CASCADE")
            val venue =
                id(
                    "INSERT INTO events.venue (name, slug, address, city, postal_code) " +
                        "VALUES ('Test Venue', 'test-venue', 'Somewhere 1', 'Berlin', '10999') RETURNING id"
                )
            val source =
                id(
                    "INSERT INTO events.event_source (venue_id, name, slug, url, source_type) " +
                        "VALUES ($venue, 'alpha', 'alpha', 'https://alpha.example', 'CASSIOPEIA') RETURNING id"
                )
            tonight = event(venue, source, "Tonight", "2026-11-02")
            later = event(venue, source, "Later", "2026-11-20")
            sql(
                "INSERT INTO accounts.account (id, email, locale, can_report) VALUES " +
                    "('00000000-0000-4000-8000-000000000001', 'one@example.de', 'de', true), " +
                    "('00000000-0000-4000-8000-000000000002', 'two@example.de', 'en', false)"
            )
            sql(
                "INSERT INTO accounts.event_report (account_id, event_id, problems, note) VALUES " +
                    "('00000000-0000-4000-8000-000000000001', $later, ARRAY ['TITLE'], NULL), " +
                    "('00000000-0000-4000-8000-000000000001', $tonight, ARRAY ['ARTISTS', 'OTHER'], 'Wrong DJ'), " +
                    "('00000000-0000-4000-8000-000000000002', $tonight, ARRAY ['DATE_TIME'], NULL)"
            )
        }

    @Test
    fun `the queue holds one entry per event, by date, and never names the reporter`() {
        webTestClient
            .get()
            .uri("/api/admin/reports")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[*].slug")
            .isEqualTo(listOf("tonight", "later"))
            .jsonPath("$[0].sourceSlug")
            .isEqualTo("alpha")
            .jsonPath("$[0].venue")
            .isEqualTo("Test Venue")
            .jsonPath("$[0].reports.length()")
            .isEqualTo(2)
            .jsonPath("$[1].reports[0].problems")
            .isEqualTo(listOf("TITLE"))
            .consumeWith { it.responseBody!!.decodeToString().contains("example.de") shouldBe false }
    }

    @Test
    fun `resolving an event deletes its reports and no others`() {
        webTestClient
            .delete()
            .uri("/api/admin/reports?eventId=$tonight")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.resolved")
            .isEqualTo(2)

        runBlocking { count("SELECT count(*) AS id FROM accounts.event_report") } shouldBe 1L
    }

    @Test
    fun `the allowlist is set by address, and an unknown address is a 404`() {
        fun put(
            email: String,
            enabled: Boolean
        ) = webTestClient
            .put()
            .uri("/api/admin/accounts/can-report")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(CanReportRequest(email, enabled))
            .exchange()

        put(" TWO@example.de ", true).expectStatus().isNoContent
        put("one@example.de", false).expectStatus().isNoContent
        put("nobody@example.de", true).expectStatus().isNotFound

        runBlocking { count("SELECT count(*) AS id FROM accounts.account WHERE can_report") } shouldBe 1L
        runBlocking { count("SELECT count(*) AS id FROM accounts.account WHERE can_report AND email = 'two@example.de'") } shouldBe 1L
    }

    @Test
    fun `the database refuses an unknown problem and an empty list`() {
        fun refused(problems: String) =
            runCatching {
                runBlocking {
                    sql(
                        "INSERT INTO accounts.event_report (account_id, event_id, problems) " +
                            "VALUES ('00000000-0000-4000-8000-000000000002', $later, $problems)"
                    )
                }
            }.isFailure

        refused("ARRAY ['WEATHER']") shouldBe true
        refused("ARRAY []::text[]") shouldBe true
    }

    private suspend fun event(
        venue: Long,
        source: Long,
        title: String,
        date: String
    ): Long =
        id(
            "INSERT INTO events.event (venue_id, event_source_id, title, event_type, slug, event_date, source_id) " +
                "VALUES ($venue, $source, '$title', 'CONCERT', '${title.lowercase()}', DATE '$date', 'alpha:$title') RETURNING id"
        )

    private suspend fun id(statement: String): Long =
        databaseClient
            .sql(statement)
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .awaitSingle()

    private suspend fun count(statement: String): Long = id(statement)

    private suspend fun sql(statement: String) = databaseClient.sql(statement).await()
}
