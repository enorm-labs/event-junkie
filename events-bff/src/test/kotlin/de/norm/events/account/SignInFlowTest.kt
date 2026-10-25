package de.norm.events.account

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.util.UUID

/** Sign-in by e-mail link end to end (#3887, ADR-053), with the mail caught by GreenMail. */
class SignInFlowTest : BaseControllerTest() {
    @BeforeEach
    fun clearAccounts() =
        runBlocking {
            greenMail.purgeEmailFromAllMailboxes()
            databaseClient.sql("TRUNCATE TABLE accounts.session, accounts.one_time_token, accounts.account CASCADE").await()
        }

    /** A client address of its own per test: the per-client limit lives in the bean, across tests. */
    private val client = "203.0.113.${(1..254).random()}, 10.42.0.${(1..254).random()}-${UUID.randomUUID()}"

    private fun csrf(): String =
        webTestClient
            .get()
            .uri("/auth/csrf")
            .exchange()
            .expectStatus()
            .isNoContent
            .returnResult(String::class.java)
            .responseCookies
            .getFirst(SessionSecurityContextRepository.CSRF_COOKIE)!!
            .value

    private fun post(
        path: String,
        body: Any,
        csrf: String,
        session: String? = null
    ): WebTestClient.ResponseSpec =
        webTestClient
            .post()
            .uri(path)
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-XSRF-TOKEN", csrf)
            .header("X-Forwarded-For", client)
            .cookie(SessionSecurityContextRepository.CSRF_COOKIE, csrf)
            .apply { if (session != null) cookie(SessionSecurityContextRepository.COOKIE, session) }
            .bodyValue(body)
            .exchange()

    private fun get(
        path: String,
        session: String?
    ): WebTestClient.ResponseSpec =
        webTestClient
            .get()
            .uri(path)
            .apply { if (session != null) cookie(SessionSecurityContextRepository.COOKIE, session) }
            .exchange()

    private fun requestLink(
        email: String,
        csrf: String,
        locale: String = "en"
    ) = post("/auth/link", LinkRequest(email, locale), csrf)

    private fun count(sql: String): Long = runBlocking { databaseClient.sql(sql).map { row -> row.get("n", Long::class.javaObjectType)!! }.awaitSingle() }

    private fun text(sql: String): String = runBlocking { databaseClient.sql(sql).map { row -> row.get(0, String::class.java)!! }.awaitSingle() }

    private fun tokenFromLastMail(): String {
        val body = GreenMailUtil.getBody(greenMail.receivedMessages.last())
        return Regex("#token=([A-Za-z0-9_-]+)").find(body)!!.groupValues[1]
    }

    /** Signs [email] in and answers the session cookie's value. */
    private fun signIn(
        email: String,
        csrf: String
    ): String {
        requestLink(email, csrf).expectStatus().isAccepted
        return post("/auth/sign-in", SignInRequest(tokenFromLastMail()), csrf)
            .expectStatus()
            .isOk
            .returnResult(String::class.java)
            .responseCookies
            .getFirst(SessionSecurityContextRepository.COOKIE)!!
            .value
    }

    @Test
    fun `a link signs in, creates the account once, and the session reaches me`() {
        val csrf = csrf()
        val session = signIn("Visitor@Example.org", csrf)

        get("/me", session)
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.email")
            .isEqualTo("Visitor@Example.org")
        signIn("visitor@example.org", csrf)
        count("SELECT count(*) AS n FROM accounts.account") shouldBe 1L
    }

    @Test
    fun `the mail speaks the requested language and points at the configured origin with the token in the fragment`() {
        requestLink("leser@example.org", csrf(), locale = "de").expectStatus().isAccepted

        val mail = greenMail.receivedMessages.single()
        mail.subject shouldBe "Dein Anmeldelink für Event Junkie"
        GreenMailUtil.getBody(mail) shouldContain "https://test.event-junkie.example/de/sign-in/confirm#token="
    }

    @Test
    fun `the link returns to the page that asked, and only to a page of this site`() {
        val csrf = csrf()
        post("/auth/link", LinkRequest("back@example.org", "en", next = "/en/venues/sisyphos"), csrf).expectStatus().isAccepted
        post("/auth/link", LinkRequest("away@example.org", "en", next = "//evil.example/en"), csrf).expectStatus().isAccepted

        val (back, away) = greenMail.receivedMessages.map { GreenMailUtil.getBody(it) }
        back shouldContain "/en/sign-in/confirm?next=/en/venues/sisyphos#token="
        away shouldContain "/en/sign-in/confirm#token="
    }

    @Test
    fun `a link works once`() {
        val csrf = csrf()
        requestLink("once@example.org", csrf).expectStatus().isAccepted
        val token = tokenFromLastMail()

        post("/auth/sign-in", SignInRequest(token), csrf).expectStatus().isOk
        post("/auth/sign-in", SignInRequest(token), csrf).expectStatus().isUnauthorized
    }

    @Test
    fun `an expired link signs nobody in`() {
        val csrf = csrf()
        requestLink("late@example.org", csrf).expectStatus().isAccepted
        runBlocking { databaseClient.sql("UPDATE accounts.one_time_token SET expires_at = now() - interval '1 minute'").await() }

        post("/auth/sign-in", SignInRequest(tokenFromLastMail()), csrf).expectStatus().isUnauthorized
    }

