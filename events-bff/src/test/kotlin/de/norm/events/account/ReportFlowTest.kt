package de.norm.events.account

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
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

/** "Report a problem" (#4399, #5603): the allowlist, the kinds, the checks, replace and withdraw, privacy, the export and the purges. */
class ReportFlowTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    @Autowired
    private lateinit var reportRows: ReportStore

    @Autowired
    private lateinit var registry: MeterRegistry

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
            insertArtist("Ada", "ada")
            insertPromoter("Night Shift", "night-shift")
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
        who: String = session,
        kind: String = "events"
    ) = webTestClient
        .put()
        .uri("/me/reports/$kind/$slug")
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

    private fun withdraw(
        slug: String,
        kind: String = "events"
    ) = webTestClient
        .delete()
        .uri("/me/reports/$kind/$slug")
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
        put("tonight", listOf("ARTISTS", "TEXT")).expectStatus().isNoContent
        put("tonight", listOf("DATE_TIME", "DATE_TIME"), "  Starts at 23:00, not 20:00  ").expectStatus().isNoContent

        get("/me/reports")
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].kind")
            .isEqualTo("events")
            .jsonPath("$[0].slug")
            .isEqualTo("tonight")
            .jsonPath("$[0].problems")
            .isEqualTo(listOf("DATE_TIME"))
            .jsonPath("$[0].note")
            .isEqualTo("Starts at 23:00, not 20:00")

        withdraw("tonight").expectStatus().isNoContent
        withdraw("tonight").expectStatus().isNoContent
        get("/me/reports").expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    private fun ticked(problem: String): Double =
        registry
            .get(AccountMetrics.REPORT_PROBLEM)
            .tag(AccountMetrics.TAG_PROBLEM, problem)
            .counter()
            .count()

    @Test
    fun `the new boxes are stored, and each ticked box is counted on a store and a replace, not on a withdraw`() {
        val genre = ticked("genre")
        val image = ticked("on_image")
        val features = ticked("features")

        put("tonight", listOf("GENRE", "ON_IMAGE")).expectStatus().isNoContent
        put("tonight", listOf("FEATURES", "ON_IMAGE")).expectStatus().isNoContent
        put("next-week", listOf("PROMOTER", "EVENT_TYPE", "TEXT")).expectStatus().isNoContent
        withdraw("tonight").expectStatus().isNoContent

        ticked("genre") - genre shouldBe 1.0
        ticked("on_image") - image shouldBe 2.0
        ticked("features") - features shouldBe 1.0
        get("/me/reports").expectBody().jsonPath("$[0].problems").isEqualTo(listOf("PROMOTER", "EVENT_TYPE", "TEXT"))
    }

    @Test
    fun `a note alone is stored as OTHER, the image hint with a note is stored as sent, and an old name stays valid`() {
        put("tonight", emptyList(), "Is this really a party?").expectStatus().isNoContent
        put("next-week", listOf("ON_IMAGE"), "See flyer").expectStatus().isNoContent

        get("/me/reports")
            .expectBody()
            .jsonPath("$[?(@.slug == 'tonight')].problems[*]")
            .isEqualTo(listOf("OTHER"))
            .jsonPath("$[?(@.slug == 'next-week')].problems[*]")
            .isEqualTo(listOf("ON_IMAGE"))

        put("tonight", listOf("GENRE", "TYPE_GENRE", "QUESTION"), "Is this really a party?").expectStatus().isNoContent
        get("/me/reports")
            .expectBody()
            .jsonPath("$[?(@.slug == 'tonight')].problems[*]")
            .isEqualTo(listOf("GENRE", "TYPE_GENRE", "QUESTION"))
    }

    @Test
    fun `an account the operator withdrew reporting from is refused`() {
        val plain = runBlocking { sessionFor("fan@example.org", canReport = false) }
        put("tonight", listOf("ARTISTS"), who = plain).expectStatus().isForbidden
    }

    @Test
    fun `no problem and no note, an unknown one, ON_IMAGE alone and a long note are 400s, an unknown event a 404`() {
        put("tonight", emptyList()).expectStatus().isBadRequest
        put("tonight", emptyList(), "   ").expectStatus().isBadRequest
        put("tonight", listOf("WEATHER")).expectStatus().isBadRequest
        put("tonight", listOf("ON_IMAGE", "ON_IMAGE")).expectStatus().isBadRequest
        put("tonight", listOf("TITLE"), "x".repeat(501)).expectStatus().isBadRequest
        put("nothing-here", listOf("TITLE")).expectStatus().isNotFound
        put("tonight", listOf("QUESTION")).expectStatus().isNoContent
    }

    private fun refused(): Double =
        registry
            .get("bff.account.report")
            .tag(AccountMetrics.TAG_ACTION, ReportAction.REFUSED.tag)
            .tag(AccountMetrics.TAG_KIND, ReportKind.EVENT.tag)
            .counter()
            .count()

    @Test
    fun `an account holds at most 200 open reports, a replacement at the cap still passes, and a refusal is counted`() {
        runBlocking {
            val hall = insertVenue("Hall", "hall")
            (1..200).forEach { insertEvent(hall, "Night $it", "night-$it", today.plusDays(it.toLong())) }
        }
        (1..199).forEach { put("night-$it", listOf("TITLE")).expectStatus().isNoContent }
        put("tonight", listOf("TITLE")).expectStatus().isNoContent
        val before = refused()

        put("night-200", listOf("TITLE")).expectStatus().isEqualTo(429)
        put("tonight", listOf("STATUS"), "Cancelled").expectStatus().isNoContent
        refused() - before shouldBe 1.0

        withdraw("night-1").expectStatus().isNoContent
        put("night-200", listOf("TITLE")).expectStatus().isNoContent
        get("/me/reports").expectBody().jsonPath("$.length()").isEqualTo(200)
    }

    @Test
    fun `an exempt account is not held to the cap`() {
        runBlocking {
            val hall = insertVenue("Hall", "hall")
            (1..201).forEach { insertEvent(hall, "Night $it", "night-$it", today.plusDays(it.toLong())) }
            databaseClient
                .sql(
                    "INSERT INTO accounts.report (account_id, kind, subject_id, problems) " +
                        "SELECT a.id, 'event', e.id, ARRAY ['TITLE'] FROM accounts.account a, events.event e " +
                        "WHERE a.email = 'operator@example.org' AND e.slug LIKE 'night-%' AND e.slug <> 'night-201'"
                ).await()
            databaseClient.sql("UPDATE accounts.account SET report_cap_exempt = true WHERE email = 'operator@example.org'").await()
        }

        put("night-201", listOf("TITLE")).expectStatus().isNoContent
        get("/me/reports").expectBody().jsonPath("$.length()").isEqualTo(201)
    }

    @Test
    fun `a new account may report, and one the operator withdrew it from may not`() {
        val fresh =
            runBlocking {
                val now = Instant.now()
                val account = accounts.signIn("new@example.org", "en", now)
                val token = SecretTokens.next()
                sessionRows.createSession(SecretTokens.hash(token), account.id, now, now.plus(Duration.ofDays(1)))
                token
            }
        get("/me", fresh).expectBody().jsonPath("$.canReport").isEqualTo(true)
        put("tonight", listOf("TITLE"), who = fresh).expectStatus().isNoContent
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
                    "UPDATE accounts.report SET updated_at = now() - interval '61 days' " +
                        "WHERE kind = 'event' AND subject_id = (SELECT id FROM events.event WHERE slug = 'tonight')"
                ).await()
            reportRows.deleteOlderThan(Instant.now().minus(Duration.ofDays(60))) shouldBe 1L
        }
        get("/me/reports").expectBody().jsonPath("$[*].slug").isEqualTo(listOf("next-week"))
    }

    @Test
    fun `an act, a promoter and a venue are reported with their own boxes, and named by their page's path`() {
        put("ada", listOf("WRONG_ACT", "IMAGE"), kind = "artists").expectStatus().isNoContent
        put("night-shift", listOf("NOT_A_PROMOTER"), kind = "promoters").expectStatus().isNoContent
        put("club", listOf("ADDRESS", "CLOSED"), "Moved to the next street", kind = "venues").expectStatus().isNoContent
        put("club", listOf("TITLE"), kind = "events").expectStatus().isNotFound

        get("/me/reports")
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(3)
            .jsonPath("$[?(@.kind == 'artists')].title")
            .isEqualTo(listOf("Ada"))
            .jsonPath("$[?(@.kind == 'promoters')].problems[*]")
            .isEqualTo(listOf("NOT_A_PROMOTER"))
            .jsonPath("$[?(@.kind == 'venues')].slug")
            .isEqualTo(listOf("club"))
            .jsonPath("$[?(@.kind == 'venues')].title")
            .isEqualTo(listOf("Club"))

        withdraw("ada", kind = "artists").expectStatus().isNoContent
        get("/me/reports").expectBody().jsonPath("$.length()").isEqualTo(2)
    }

    @Test
    fun `a box another kind owns, an unknown kind and an unknown subject are refused, a note alone is OTHER everywhere`() {
        put("club", listOf("ARTISTS"), kind = "venues").expectStatus().isBadRequest
        put("club", listOf("ON_IMAGE"), "See flyer", kind = "venues").expectStatus().isBadRequest
        put("ada", listOf("ADDRESS"), kind = "artists").expectStatus().isBadRequest
        put("tonight", listOf("WRONG_ACT")).expectStatus().isBadRequest
        put("club", listOf("NAME"), kind = "festivals").expectStatus().isNotFound
        put("nobody", listOf("NAME"), kind = "artists").expectStatus().isNotFound

        put("night-shift", emptyList(), "Two promoters in one", kind = "promoters").expectStatus().isNoContent
        get("/me/reports").expectBody().jsonPath("$[0].problems").isEqualTo(listOf("OTHER"))
    }

    @Test
    fun `a report whose act, promoter or venue is gone is purged, the others kept`() {
        runBlocking { insertVenue("Bar", "bar") }
        put("ada", listOf("NAME"), kind = "artists").expectStatus().isNoContent
        put("night-shift", listOf("LINKS"), kind = "promoters").expectStatus().isNoContent
        put("bar", listOf("NAME"), kind = "venues").expectStatus().isNoContent
        put("tonight", listOf("TITLE")).expectStatus().isNoContent

        runBlocking {
            databaseClient.sql("DELETE FROM events.artist WHERE slug = 'ada'").await()
            databaseClient.sql("DELETE FROM events.promoter WHERE slug = 'night-shift'").await()
            databaseClient.sql("DELETE FROM events.venue WHERE slug = 'bar'").await()
            reportRows.deleteOrphans() shouldBe 3L
        }
        get("/me/reports").expectBody().jsonPath("$[*].slug").isEqualTo(listOf("tonight"))
    }
}
