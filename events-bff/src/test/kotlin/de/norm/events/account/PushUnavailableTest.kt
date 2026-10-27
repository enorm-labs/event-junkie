package de.norm.events.account

import de.norm.events.BaseControllerTest
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import java.time.Duration
import java.time.Instant

/** A cluster without the push Secret (ADR-055): the BFF starts, says so, and stores nothing. */
class PushUnavailableTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    private lateinit var session: String
    private lateinit var csrf: String

    @BeforeEach
    fun signIn() =
        runBlocking<Unit> {
            databaseClient.sql("TRUNCATE TABLE accounts.push_subscription, accounts.session, accounts.account CASCADE").await()
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

    @Test
    fun `there is no public key, and a subscription is refused`() {
        webTestClient
            .get()
            .uri("/me/push")
            .cookie(SessionSecurityContextRepository.COOKIE, session)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.publicKey")
            .doesNotExist()

        webTestClient
            .put()
            .uri("/me/push")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(PushSubscriptionRequest("https://fcm.googleapis.com/fcm/send/x", PushKeys("k", "a")))
            .cookie(SessionSecurityContextRepository.COOKIE, session)
            .cookie(SessionSecurityContextRepository.CSRF_COOKIE, csrf)
            .header("X-XSRF-TOKEN", csrf)
            .exchange()
            .expectStatus()
            .isEqualTo(503)
    }
}
