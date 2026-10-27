package de.norm.events.account

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import de.norm.events.push.WebPushCrypto
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
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
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Web Push end to end (#3894, ADR-055): subscribe, the bell, and the new-dates job against a local push service. */
class PushFlowTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    @Autowired
    private lateinit var job: NewDatesJob

    private lateinit var session: String
    private lateinit var csrf: String
    private lateinit var venue: String
    private val today: LocalDate = LocalDate.now(BERLIN)

    @BeforeEach
    fun signIn() =
        runBlocking<Unit> {
            databaseClient.sql("TRUNCATE TABLE accounts.push_subscription, accounts.follow, accounts.session, accounts.account CASCADE").await()
            databaseClient.sql("UPDATE accounts.push_cursor SET sent_until = now() - interval '1 hour'").await()
            generateSequence { server.takeRequest(0, TimeUnit.SECONDS) }.count() // Drains what an earlier test left.
            session = sessionFor("fan@example.org", "de")
            csrf =
                webTestClient
                    .get()
                    .uri("/auth/csrf")
                    .exchange()
                    .returnResult(String::class.java)
                    .responseCookies
                    .getFirst(SessionSecurityContextRepository.CSRF_COOKIE)!!
                    .value
            insertVenue("Club", "club")
            venue = "club"
        }

    private suspend fun sessionFor(
        email: String,
        locale: String = "en"
    ): String {
        val now = Instant.now()
        val account = accounts.signIn(email, locale, now)
        val token = SecretTokens.next()
        sessionRows.createSession(SecretTokens.hash(token), account.id, now, now.plus(Duration.ofDays(1)))
        return token
    }

    private fun WebTestClient.RequestHeadersSpec<*>.signedIn(who: String = session): WebTestClient.RequestHeadersSpec<*> =
        cookie(SessionSecurityContextRepository.COOKIE, who)
            .cookie(SessionSecurityContextRepository.CSRF_COOKIE, csrf)
            .header("X-XSRF-TOKEN", csrf)

    private fun endpoint(path: String = "browser-1") = server.url("/push/$path").toString()

    private fun subscribe(
        endpoint: String = endpoint(),
        p256dh: String = BROWSER_POINT,
        who: String = session
    ) = webTestClient
        .put()
        .uri("/me/push")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(PushSubscriptionRequest(endpoint, PushKeys(p256dh, BROWSER_AUTH)))
        .signedIn(who)
        .exchange()

    private fun bell(
        notify: String,
        slug: String = venue
    ) = webTestClient
        .put()
        .uri("/me/follows/venue/$slug/notify")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(NotifyRequest(notify))
        .signedIn()
        .exchange()

    private fun follow(slug: String = venue) =
        webTestClient
            .put()
            .uri("/me/follows/venue/$slug")
            .signedIn()
            .exchange()
            .expectStatus()
            .isNoContent

    private fun subscriptions(): Long =
        databaseClient
            .sql("SELECT count(*) AS n FROM accounts.push_subscription")
            .map { row -> row.get("n", Long::class.javaObjectType)!! }
            .one()
            .block()!!

    @Test
    fun `hands out the public key`() {
        webTestClient
            .get()
            .uri("/me/push")
            .signedIn()
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.publicKey")
            .isEqualTo(SERVER_POINT)
    }

    @Test
    fun `stores a subscription once, and forgets it on unsubscribe`() =
        runBlocking<Unit> {
            subscribe().expectStatus().isNoContent
            subscribe().expectStatus().isNoContent
            subscriptions() shouldBe 1

            webTestClient
                .post()
                .uri("/me/push/unsubscribe")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(PushUnsubscribeRequest(endpoint()))
                .signedIn()
                .exchange()
                .expectStatus()
                .isNoContent
            subscriptions() shouldBe 0
        }

    @Test
    fun `refuses an endpoint outside the push services, and a key that is not a point`() =
        runBlocking<Unit> {
            subscribe(endpoint = "https://attacker.example/collect").expectStatus().isBadRequest
            subscribe(p256dh = "AAAA").expectStatus().isBadRequest
            subscriptions() shouldBe 0
        }

    @Test
    fun `caps the browsers of one account`() {
        (1..10).forEach { subscribe(endpoint("browser-$it")).expectStatus().isNoContent }

        subscribe(endpoint("browser-11")).expectStatus().isEqualTo(409)
    }

    @Test
    fun `a signed-out visitor cannot subscribe`() {
        webTestClient
            .put()
            .uri("/me/push")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(PushSubscriptionRequest(endpoint(), PushKeys(BROWSER_POINT, BROWSER_AUTH)))
            .cookie(SessionSecurityContextRepository.CSRF_COOKIE, csrf)
            .header("X-XSRF-TOKEN", csrf)
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `the bell needs a follow, and takes only instant or off`() {
        bell("instant").expectStatus().isNotFound
        follow()

        bell("digest").expectStatus().isBadRequest
        bell("instant").expectStatus().isNoContent

        webTestClient
            .get()
            .uri("/me/follows")
            .signedIn()
            .exchange()
            .expectBody()
            .jsonPath("$[0].notify")
            .isEqualTo("instant")
    }

    @Test
    fun `rings every bell at once, and only the account's own`() {
        runBlocking { insertVenue("Other club", "other-club") }
        follow()
        follow("other-club")

        webTestClient
            .put()
            .uri("/me/follows/notify")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(NotifyRequest("instant"))
            .signedIn()
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.changed")
            .isEqualTo(2)

        webTestClient
            .get()
            .uri("/me/follows")
            .signedIn()
            .exchange()
            .expectBody()
            .jsonPath("$[*].notify")
            .isEqualTo(listOf("instant", "instant"))
    }

    @Test
    fun `a new event at a venue with the bell on is pushed once, in the account's language`() =
        runBlocking<Unit> {
            follow()
            bell("instant").expectStatus().isNoContent
            subscribe().expectStatus().isNoContent
            insertEvent(club(), "Moderat", "moderat", today.plusDays(10))
            server.enqueue(MockResponse.Builder().code(201).build())

            job.run()
            job.run()

            val request = server.takeRequest(5, TimeUnit.SECONDS).shouldNotBeNull()
            request.headers["Content-Encoding"] shouldBe "aes128gcm"
            request.headers["Authorization"].shouldNotBeNull() shouldStartWith "vapid t="
            request.headers["TTL"] shouldBe "86400"
            server.takeRequest(1, TimeUnit.SECONDS) shouldBe null
        }

    @Test
    fun `no push without the bell, for a past date, or for a cancelled event`() =
        runBlocking<Unit> {
            follow()
            subscribe().expectStatus().isNoContent
            insertEvent(club(), "Bell off", "bell-off", today.plusDays(3))
            job.run()
            bell("instant").expectStatus().isNoContent
            insertEvent(club(), "Yesterday", "yesterday", today.minusDays(1))
            val cancelled = insertEvent(club(), "Cancelled", "cancelled", today.plusDays(5))
            databaseClient.sql("UPDATE events.event SET status = 'CANCELLED' WHERE id = $cancelled").await()

            job.run()

            server.takeRequest(1, TimeUnit.SECONDS) shouldBe null
        }

    @Test
    @ExtendWith(OutputCaptureExtension::class)
    fun `an empty window logs one line, and a window claimed already logs none at info`(output: CapturedOutput) =
        runBlocking<Unit> {
            job.run()
            output.out shouldContain "New-dates push: no new dates from"

            databaseClient.sql("UPDATE accounts.push_cursor SET sent_until = now() + interval '1 hour'").await()
            val before = output.out.length
            job.run()
            output.out.substring(before) shouldNotContain "no new dates"
        }

    @Test
    fun `a subscription the push service calls gone is deleted`() =
        runBlocking<Unit> {
            follow()
            bell("instant").expectStatus().isNoContent
            subscribe().expectStatus().isNoContent
            insertEvent(club(), "Moderat", "moderat", today.plusDays(10))
            server.enqueue(MockResponse.Builder().code(410).build())

            job.run()

            server.takeRequest(5, TimeUnit.SECONDS).shouldNotBeNull()
            subscriptions() shouldBe 0
        }

    @Test
    fun `the export lists the push service, never the endpoint`() {
        subscribe().expectStatus().isNoContent

        val body =
            webTestClient
                .get()
                .uri("/me/export")
                .signedIn()
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!

        body.contains("browser-1") shouldBe false
        body.contains("\"pushService\":\"${server.hostName}\"") shouldBe true
    }

    @Test
    fun `deleting the account deletes its subscriptions`() =
        runBlocking<Unit> {
            subscribe().expectStatus().isNoContent
            databaseClient.sql("DELETE FROM accounts.account").await()

            subscriptions() shouldBe 0
        }

    private fun club(): Long =
        databaseClient
            .sql("SELECT id FROM events.venue WHERE slug = 'club'")
            .map { row -> row.get("id", Long::class.javaObjectType)!! }
            .one()
            .block()!!

    companion object {
        private val server = MockWebServer().apply { start() }

        /** RFC 8291's example: the application server's P-256 pair is ours, the user agent's point the browser's. */
        private const val SERVER_POINT = "BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8"
        private const val SERVER_SCALAR = "yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"
        private const val BROWSER_POINT = "BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4"
        private val BROWSER_AUTH = WebPushCrypto.base64Url(ByteArray(16) { it.toByte() })

        @JvmStatic
        @DynamicPropertySource
        fun push(registry: DynamicPropertyRegistry) {
            registry.add("app.push.vapid.private-key") { SERVER_SCALAR }
            registry.add("app.push.vapid.public-key") { SERVER_POINT }
            registry.add("app.push.allowed-origins") { "http://localhost,http://127.0.0.1" }
            registry.add("app.push.new-dates-lag") { "PT0S" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.close()
    }
}
