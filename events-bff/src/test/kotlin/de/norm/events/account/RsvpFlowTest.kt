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

/** "Interested" and "going" (#3892): set, change, withdraw, "My calendar", and the Following feed. */
class RsvpFlowTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    @Autowired
    private lateinit var rsvpRows: RsvpStore

    private lateinit var session: String
    private lateinit var csrf: String
    private val today: LocalDate = LocalDate.now(BERLIN)

    @BeforeEach
    fun signIn() =
        runBlocking {
            databaseClient.sql("TRUNCATE TABLE accounts.rsvp, accounts.follow, accounts.session, accounts.one_time_token, accounts.account CASCADE").await()
            session = sessionFor("fan@example.org")
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
            insertEvent(club, "Last month", "last-month", today.minusDays(30))
            Unit
        }

    private suspend fun sessionFor(email: String): String {
        val now = Instant.now()
        val account = accounts.signIn(email, "en", now)
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
        status: String,
        who: String = session
    ) = webTestClient
        .put()
        .uri("/me/rsvps/$slug")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(RsvpRequest(status))
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

    @Test
    fun `an RSVP is set, changed and withdrawn, and never cached`() {
        put("tonight", "interested").expectStatus().isNoContent
        put("tonight", "going").expectStatus().isNoContent

        get("/me/rsvps")
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .expectBody()
            .jsonPath("$.tonight")
            .isEqualTo("going")

        webTestClient
            .delete()
            .uri("/me/rsvps/tonight")
            .signedIn()
            .exchange()
            .expectStatus()
            .isNoContent
        get("/me/rsvps").expectBody().jsonPath("$.tonight").doesNotExist()
    }

    @Test
    fun `an unknown event is a 404, an unknown status a 400, and without a session a 401`() {
        put("nothing-here", "going").expectStatus().isNotFound
        put("tonight", "maybe").expectStatus().isBadRequest
        webTestClient
            .get()
            .uri("/me/rsvps")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `My calendar lists what is ahead, the history latest first, and a window`() {
        put("tonight", "going").expectStatus().isNoContent
        put("next-week", "interested").expectStatus().isNoContent
        put("last-month", "going").expectStatus().isNoContent

        get("/me/rsvps/upcoming").expectBody().jsonPath("$.content[*].slug").isEqualTo(listOf("tonight", "next-week"))
        get("/me/rsvps/past").expectBody().jsonPath("$.content[*].slug").isEqualTo(listOf("last-month"))
        get("/me/rsvps/calendar?from=${today.minusDays(1)}&to=${today.plusDays(1)}")
            .expectBody()
            .jsonPath("$[*].slug")
            .isEqualTo(listOf("tonight"))
    }

    @Test
    fun `an RSVPed event joins Following's feed without following anything`() {
        put("next-week", "interested").expectStatus().isNoContent

        get("/me/following").expectBody().jsonPath("$.content[*].slug").isEqualTo(listOf("next-week"))
    }

    @Test
    fun `RSVPs are private to their account`() {
        put("tonight", "going").expectStatus().isNoContent
        val other = runBlocking { sessionFor("other@example.org") }

        get("/me/rsvps", other).expectBody().jsonPath("$.tonight").doesNotExist()
        get("/me/rsvps/upcoming", other).expectBody().jsonPath("$.content.length()").isEqualTo(0)
    }

    @Test
    fun `the export carries the RSVPs, and an RSVP whose event is gone is purged`() {
        put("tonight", "going").expectStatus().isNoContent
        put("next-week", "interested").expectStatus().isNoContent

        get("/me/export").expectBody().jsonPath("$.rsvps.length()").isEqualTo(2)

        runBlocking {
            databaseClient.sql("DELETE FROM events.event WHERE slug = 'next-week'").await()
            rsvpRows.deleteOrphans() shouldBe 1L
        }
        get("/me/rsvps").expectBody().jsonPath("$.length()").isEqualTo(1)
    }
}
