package de.norm.events.account

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import de.norm.events.push.PushSender
import de.norm.events.push.WebPushCrypto
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.await
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit

/**
 * The day reminder (#4253): from 10:00 Berlin one replica claims the day and pushes each account's events of that day.
 * The job runs on a fixed clock, so the boundary is exact. 15 January is winter time (UTC+1), 28 March 2027 is the
 * day the clocks go forward (UTC+2 from 03:00).
 */
class RemindersFlowTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    @Autowired
    private lateinit var store: ReminderStore

    @Autowired
    private lateinit var subscriptions: PushSubscriptionStore

    @Autowired
    private lateinit var sender: ObjectProvider<PushSender>

    private lateinit var session: String
    private lateinit var csrf: String
    private var club = 0L

    @BeforeEach
    fun signIn() =
        runBlocking<Unit> {
            databaseClient
                .sql("TRUNCATE TABLE accounts.notification, accounts.rsvp, accounts.push_subscription, accounts.session, accounts.account CASCADE")
                .await()
            databaseClient.sql("UPDATE accounts.push_cursor SET reminders_until = '$WINTER'::date - 1").await()
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
            club = insertVenue("Columbiahalle", "columbiahalle")
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

    private fun mark(
        slug: String,
        status: String = "going"
    ) = webTestClient
        .put()
        .uri("/me/rsvps/$slug")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(RsvpRequest(status))
        .signedIn()
        .exchange()
        .expectStatus()
        .isNoContent

    private fun switch(enabled: Boolean) =
        webTestClient
            .put()
            .uri("/me/push/reminders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(ChangePushRequest(enabled))
            .signedIn()
            .exchange()
            .expectStatus()
            .isNoContent

    /** The job as a replica runs it, at [at] Berlin wall time on [day]. */
    private fun jobAt(
        day: LocalDate,
        at: LocalTime
    ) = RemindersJob(store, subscriptions, sender, Clock.fixed(day.atTime(at).atZone(BERLIN).toInstant(), ZoneOffset.UTC))

    private suspend fun status(
        event: Long,
        status: String,
        relocatedTo: String? = null
    ) {
        databaseClient
            .sql("UPDATE events.event SET status = '$status', relocated_to = ${relocatedTo?.let { "'$it'" } ?: "NULL"} WHERE id = $event")
            .await()
    }

    private fun pushes(): Int = generateSequence { server.takeRequest(1, TimeUnit.SECONDS) }.count()

    private fun accepted() = repeat(2) { server.enqueue(MockResponse.Builder().code(201).build()) }

    @Test
    fun `nothing before 10 o'clock, one push at 10, and nothing from a second replica`() =
        runBlocking<Unit> {
            insertEvent(club, "Moderat", "moderat", WINTER)
            mark("moderat")
            subscribe()
            accepted()

            jobAt(WINTER, LocalTime.of(9, 59, 59)).run()
            pushes() shouldBe 0

            jobAt(WINTER, LocalTime.of(10, 0)).run()
            jobAt(WINTER, LocalTime.of(10, 5)).run()
            pushes() shouldBe 1
        }

    @Test
    fun `on the day the clocks go forward, 10 o'clock is Berlin's, not UTC's`() =
        runBlocking<Unit> {
            databaseClient.sql("UPDATE accounts.push_cursor SET reminders_until = '$SPRING'::date - 1").await()
            insertEvent(club, "Moderat", "moderat", SPRING)
            mark("moderat")
            subscribe()
            accepted()

            // Summer time from 03:00: 09:59 Berlin is 07:59 UTC, and 10:00 Berlin is 08:00 UTC.
            jobAt(SPRING, LocalTime.of(9, 59)).run()
            pushes() shouldBe 0
            RemindersJob(store, subscriptions, sender, Clock.fixed(Instant.parse("${SPRING}T08:00:00Z"), ZoneOffset.UTC)).run()
            pushes() shouldBe 1
        }

    @Test
    fun `going and interested are one push per account, going first`() =
        runBlocking<Unit> {
            insertEvent(club, "Early", "early", WINTER, startTime = LocalTime.of(18, 0))
            insertEvent(club, "Moderat", "moderat", WINTER, startTime = LocalTime.of(21, 0))
            mark("early", "interested")
            mark("moderat", "going")
            subscribe()
            accepted()

            store.due(WINTER).map { it.slug } shouldBe listOf("moderat", "early")
            jobAt(WINTER, LocalTime.of(10, 0)).run()

            pushes() shouldBe 1
        }

    @Test
    fun `a cancelled and a postponed event stay silent, a relocated one names where it moved`() =
        runBlocking<Unit> {
            val cancelled = insertEvent(club, "Cancelled", "cancelled", WINTER)
            val postponed = insertEvent(club, "Postponed", "postponed", WINTER)
            val relocated = insertEvent(club, "Relocated", "relocated", WINTER)
            insertEvent(club, "Postponed", "postponed-new", WINTER.plusDays(14))
            listOf("cancelled", "postponed", "relocated", "postponed-new").forEach { mark(it) }
            status(cancelled, "CANCELLED")
            status(postponed, "POSTPONED")
            status(relocated, "RELOCATED", "Astra")
            subscribe()

            store.due(WINTER).map { it.slug to it.place } shouldBe listOf("relocated" to "Astra")
            store.due(WINTER.plusDays(14)).map { it.slug } shouldBe listOf("postponed-new")
        }

    @Test
    fun `an event over several days counts on its first day only`() =
        runBlocking<Unit> {
            insertEvent(club, "Festival", "festival", WINTER, endDate = WINTER.plusDays(2))
            mark("festival")
            subscribe()

            store.due(WINTER).map { it.slug } shouldBe listOf("festival")
            store.due(WINTER.plusDays(1)).shouldBeEmpty()
        }

    @Test
    fun `the switch off sends nothing, and the account and its export say so`() =
        runBlocking<Unit> {
            insertEvent(club, "Moderat", "moderat", WINTER)
            mark("moderat")
            subscribe()
            switch(false)
            listOf("/me", "/me/export").forEach {
                webTestClient
                    .get()
                    .uri(it)
                    .signedIn()
                    .exchange()
                    .expectBody()
                    .jsonPath("$.reminderPush")
                    .isEqualTo(false)
            }

            jobAt(WINTER, LocalTime.of(10, 0)).run()

            pushes() shouldBe 0
        }

    @Test
    fun `an account with no browser subscribed is not due`() =
        runBlocking<Unit> {
            insertEvent(club, "Moderat", "moderat", WINTER)
            mark("moderat")

            store.due(WINTER).shouldBeEmpty()
        }

    @Test
    fun `a day another replica claimed already sends nothing`() =
        runBlocking<Unit> {
            insertEvent(club, "Moderat", "moderat", WINTER)
            mark("moderat")
            subscribe()
            databaseClient.sql("UPDATE accounts.push_cursor SET reminders_until = '$WINTER'").await()

            jobAt(WINTER, LocalTime.of(10, 0)).run()

            pushes() shouldBe 0
        }

    companion object {
        private val server = MockWebServer().apply { start() }
        private val WINTER: LocalDate = LocalDate.of(2027, 1, 15)
        private val SPRING: LocalDate = LocalDate.of(2027, 3, 28)

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
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.close()
    }
}
