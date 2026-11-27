package de.norm.events.account

import de.norm.events.BaseControllerTest
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle

/** The operator's side of "Report a problem" (#4399, #5603): the queue of every kind, resolving it, and the allowlist. */
class ReportAdminControllerTest : BaseControllerTest() {
    private var tonight = 0L
    private var later = 0L
    private var venue = 0L
    private var artist = 0L

    @BeforeEach
    fun seed() =
        runBlocking {
            sql("TRUNCATE TABLE accounts.session, accounts.account CASCADE")
            venue =
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
            artist = id("INSERT INTO events.artist (name, slug) VALUES ('Ada', 'ada') RETURNING id")
            sql(
                "INSERT INTO accounts.account (id, email, locale, can_report) VALUES " +
                    "('00000000-0000-4000-8000-000000000001', 'one@example.de', 'de', true), " +
                    "('00000000-0000-4000-8000-000000000002', 'two@example.de', 'en', false)"
            )
            sql(
                "INSERT INTO accounts.report (account_id, kind, subject_id, problems, note) VALUES " +
                    "('00000000-0000-4000-8000-000000000001', 'venue', $venue, ARRAY ['ADDRESS'], 'Moved'), " +
                    "('00000000-0000-4000-8000-000000000001', 'event', $later, ARRAY ['TITLE'], NULL), " +
                    "('00000000-0000-4000-8000-000000000001', 'event', $tonight, ARRAY ['ARTISTS', 'OTHER'], 'Wrong DJ'), " +
                    "('00000000-0000-4000-8000-000000000002', 'event', $tonight, ARRAY ['DATE_TIME'], NULL), " +
                    "('00000000-0000-4000-8000-000000000002', 'artist', $artist, ARRAY ['WRONG_ACT'], NULL)"
            )
        }

    @Test
    fun `the queue holds one entry per subject, events by date first, and never names the reporter`() {
        webTestClient
            .get()
            .uri("/api/admin/reports")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$[*].slug")
            .isEqualTo(listOf("tonight", "later", "ada", "test-venue"))
            .jsonPath("$[*].kind")
            .isEqualTo(listOf("events", "events", "artists", "venues"))
            .jsonPath("$[0].name")
            .isEqualTo("Tonight")
            .jsonPath("$[3].name")
            .isEqualTo("Test Venue")
            .jsonPath("$[3].eventDate")
            .doesNotExist()
            .jsonPath("$[2].reports[0].problems")
            .isEqualTo(listOf("WRONG_ACT"))
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

    private fun resolve(query: String) =
        webTestClient
            .delete()
            .uri("/api/admin/reports?$query")
            .exchange()

    @Test
    fun `resolving a subject deletes its reports and no others, and eventId alone still names an event`() {
        resolve("kind=events&id=$tonight")
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.resolved")
            .isEqualTo(2)
        resolve("kind=venues&id=$venue")
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.resolved")
            .isEqualTo(1)
        resolve("eventId=$later")
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.resolved")
            .isEqualTo(1)

        runBlocking { count("SELECT count(*) AS id FROM accounts.report") } shouldBe 1L
    }

    @Test
    fun `a resolve with an unknown kind, or with both forms, or with neither, is a 400`() {
        resolve("kind=festivals&id=1").expectStatus().isBadRequest
        resolve("kind=events&id=$tonight&eventId=$tonight").expectStatus().isBadRequest
        resolve("kind=events").expectStatus().isBadRequest
        resolve("").expectStatus().isBadRequest
    }

    @Test
    fun `the cap exemption is set by address, the account list shows it, and an unknown address is a 404`() {
        fun put(
            email: String,
            exempt: Boolean
        ) = webTestClient
            .put()
            .uri("/api/admin/accounts/report-cap-exempt")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ReportCapRequest(email, exempt))
            .exchange()

        put(" ONE@example.de ", true).expectStatus().isNoContent
        put("nobody@example.de", true).expectStatus().isNotFound

        runBlocking { count("SELECT count(*) AS id FROM accounts.account WHERE report_cap_exempt AND email = 'one@example.de'") } shouldBe 1L
        webTestClient
            .get()
            .uri("/api/admin/accounts")
            .exchange()
            .expectBody()
            .jsonPath("$[?(@.reportCapExempt == true)].email")
            .isEqualTo(listOf("one@example.de"))

        put("one@example.de", false).expectStatus().isNoContent
        runBlocking { count("SELECT count(*) AS id FROM accounts.account WHERE report_cap_exempt") } shouldBe 0L
    }

    @Test
    fun `the report flag is set by address, and an unknown address is a 404`() {
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
    fun `the database takes every box of every kind, and refuses an unknown problem, an empty list and an unknown kind`() {
        fun refused(
            problems: String,
            kind: String = "event"
        ) = runCatching {
            runBlocking {
                sql(
                    "INSERT INTO accounts.report (account_id, kind, subject_id, problems) " +
                        "VALUES ('00000000-0000-4000-8000-000000000002', '$kind', $later, $problems)"
                )
            }
        }.isFailure

        refused("ARRAY ['PROMOTER', 'GENRE', 'EVENT_TYPE', 'FEATURES', 'TEXT', 'ON_IMAGE', 'TYPE_GENRE', 'DUPLICATE', 'QUESTION']") shouldBe false
        refused("ARRAY ['NAME', 'ADDRESS', 'LINKS', 'CLOSED', 'WRONG_ACT', 'IMAGE', 'NOT_A_PROMOTER']", "promoter") shouldBe false
        refused("ARRAY ['WEATHER']") shouldBe true
        refused("ARRAY []::text[]") shouldBe true
        refused("ARRAY ['NAME']", "festival") shouldBe true
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
