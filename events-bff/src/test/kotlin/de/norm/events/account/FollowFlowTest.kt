package de.norm.events.account

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpHeaders
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Following artists, venues and promoters, and the events of what is followed (#3889). */
class FollowFlowTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    @Autowired
    private lateinit var follows: FollowStore

    private lateinit var session: String
    private lateinit var csrf: String
    private val today: LocalDate = LocalDate.now(BERLIN)

    @BeforeEach
    fun signIn() =
        runBlocking {
            databaseClient.sql("TRUNCATE TABLE accounts.follow, accounts.session, accounts.one_time_token, accounts.account CASCADE").await()
            val now = Instant.now()
            val account = accounts.signIn("fan@example.org", "en", now)
            session = SecretTokens.next()
            sessionRows.createSession(SecretTokens.hash(session), account.id, now, now.plus(Duration.ofDays(1)))
            csrf =
                webTestClient
                    .get()
                    .uri("/auth/csrf")
                    .exchange()
                    .returnResult(String::class.java)
                    .responseCookies
                    .getFirst(SessionSecurityContextRepository.CSRF_COOKIE)!!
                    .value
        }

    private fun WebTestClient.RequestHeadersSpec<*>.signedIn(): WebTestClient.RequestHeadersSpec<*> =
        cookie(SessionSecurityContextRepository.COOKIE, session)
            .cookie(SessionSecurityContextRepository.CSRF_COOKIE, csrf)
            .header("X-XSRF-TOKEN", csrf)

    private fun allFollows(): Long =
        runBlocking {
            databaseClient
                .sql("SELECT count(*) AS n FROM accounts.follow")
                .map { row -> row.get("n", Long::class.javaObjectType)!! }
                .one()
                .awaitSingle()
        }

    private fun put(path: String) =
        webTestClient
            .put()
            .uri(path)
            .signedIn()
            .exchange()

    private fun delete(path: String) =
        webTestClient
            .delete()
            .uri(path)
            .signedIn()
            .exchange()

    private fun get(path: String) =
        webTestClient
            .get()
            .uri(path)
            .signedIn()
            .exchange()

    @Test
    fun `follow is idempotent, shows in the list and the check, and unfollow ends it`() {
        runBlocking { insertVenue("Sisyphos", "sisyphos") }

        put("/me/follows/venue/sisyphos").expectStatus().isNoContent
        put("/me/follows/venue/sisyphos").expectStatus().isNoContent
        get("/me/follows/venue/sisyphos")
            .expectBody()
            .jsonPath("$.following")
            .isEqualTo(true)
        get("/me/follows")
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].kind")
            .isEqualTo("venue")
            .jsonPath("$[0].name")
            .isEqualTo("Sisyphos")
            .jsonPath("$[0].notify")
            .isEqualTo("off")

        delete("/me/follows/venue/sisyphos").expectStatus().isNoContent
        get("/me/follows/venue/sisyphos")
            .expectBody()
            .jsonPath("$.following")
            .isEqualTo(false)
    }

    @Test
    fun `an unknown slug or kind is a 404, and without a session a 401`() {
        put("/me/follows/venue/nowhere").expectStatus().isNotFound
        put("/me/follows/festival/anything").expectStatus().isNotFound
        webTestClient
            .get()
            .uri("/me/follows")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `a PUT without the CSRF header is refused`() {
        runBlocking { insertVenue("Sisyphos", "sisyphos") }

        webTestClient
            .put()
            .uri("/me/follows/venue/sisyphos")
            .cookie(SessionSecurityContextRepository.COOKIE, session)
            .exchange()
            .expectStatus()
            .isForbidden
    }

    @Test
    fun `following lists upcoming events of followed venues, artists and promoters only, by date, never cached`() {
        runBlocking {
            val club = insertVenue("Club", "club")
            val bar = insertVenue("Bar", "bar")
            val artist = insertArtist("Ada", "ada")
            val promoter = insertPromoter("Crew", "crew")
            insertEvent(club, "At the club", "at-the-club", today.plusDays(3))
            insertEvent(club, "Was at the club", "was-at-the-club", today.minusDays(3))
            linkArtist(insertEvent(bar, "Ada live", "ada-live", today.plusDays(1)), artist)
            linkPromoter(insertEvent(bar, "Crew night", "crew-night", today.plusDays(2)), promoter)
            insertEvent(bar, "Unrelated", "unrelated", today.plusDays(1))
        }
        put("/me/follows/venue/club").expectStatus().isNoContent
        put("/me/follows/artist/ada").expectStatus().isNoContent
        put("/me/follows/promoter/crew").expectStatus().isNoContent

        get("/me/following")
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .expectBody()
            .jsonPath("$.content[*].slug")
            .isEqualTo(listOf("ada-live", "crew-night", "at-the-club"))
    }

    @Test
    fun `today's followed events come on their own, and from tomorrow leaves them out of the rest`() {
        runBlocking {
            val club = insertVenue("Club", "club")
            insertEvent(club, "Tonight", "tonight", today)
            insertEvent(club, "Tomorrow", "tomorrow", today.plusDays(1))
            insertEvent(insertVenue("Bar", "bar"), "Elsewhere tonight", "elsewhere-tonight", today)
        }
        put("/me/follows/venue/club").expectStatus().isNoContent

        get("/me/following/today")
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .expectBody()
            .jsonPath("$[*].slug")
            .isEqualTo(listOf("tonight"))
        get("/me/following?from=${today.plusDays(1)}")
            .expectBody()
            .jsonPath("$.content[*].slug")
            .isEqualTo(listOf("tomorrow"))
        get("/me/following")
            .expectBody()
            .jsonPath("$.content[*].slug")
            .isEqualTo(listOf("tonight", "tomorrow"))
    }

    @Test
    fun `today without a session is a 401`() {
        webTestClient
            .get()
            .uri("/me/following/today")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `following nothing lists nothing`() {
        runBlocking { insertEvent(insertVenue("Club", "club"), "Party", "party", today.plusDays(1)) }

        get("/me/following")
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.content.length()")
            .isEqualTo(0)
    }

    @Test
    fun `the export carries the follows, and deleting the account removes them`() {
        runBlocking { insertArtist("Ada", "ada") }
        put("/me/follows/artist/ada").expectStatus().isNoContent

        get("/me/export")
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.follows[0].slug")
            .isEqualTo("ada")
        delete("/me").expectStatus().isNoContent
        allFollows() shouldBe 0L
    }

    @Test
    fun `a follow whose entity is gone drops out of the list and is purged`() {
        val ada = runBlocking { insertArtist("Ada", "ada") }
        put("/me/follows/artist/ada").expectStatus().isNoContent
        runBlocking { databaseClient.sql("DELETE FROM events.artist WHERE id = :id").bind("id", ada).await() }

        get("/me/follows")
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(0)
        runBlocking { follows.deleteOrphans() } shouldBe 1L
    }
}
