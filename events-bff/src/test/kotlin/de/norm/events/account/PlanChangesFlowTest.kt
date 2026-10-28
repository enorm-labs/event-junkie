package de.norm.events.account

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import de.norm.events.push.WebPushCrypto
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Changes to events an account marked (#4045): the job reads `event_change`, writes the bell's rows and pushes. */
class PlanChangesFlowTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    @Autowired
    private lateinit var job: PlanChangesJob

    private lateinit var session: String
    private lateinit var csrf: String
    private var club = 0L
    private val today: LocalDate = LocalDate.now(BERLIN)

    @BeforeEach
    fun signIn() =
        runBlocking<Unit> {
            databaseClient
                .sql("TRUNCATE TABLE accounts.notification, accounts.rsvp, accounts.push_subscription, accounts.session, accounts.account CASCADE")
                .await()
            databaseClient.sql("UPDATE accounts.push_cursor SET changes_until = now() - interval '1 hour'").await()
            generateSequence { server.takeRequest(0, TimeUnit.SECONDS) }.count() // Drains what an earlier test left.
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
            club = insertVenue("Club", "club")
        }

    private suspend fun sessionFor(email: String): String {
        val now = Instant.now()
        val account = accounts.signIn(email, "en", now)
        val token = SecretTokens.next()
        sessionRows.createSession(SecretTokens.hash(token), account.id, now, now.plus(Duration.ofDays(1)))
        return token
    }

    private fun WebTestClient.RequestHeadersSpec<*>.signedIn(): WebTestClient.RequestHeadersSpec<*> =
        cookie(SessionSecurityContextRepository.COOKIE, session)
            .cookie(SessionSecurityContextRepository.CSRF_COOKIE, csrf)
            .header("X-XSRF-TOKEN", csrf)

    private fun subscribe() =
        webTestClient
            .put()
            .uri("/me/push")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(PushSubscriptionRequest(server.url("/push/browser-1").toString(), PushKeys(BROWSER_POINT, BROWSER_AUTH)))
            .signedIn()
            .exchange()
            .expectStatus()
            .isNoContent

    private fun going(slug: String) =
        webTestClient
            .put()
            .uri("/me/rsvps/$slug")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(RsvpRequest("going"))
            .signedIn()
            .exchange()
            .expectStatus()
            .isNoContent

    private fun switch(enabled: Boolean) =
        webTestClient
            .put()
            .uri("/me/push/changes")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ChangePushRequest(enabled))
            .signedIn()
            .exchange()
            .expectStatus()
            .isNoContent

    /** A change the importer logged a minute ago, inside the window. */
    private suspend fun changed(
        event: Long,
        field: String,
        from: String,
        to: String
    ) {
        databaseClient
            .sql(
                "INSERT INTO events.event_change (event_id, field, old_value, new_value, seen_at) " +
                    "VALUES ($event, '$field', '$from', '$to', now() - interval '1 minute')"
            ).await()
    }

    private fun list() =
        webTestClient
            .get()
            .uri("/me/notifications")
            .signedIn()
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun pushes(): Int = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.count()

    private fun accepted() = repeat(2) { server.enqueue(MockResponse.Builder().code(201).build()) }

    @Test
    fun `a cancellation is one notification and one push, once`() =
        runBlocking<Unit> {
            val event = insertEvent(club, "Moderat", "moderat", today.plusDays(7))
            going("moderat")
            subscribe()
            changed(event, "STATUS", "SCHEDULED", "CANCELLED")
            accepted()

            job.run()
            job.run()

            pushes() shouldBe 1
            list()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].kind")
                .isEqualTo("change")
                .jsonPath("$[0].change")
                .isEqualTo("cancelled")
                .jsonPath("$[0].aboutKind")
                .isEqualTo("rsvp")
                .jsonPath("$[0].event")
                .isEqualTo("moderat")
        }

    @Test
    fun `several changes in one window are one push, named by the most severe`() =
        runBlocking<Unit> {
            val event = insertEvent(club, "Moderat", "moderat", today.plusDays(7))
            going("moderat")
            subscribe()
            changed(event, "START_TIME", "20:00:00", "21:00:00")
            changed(event, "STATUS", "SCHEDULED", "RELOCATED")
            accepted()

            job.run()

            pushes() shouldBe 1
            list().jsonPath("$[0].change").isEqualTo("relocated")
        }

    @Test
    fun `a second change the same day sends nothing and keeps the more severe one`() =
        runBlocking<Unit> {
            val event = insertEvent(club, "Moderat", "moderat", today.plusDays(7))
            going("moderat")
            subscribe()
            changed(event, "STATUS", "SCHEDULED", "CANCELLED")
            accepted()
            job.run()
            pushes() shouldBe 1

            databaseClient.sql("UPDATE accounts.push_cursor SET changes_until = now() - interval '30 seconds'").await()
            databaseClient
                .sql(
                    "INSERT INTO events.event_change (event_id, field, old_value, new_value, seen_at) " +
                        "VALUES ($event, 'START_TIME', '20:00:00', '21:00:00', now() - interval '10 seconds')"
                ).await()
            job.run()

            pushes() shouldBe 0
            list().jsonPath("$[0].change").isEqualTo("cancelled")
        }

    @Test
    fun `a change on a later day starts the row over, unread, and pushes again`() =
        runBlocking<Unit> {
            val event = insertEvent(club, "Moderat", "moderat", today.plusDays(7))
            going("moderat")
            subscribe()
            changed(event, "EVENT_DATE", today.plusDays(6).toString(), today.plusDays(7).toString())
            accepted()
            job.run()
            pushes() shouldBe 1
            databaseClient.sql("UPDATE accounts.notification SET created_at = now() - interval '2 days', read_at = now() - interval '1 day'").await()

            databaseClient.sql("UPDATE accounts.push_cursor SET changes_until = now() - interval '30 seconds'").await()
            databaseClient
                .sql(
                    "INSERT INTO events.event_change (event_id, field, old_value, new_value, seen_at) " +
                        "VALUES ($event, 'VENUE', '1', '$club', now() - interval '10 seconds')"
                ).await()
            accepted()
            job.run()

            pushes() shouldBe 1
            list()
                .jsonPath("$.length()")
                .isEqualTo(1)
                .jsonPath("$[0].change")
                .isEqualTo("venue")
                .jsonPath("$[0].readAt")
                .doesNotExist()
        }

    @Test
    fun `a changed end, a status back to scheduled, a past event and an unmarked event stay silent`() =
        runBlocking<Unit> {
            val marked = insertEvent(club, "Moderat", "moderat", today.plusDays(7))
            val past = insertEvent(club, "Yesterday", "yesterday", today.minusDays(1))
            val other = insertEvent(club, "Other", "other", today.plusDays(3))
            going("moderat")
            going("yesterday")
            subscribe()
            changed(marked, "END_TIME", "04:00:00", "06:00:00")
            changed(marked, "STATUS", "CANCELLED", "SCHEDULED")
            changed(past, "STATUS", "SCHEDULED", "CANCELLED")
            changed(other, "STATUS", "SCHEDULED", "CANCELLED")

            job.run()

            pushes() shouldBe 0
            list().jsonPath("$.length()").isEqualTo(0)
        }

    @Test
    fun `the switch off keeps the notification and sends no push`() =
        runBlocking<Unit> {
            val event = insertEvent(club, "Moderat", "moderat", today.plusDays(7))
            going("moderat")
            subscribe()
            switch(false)
            webTestClient
                .get()
                .uri("/me")
                .signedIn()
                .exchange()
                .expectBody()
                .jsonPath("$.changePush")
                .isEqualTo(false)
            changed(event, "STATUS", "SCHEDULED", "POSTPONED")

            job.run()

            pushes() shouldBe 0
            list().jsonPath("$[0].change").isEqualTo("postponed")
        }

    @Test
    fun `a window claimed already writes nothing`() =
        runBlocking<Unit> {
            val event = insertEvent(club, "Moderat", "moderat", today.plusDays(7))
            going("moderat")
            changed(event, "STATUS", "SCHEDULED", "CANCELLED")
            databaseClient.sql("UPDATE accounts.push_cursor SET changes_until = now() + interval '1 hour'").await()

            job.run()

            list().jsonPath("$.length()").isEqualTo(0)
        }

    companion object {
        private val server = MockWebServer().apply { start() }

        /** RFC 8291's example keys, as in [PushFlowTest]. */
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
            registry.add("app.push.changes-lag") { "PT0S" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.close()
    }
}
