package de.norm.events.account

import de.norm.events.BaseControllerTest
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.r2dbc.core.await
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** Watching an act by its MusicBrainz id (#5214), end to end against a local MusicBrainz. */
@ExtendWith(OutputCaptureExtension::class)
class WatchFlowTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    private lateinit var session: String
    private lateinit var csrf: String
    private var requestsBefore = 0

    private fun requests() = server.requestCount - requestsBefore

    @BeforeEach
    fun signIn() =
        runBlocking<Unit> {
            databaseClient.sql("TRUNCATE TABLE accounts.follow, accounts.session, accounts.account CASCADE").await()
            generateSequence { server.takeRequest(0, TimeUnit.SECONDS) }.count() // Drains what an earlier test left.
            requestsBefore = server.requestCount
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

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun entity(
        id: String,
        name: String
    ) = json("""{"id":"$id","name":"$name","type":"Group","country":"DE","disambiguation":"Berlin trio"}""")

    private fun watch(
        mbid: String,
        who: String = session
    ) = webTestClient
        .put()
        .uri("/me/watches/$mbid")
        .signedIn(who)
        .exchange()

    private fun watches(who: String = session): List<WatchResponse> =
        webTestClient
            .get()
            .uri("/me/watches")
            .signedIn(who)
            .exchange()
            .expectStatus()
            .isOk
            .expectBodyList(WatchResponse::class.java)
            .returnResult()
            .responseBody!!

    private suspend fun exact(
        mbid: String,
        slug: String
    ): Long =
        insertArtist(slug.replaceFirstChar { it.uppercase() }, slug).also { id ->
            databaseClient.sql("UPDATE events.artist SET musicbrainz_match = 'EXACT', musicbrainz_id = '$mbid' WHERE id = $id").await()
        }

    @Test
    fun `every route needs a session`() {
        webTestClient
            .get()
            .uri("/me/musicbrainz/artists?q=moderat")
            .exchange()
            .expectStatus()
            .isUnauthorized
        webTestClient
            .get()
            .uri("/me/watches")
            .exchange()
            .expectStatus()
            .isUnauthorized
        requests() shouldBe 0
    }

    @Test
    fun `the picker lists candidates, and names the artist row that already carries one`(output: CapturedOutput) =
        runBlocking<Unit> {
            exact(KNOWN, "known")
            server.enqueue(json("""{"artists":[{"id":"$MODERAT","name":"Moderat"},{"id":"$KNOWN","name":"Known"}]}"""))

            webTestClient
                .get()
                .uri("/me/musicbrainz/artists?q=Moderat Secret")
                .signedIn()
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$[0].mbid")
                .isEqualTo(MODERAT)
                .jsonPath("$[0].followSlug")
                .doesNotExist()
                .jsonPath("$[1].followSlug")
                .isEqualTo("known")

            // The term never reaches the log: neither the access line nor the call out. The access line is written in
            // `doFinally`, after the response the client already has, so wait for it before asserting what is absent.
            eventually(5.seconds) { output.out shouldContain "/api/me/musicbrainz/artists?[masked]" }
            output.out shouldNotContain "Secret"
        }

    @Test
    fun `refuses a term too short or too long, without asking MusicBrainz`() {
        listOf("ab", "x".repeat(101)).forEach { q ->
            webTestClient
                .get()
                .uri("/me/musicbrainz/artists?q=$q")
                .signedIn()
                .exchange()
                .expectStatus()
                .isBadRequest
        }
        requests() shouldBe 0
    }

    @Test
    fun `a watch takes its name from MusicBrainz, once, with the bell on`() =
        runBlocking<Unit> {
            server.enqueue(entity(MODERAT, "Moderat"))

            watch(MODERAT)
                .expectStatus()
                .isOk
                .expectBody(WatchOutcome::class.java)
                .isEqualTo(WatchOutcome("watch", null))
            watch(MODERAT).expectStatus().isOk

            watches().map { it.mbid to it.name to it.notify } shouldBe listOf(MODERAT to "Moderat" to "instant")
            requests() shouldBe 1
        }

    @Test
    fun `a merged id is stored as the id it was merged into`() =
        runBlocking<Unit> {
            server.enqueue(entity(MODERAT, "Moderat"))

            watch(OLD).expectStatus().isOk

            watches().map { it.mbid } shouldBe listOf(MODERAT)
        }

    @Test
    fun `watching an id an artist row carries follows that artist, without asking MusicBrainz`() =
        runBlocking<Unit> {
            exact(KNOWN, "known")

            watch(KNOWN)
                .expectStatus()
                .isOk
                .expectBody(WatchOutcome::class.java)
                .isEqualTo(WatchOutcome("artist", "known"))

            watches().shouldBeEmpty()
            webTestClient
                .get()
                .uri("/me/follows/artist/known")
                .signedIn()
                .exchange()
                .expectBody(FollowState::class.java)
                .isEqualTo(FollowState(following = true, notify = "instant"))
            requests() shouldBe 0
        }

    @Test
    fun `an unknown id is a 404, an unanswered MusicBrainz a 503, and a malformed id a 400`() {
        server.enqueue(MockResponse.Builder().code(404).build())
        // Two: the search retries one 503 (#5227), and an unconsumed answer would reach the next test on this shared server.
        server.enqueue(MockResponse.Builder().code(503).build())
        server.enqueue(MockResponse.Builder().code(503).build())

        watch(MODERAT).expectStatus().isNotFound
        watch(MODERAT).expectStatus().isEqualTo(503)
        watch("not-an-id").expectStatus().isBadRequest
        watches().shouldBeEmpty()
    }

    @Test
    fun `a watch counts toward the cap of follows`() =
        runBlocking<Unit> {
            val account = accounts.signIn("fan@example.org", "en", Instant.now())
            databaseClient
                .sql(
                    "INSERT INTO accounts.follow (account_id, kind, query, name) " +
                        "SELECT '${account.id}', 'search', 'q=' || n, 'search ' || n FROM generate_series(1, ${MAX_FOLLOWS}) n"
                ).await()
            server.enqueue(entity(MODERAT, "Moderat"))

            watch(MODERAT).expectStatus().isEqualTo(409)
        }

    @Test
    fun `an account removes only its own watch, and the export lists it`() =
        runBlocking<Unit> {
            server.enqueue(entity(MODERAT, "Moderat"))
            watch(MODERAT).expectStatus().isOk
            val other = sessionFor("other@example.org")

            webTestClient
                .delete()
                .uri("/me/watches/$MODERAT")
                .signedIn(other)
                .exchange()
                .expectStatus()
                .isNoContent
            watches().map { it.mbid } shouldBe listOf(MODERAT)

            webTestClient
                .get()
                .uri("/me/export")
                .signedIn()
                .exchange()
                .expectBody()
                .jsonPath("$.watches[0].mbid")
                .isEqualTo(MODERAT)

            webTestClient
                .delete()
                .uri("/me/watches/$MODERAT")
                .signedIn()
                .exchange()
                .expectStatus()
                .isNoContent
            watches().shouldBeEmpty()
        }

    companion object {
        private val server = MockWebServer().apply { start() }
        private const val MODERAT = "2d2a7d9e-6cf0-4f2f-9d5c-4b9b1e3a7f10"
        private const val OLD = "11111111-2222-4333-8444-555555555555"
        private const val KNOWN = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

        @JvmStatic
        @DynamicPropertySource
        fun musicBrainz(registry: DynamicPropertyRegistry) {
            registry.add("app.musicbrainz.url") { server.url("/ws/2/").toString() }
            registry.add("app.musicbrainz.pace") { "PT0S" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.close()
    }
}
