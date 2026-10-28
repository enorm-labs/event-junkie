package de.norm.events.account

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Passkeys end to end (#3888): register on the account page, sign in, rename, delete, against a software authenticator. */
class PasskeyFlowTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    private val mapper = JsonMapper.builder().build()
    private lateinit var session: String
    private lateinit var csrf: String

    /** The OpenAPI example ZAP sends (#4105), an empty object, and fields of the wrong type. */
    private val malformedCredentials =
        listOf(
            mapOf("additionalProp1" to emptyMap<String, String>()),
            emptyMap(),
            mapOf("id" to 1, "rawId" to "x", "type" to "public-key", "response" to "x"),
            mapOf("id" to "AAAA", "rawId" to "AAAA", "type" to "public-key", "response" to mapOf("clientDataJSON" to "not base64!"))
        )

    /** A client address of its own per test: the per-client limit lives in the bean, across tests. */
    private val client = "203.0.113.${(1..254).random()}-${UUID.randomUUID()}"

    @BeforeEach
    fun signIn() =
        runBlocking<Unit> {
            databaseClient.sql("TRUNCATE TABLE accounts.passkey_challenge, accounts.passkey, accounts.session, accounts.account CASCADE").await()
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

    private fun WebTestClient.RequestHeadersSpec<*>.withCsrf(signedIn: Boolean = true): WebTestClient.RequestHeadersSpec<*> =
        cookie(SessionSecurityContextRepository.CSRF_COOKIE, csrf)
            .header("X-XSRF-TOKEN", csrf)
            .header("X-Forwarded-For", client)
            .apply { if (signedIn) cookie(SessionSecurityContextRepository.COOKIE, session) }

    private fun post(
        path: String,
        body: Any = emptyMap<String, String>(),
        signedIn: Boolean = true
    ) = webTestClient
        .post()
        .uri(path)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(body)
        .withCsrf(signedIn)
        .exchange()

    private fun options(
        path: String,
        signedIn: Boolean = true
    ): JsonNode =
        mapper.readTree(
            post(path, signedIn = signedIn)
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        )

    private fun register(
        device: SoftwarePasskey,
        name: String? = "Phone"
    ) = post("/me/passkeys", mapOf("name" to name, "credential" to mapper.readTree(device.create(options("/me/passkeys/options")))))

    private fun signInWith(
        device: SoftwarePasskey,
        answer: String = device.get(options("/auth/passkey/options", signedIn = false))
    ) = post("/auth/passkey/sign-in", mapOf("credential" to mapper.readTree(answer)), signedIn = false)

    private fun list(): JsonNode =
        mapper.readTree(
            webTestClient
                .get()
                .uri("/me/passkeys")
                .withCsrf()
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(String::class.java)
                .returnResult()
                .responseBody!!
        )

    @Test
    fun `a registered passkey signs in and starts a session`() {
        val device = SoftwarePasskey()
        register(device).expectStatus().isNoContent

        val cookie =
            signInWith(device)
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.email")
                .isEqualTo("fan@example.org")
                .returnResult()
                .responseCookies
                .getFirst(SessionSecurityContextRepository.COOKIE)
        cookie.shouldNotBeNull()
        webTestClient
            .get()
            .uri("/auth/session")
            .cookie(SessionSecurityContextRepository.COOKIE, cookie.value)
            .exchange()
            .expectBody()
            .jsonPath("$.account.email")
            .isEqualTo("fan@example.org")
        list()[0]["lastUsedAt"].isNull shouldBe false
    }

    @Test
    fun `the options name the cluster's origin, require user verification, and leave out the account id`() {
        val created = options("/me/passkeys/options")
        created["rp"]["id"].asString() shouldBe "event-junkie.de"
        created["authenticatorSelection"]["userVerification"].asString() shouldBe "required"
        created["authenticatorSelection"]["residentKey"].asString() shouldBe "required"
        created["attestation"].asString() shouldBe "none"
        val accountId = runBlocking { accounts.signIn("fan@example.org", "en", Instant.now()).id }
        created["user"]["id"].asString().contains(accountId.toString()) shouldBe false

        options("/auth/passkey/options", signedIn = false)["userVerification"].asString() shouldBe "required"
    }

    @Test
    fun `the same user handle serves every passkey of the account`() {
        options("/me/passkeys/options")["user"]["id"].asString() shouldBe options("/me/passkeys/options")["user"]["id"].asString()
    }

    @Test
    fun `a sign-in answer works once`() {
        val device = SoftwarePasskey()
        register(device).expectStatus().isNoContent
        val answer = device.get(options("/auth/passkey/options", signedIn = false))

        signInWith(device, answer).expectStatus().isOk
        signInWith(device, answer).expectStatus().isUnauthorized
    }

    @Test
    fun `a deleted passkey can no longer sign in`() {
        val device = SoftwarePasskey()
        register(device).expectStatus().isNoContent
        val id = list()[0]["id"].asString()

        webTestClient
            .delete()
            .uri("/me/passkeys/$id")
            .withCsrf()
            .exchange()
            .expectStatus()
            .isNoContent
        list().size() shouldBe 0
        signInWith(device).expectStatus().isUnauthorized
    }

    @Test
    fun `an answer that is not a credential is a refusal, not a server error`() {
        malformedCredentials.forEach { credential ->
            post("/auth/passkey/sign-in", mapOf("credential" to credential), signedIn = false).expectStatus().isUnauthorized
            post("/me/passkeys", mapOf("credential" to credential)).expectStatus().isBadRequest
        }
        list().size() shouldBe 0
    }

    @Test
    fun `a passkey made for another origin is refused`() {
        post(
            "/me/passkeys",
            mapOf("credential" to mapper.readTree(SoftwarePasskey("https://event-junkie.example").create(options("/me/passkeys/options"))))
        ).expectStatus().isBadRequest
        list().size() shouldBe 0
    }

    @Test
    fun `a sign-in challenge cannot register a passkey`() {
        val created = options("/me/passkeys/options") as ObjectNode
        created.put("challenge", options("/auth/passkey/options", signedIn = false)["challenge"].asString())
        post("/me/passkeys", mapOf("credential" to mapper.readTree(SoftwarePasskey().create(created)))).expectStatus().isBadRequest
    }

    @Test
    fun `an account holds at most ten passkeys`() {
        repeat(Passkeys.MAX_PASSKEYS.toInt()) { register(SoftwarePasskey()).expectStatus().isNoContent }
        register(SoftwarePasskey()).expectStatus().isEqualTo(409)
        list().size() shouldBe Passkeys.MAX_PASSKEYS.toInt()
    }

    @Test
    fun `a passkey is renamed, a blank name falls back, and the export lists it without the key`() {
        register(SoftwarePasskey(), name = "  ").expectStatus().isNoContent
        list()[0]["name"].asString() shouldBe "Passkey"
        val id = list()[0]["id"].asString()

        webTestClient
            .patch()
            .uri("/me/passkeys/$id")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(PasskeyRename("Laptop"))
            .withCsrf()
            .exchange()
            .expectStatus()
            .isNoContent
        val export =
            mapper.readTree(
                webTestClient
                    .get()
                    .uri("/me/export")
                    .withCsrf()
                    .exchange()
                    .expectBody(String::class.java)
                    .returnResult()
                    .responseBody!!
            )
        export["passkeys"].toList() shouldHaveSize 1
        export["passkeys"][0]["name"].asString() shouldBe "Laptop"
        export["passkeys"][0].has("credential") shouldBe false
    }

    @Test
    fun `the stored credential carries no authenticator model`() =
        runBlocking<Unit> {
            register(SoftwarePasskey()).expectStatus().isNoContent
            val stored =
                databaseClient
                    .sql("SELECT credential FROM accounts.passkey")
                    .map { row -> row.get("credential", ByteArray::class.java)!! }
                    .one()
                    .block()!!
            stored.copyOfRange(0, AAGUID_BYTES).all { it == 0.toByte() } shouldBe true
        }

    @Test
    fun `without a session the account's passkey routes are a 401`() {
        post("/me/passkeys/options", signedIn = false).expectStatus().isUnauthorized
    }

    private companion object {
        const val AAGUID_BYTES = 16
    }
}
