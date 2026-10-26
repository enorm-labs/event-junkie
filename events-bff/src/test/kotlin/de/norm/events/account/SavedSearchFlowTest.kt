package de.norm.events.account

import de.norm.events.BaseControllerTest
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

/** Saved searches (#3890): save, rename, delete, and what the query may hold. */
class SavedSearchFlowTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    private lateinit var session: String
    private lateinit var csrf: String

    @BeforeEach
    fun signIn() =
        runBlocking {
            databaseClient.sql("TRUNCATE TABLE accounts.follow, accounts.session, accounts.one_time_token, accounts.account CASCADE").await()
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

    private fun save(
        name: String,
        query: String,
        who: String = session
    ) = webTestClient
        .post()
        .uri("/me/searches")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(SavedSearchRequest(name, query))
        .signedIn(who)
        .exchange()

    private fun list(who: String = session) =
        webTestClient
            .get()
            .uri("/me/searches")
            .signedIn(who)
            .exchange()

    private fun idOf(response: WebTestClient.ResponseSpec): String =
        response
            .expectStatus()
            .isOk
            .expectBody(SavedSearchResponse::class.java)
            .returnResult()
            .responseBody!!
            .id
            .toString()

    @Test
    fun `a search is saved normalised, listed, renamed and deleted, and never cached`() {
        save("Free techno", "when=weekend&genre=techno&free=true")
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.query")
            .isEqualTo("free=true&genre=techno&when=weekend")
        val id = idOf(save("Free techno again", "genre=techno&free=true&when=weekend"))

        list()
            .expectHeader()
            .valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
            .expectBody()
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].name")
            .isEqualTo("Free techno")

        webTestClient
            .patch()
            .uri("/me/searches/$id")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(SavedSearchRename("  Weekend techno  "))
            .signedIn()
            .exchange()
            .expectBody()
            .jsonPath("$.name")
            .isEqualTo("Weekend techno")

        webTestClient
            .delete()
            .uri("/me/searches/$id")
            .signedIn()
            .exchange()
            .expectStatus()
            .isNoContent
        list().expectBody().jsonPath("$.length()").isEqualTo(0)
    }

    @Test
    fun `a value with a space or an umlaut survives the round trip`() {
        save("Neukölln", "district=Neuk%C3%B6lln&q=live+jazz")
            .expectBody()
            .jsonPath("$.query")
            .isEqualTo("district=Neuk%C3%B6lln&q=live%20jazz")
    }

    @Test
    fun `only the list's filters and a relative date can be saved`() {
        save("Dates", "from=2026-10-30&to=2026-11-01").expectStatus().isBadRequest
        save("Nothing", "").expectStatus().isBadRequest
        save("Odd preset", "when=someday").expectStatus().isBadRequest
        save("", "genre=techno").expectStatus().isBadRequest
        save("x".repeat(81), "genre=techno").expectStatus().isBadRequest
    }

    @Test
    fun `an account holds at most 20 searches`() {
        repeat(20) { i -> save("Search $i", "q=term$i").expectStatus().isOk }

        save("One more", "q=extra").expectStatus().isEqualTo(409)
    }

    @Test
    fun `another account's search can be neither seen, renamed nor deleted`() {
        val id = idOf(save("Mine", "genre=techno"))
        val other = runBlocking { sessionFor("other@example.org") }

        list(other).expectBody().jsonPath("$.length()").isEqualTo(0)
        webTestClient
            .patch()
            .uri("/me/searches/$id")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(SavedSearchRename("Theirs"))
            .signedIn(other)
            .exchange()
            .expectStatus()
            .isNotFound
        webTestClient
            .delete()
            .uri("/me/searches/$id")
            .signedIn(other)
            .exchange()
            .expectStatus()
            .isNoContent
        list().expectBody().jsonPath("$[0].name").isEqualTo("Mine")
    }

    @Test
    fun `the export carries the searches, and without a session the routes are a 401`() {
        save("Free techno", "genre=techno&free=true").expectStatus().isOk

        webTestClient
            .get()
            .uri("/me/export")
            .signedIn()
            .exchange()
            .expectBody()
            .jsonPath("$.searches[0].name")
            .isEqualTo("Free techno")
        webTestClient
            .get()
            .uri("/me/searches")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }
}