    @Test
    fun `the database keeps only a hash of the token`() {
        requestLink("hash@example.org", csrf()).expectStatus().isAccepted
        val token = tokenFromLastMail()

        val stored = text("SELECT token_hash FROM accounts.one_time_token")
        stored shouldNotBe token
        stored shouldBe SecretTokens.hash(token)
    }

    @Test
    fun `an address that asks too often is answered alike but gets no fourth mail`() {
        val csrf = csrf()
        repeat(4) { requestLink("eager@example.org", csrf).expectStatus().isAccepted }

        greenMail.receivedMessages shouldHaveSize 3
    }

    @Test
    fun `a client that asks too often gets a 429`() {
        val csrf = csrf()
        repeat(20) { i -> requestLink("many-$i@example.org", csrf).expectStatus().isAccepted }

        requestLink("one-more@example.org", csrf).expectStatus().isEqualTo(429)
    }

    @Test
    fun `a malformed address or locale is a 400`() {
        val csrf = csrf()
        requestLink("not an address", csrf).expectStatus().isBadRequest
        requestLink("ok@example.org", csrf, locale = "fr").expectStatus().isBadRequest
    }

    @Test
    fun `a POST without the CSRF header is refused`() {
        webTestClient
            .post()
            .uri("/auth/link")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(LinkRequest("csrf@example.org", "en"))
            .exchange()
            .expectStatus()
            .isForbidden
        greenMail.receivedMessages shouldHaveSize 0
    }

    @Test
    fun `without a session me is a 401 and the session check is an empty 200`() {
        get("/me", null).expectStatus().isUnauthorized
        get("/auth/session", null)
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.account")
            .isEmpty
    }

    @Test
    fun `signing out ends this session and clears both cookies`() {
        val csrf = csrf()
        val session = signIn("leave@example.org", csrf)

        val cookies =
            post("/me/sign-out", Unit, csrf, session)
                .expectStatus()
                .isNoContent
                .returnResult(String::class.java)
                .responseCookies
        cookies.getFirst(SessionSecurityContextRepository.COOKIE)!!.maxAge.isZero shouldBe true
        cookies.getFirst(SessionSecurityContextRepository.CSRF_COOKIE)!!.maxAge.isZero shouldBe true
        get("/me", session).expectStatus().isUnauthorized
    }

    @Test
    fun `signing out everywhere ends the other device's session too`() {
        val csrf = csrf()
        val laptop = signIn("two@example.org", csrf)
        val phone = signIn("two@example.org", csrf)

        post("/me/sign-out-everywhere", Unit, csrf, phone).expectStatus().isNoContent
        get("/me", laptop).expectStatus().isUnauthorized
    }

    @Test
    fun `the export carries the account and its sessions, as a download`() {
        val csrf = csrf()
        val session = signIn("export@example.org", csrf)

        get("/me/export", session)
            .expectStatus()
            .isOk
            .expectHeader()
            .valueMatches(HttpHeaders.CONTENT_DISPOSITION, "attachment.*")
            .expectBody()
            .jsonPath("$.email")
            .isEqualTo("export@example.org")
            .jsonPath("$.sessions.length()")
            .isEqualTo(1)
    }

    @Test
    fun `deleting the account removes it, its sessions and its links`() {
        val csrf = csrf()
        val session = signIn("gone@example.org", csrf)

        webTestClient
            .delete()
            .uri("/me")
            .header("X-XSRF-TOKEN", csrf)
            .cookie(SessionSecurityContextRepository.CSRF_COOKIE, csrf)
            .cookie(SessionSecurityContextRepository.COOKIE, session)
            .exchange()
            .expectStatus()
            .isNoContent
        get("/me", session).expectStatus().isUnauthorized
        count("SELECT (SELECT count(*) FROM accounts.account) + (SELECT count(*) FROM accounts.one_time_token) AS n") shouldBe 0L
    }

    @Test
    fun `account answers are never cached, and the public API sets no cookie`() {
        val csrf = csrf()
        val session = signIn("cache@example.org", csrf)

        get("/me", session).expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
        get("/auth/session", null).expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "private, no-store")
        get("/festivals/current", session)
            .expectStatus()
            .isOk
            .expectHeader()
            .doesNotExist(HttpHeaders.SET_COOKIE)
            .expectHeader()
            .value(HttpHeaders.CACHE_CONTROL) { it shouldStartWith "max-age=" }
    }

    companion object {
        private val greenMail = GreenMail(ServerSetupTest.SMTP.dynamicPort()).apply { start() }

        @JvmStatic
        @DynamicPropertySource
        fun mail(registry: DynamicPropertyRegistry) {
            registry.add("spring.mail.host") { "localhost" }
            registry.add("spring.mail.port") { greenMail.smtp.port }
            registry.add("spring.mail.properties.mail.smtp.auth") { false }
            registry.add("spring.mail.properties.mail.smtp.starttls.enable") { false }
            registry.add("spring.mail.properties.mail.smtp.starttls.required") { false }
            registry.add("app.site.origin") { "https://test.event-junkie.example" }
        }

        @JvmStatic
        @AfterAll
        fun stopMail() = greenMail.stop()
    }
}
