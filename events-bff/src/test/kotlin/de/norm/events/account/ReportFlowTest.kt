package de.norm.events.account

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** "Report a problem" (#4399): the allowlist, the checks, replace and withdraw, privacy, the export and the purges. */
class ReportFlowTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    @Autowired
    private lateinit var reportRows: ReportStore

    private lateinit var session: String
    private lateinit var csrf: String
    private val today: LocalDate = LocalDate.now(BERLIN)

    @BeforeEach
    fun signIn() =
        runBlocking {
            databaseClient.sql("TRUNCATE TABLE accounts.session, accounts.one_time_token, accounts.account CASCADE").await()
            session = sessionFor("operator@example.org", canReport = true)
            csrf =
                webTestClient
                    .get()
                    .uri("/auth/csrf")
                    .exchange()
                    .returnResult(String::class.java)
                    .responseCookies
                    .getFirst(SessionSecurityContextRepository.CSRF_COOKIE)!!
                    .value
            val club = insertVenue("Club", "club")
            insertEvent(club, "Tonight", "tonight", today)
            insertEvent(club, "Next week", "next-week", today.plusDays(7))
            Unit
        }

    private suspend fun sessionFor(
        email: String,
        canReport: Boolean
    ): String {
        val now = Instant.now()
        val account = accounts.signIn(email, "en", now)
        databaseClient
            .sql("UPDATE accounts.account SET can_report = :flag WHERE id = :id")
            .bind("flag", canReport)
            .bind("id", account.id)
            .await()
        val token = SecretTokens.next()
        sessionRows.createSession(SecretTokens.hash(token), account.id, now, now.plus(Duration.ofDays(1)))
        return token
    }

    private fun WebTestClient.RequestHeadersSpec<*>.signedIn(who: String = session): WebTestClient.RequestHeadersSpec<*> =
        cookie(SessionSecurityContextRepository.COOKIE, who)
            .cookie(SessionSecurityContextRepository.CSRF_COOKIE, csrf)
            .header("X-XSRF-TOKEN", csrf)

    private fun put(
        slug: String,
        problems: List<String>,
        note: String? = null,
        who: String = session
    ) = webTestClient
        .put()
        .uri("/me/reports/$slug")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(ReportRequest(problems, note))
        .signedIn(who)
        .exchange()

    private fun get(
        path: String,
        who: String = session
    ) = webTestClient
        .get()
        .uri(path)
        .signedIn(who)
        .exchange()

    private fun withdraw(slug: String) =
        webTestClient
            .delete()
            .uri("/me/reports/$slug")
            .signedIn()
            .exchange()

    @Test
    fun `the session says whether the account may report`() {
        get("/me").expectBody().jsonPath("$.canReport").isEqualTo(true)
        val plain = runBlocking { sessionFor("fan@example.org", canReport = false) }
        get("/me", plain).expectBody().jsonPath("$.canReport").isEqualTo(false)
    }

    @Test
    fun `a report is stored, replaced and withdrawn, and never cached`() {
        put("tonight", listOf("ARTISTS", "TITLE")).expectStatus().isNoContent
        put("tonight", listOf("DATE_TIME", "DATE_TIME"), "  Starts at 23:00, not 20:00  ").expectStatus().isNoContent

        get("/me/reports")
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].event")
            .isEqualTo("tonight")
            .jsonPath("$[0].problems")
            .isEqualTo(listOf("DATE_TIME"))
            .jsonPath("$[0].note")
            .isEqualTo("Starts at 23:00, not 20:00")

        withdraw("tonight").expectStatus().isNoContent
        withdraw("tonight").expectStatus().isNoContent
        get("/me/reports").expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    @Test
    fun `an account the operator has not allowed is refused`() {
        val plain = runBlocking { sessionFor("fan@example.org", canReport = false) }
        put("tonight", listOf("ARTISTS"), who = plain).expectStatus().isForbidden
    }

    @Test
    fun `no problem, an unknown one, OTHER without a note and a long note are 400s, an unknown event a 404`() {
        put("tonight", emptyList()).expectStatus().isBadRequest
        put("tonight", listOf("WEATHER")).expectStatus().isBadRequest
        put("tonight", listOf("OTHER"), "   ").expectStatus().isBadRequest
        put("tonight", listOf("TITLE"), "x".repeat(501)).expectStatus().isBadRequest
        put("nothing-here", listOf("TITLE")).expectStatus().isNotFound
        put("tonight", listOf("OTHER"), "Wrong venue").expectStatus().isNoContent
    }

    @Test
    fun `an account can hold any number of open reports`() {
        runBlocking {
            val club = insertVenue("Hall", "hall")
            (1..40).forEach { insertEvent(club, "Night $it", "night-$it", today.plusDays(it.toLong())) }
        }
        (1..40).forEach { put("night-$it", listOf("TITLE")).expectStatus().isNoContent }

        put("tonight", listOf("TITLE")).expectStatus().isNoContent
        get("/me/reports").expectBody().jsonPath("$.length()").isEqualTo(41)
    }

    @Test
    fun `reports are private to their account`() {
        put("tonight", listOf("TITLE")).expectStatus().isNoContent
        val other = runBlocking { sessionFor("friend@example.org", canReport = true) }

        get("/me/reports", other).expectBody().jsonPath("$.length()").isEqualTo(0)
        get("/me/export", other).expectBody().jsonPath("$.reports.length()").isEqualTo(0)
    }

    @Test
    fun `the export carries the reports, and a report whose event is gone is purged`() {
        put("tonight", listOf("TITLE")).expectStatus().isNoContent
        put("next-week", listOf("STATUS")).expectStatus().isNoContent

        get("/me/export").expectBody().jsonPath("$.reports.length()").isEqualTo(2)

        runBlocking {
            databaseClient.sql("DELETE FROM events.event WHERE slug = 'next-week'").await()
            reportRows.deleteOrphans() shouldBe 1L
        }
        get("/me/reports").expectBody().jsonPath("$.length()").isEqualTo(1)
    }

    @Test
    fun `a report unchanged for 60 days is purged, a fresher one kept`() {
        put("tonight", listOf("TITLE")).expectStatus().isNoContent
        put("next-week", listOf("STATUS")).expectStatus().isNoContent
        runBlocking {
            databaseClient
                .sql(
                    "UPDATE accounts.event_report SET updated_at = now() - interval '61 days' " +
                        "WHERE event_id = (SELECT id FROM events.event WHERE slug = 'tonight')"
                ).await()
            reportRows.deleteOlderThan(Instant.now().minus(Duration.ofDays(60))) shouldBe 1L
        }
        get("/me/reports").expectBody().jsonPath("$[*].event").isEqualTo(listOf("next-week"))
    }
}
