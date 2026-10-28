package de.norm.events.account

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * The header's bell end to end (#4078): the new-dates job writes the rows without any push configured, and the routes
 * read and mark only the account's own. The test context has no VAPID keys, so no push sender.
 */
class NotificationFlowTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    @Autowired
    private lateinit var job: NewDatesJob

    @Autowired
    private lateinit var cleanup: InactiveAccountCleanup

    private lateinit var session: String
    private lateinit var csrf: String
    private var club = 0L
    private val today: LocalDate = LocalDate.now(BERLIN)

    @BeforeEach
    fun signIn() =
        runBlocking<Unit> {
            databaseClient.sql("TRUNCATE TABLE accounts.notification, accounts.follow, accounts.session, accounts.account CASCADE").await()
            databaseClient.sql("UPDATE accounts.push_cursor SET sent_until = now() - interval '1 hour'").await()
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

    private fun WebTestClient.RequestHeadersSpec<*>.signedIn(who: String = session): WebTestClient.RequestHeadersSpec<*> =
        cookie(SessionSecurityContextRepository.COOKIE, who)
            .cookie(SessionSecurityContextRepository.CSRF_COOKIE, csrf)
            .header("X-XSRF-TOKEN", csrf)

    private fun follow(
        kind: String,
        slug: String,
        who: String = session
    ) {
        webTestClient
            .put()
            .uri("/me/follows/$kind/$slug")
            .signedIn(who)
            .exchange()
            .expectStatus()
            .isNoContent
    }

    /** An event stored 20 minutes ago, so it falls inside the window past the job's lag. */
    private suspend fun newEvent(
        title: String,
        slug: String,
        date: LocalDate = today.plusDays(7)
    ): Long =
        insertEvent(club, title, slug, date).also { id ->
            databaseClient.sql("UPDATE events.event SET created_at = now() - interval '20 minutes' WHERE id = $id").await()
        }

    private fun list(who: String = session) =
        webTestClient
            .get()
            .uri("/me/notifications")
            .signedIn(who)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()

    private fun unread(who: String = session): Long =
        webTestClient
            .get()
            .uri("/me/notifications/unread-count")
            .signedIn(who)
            .exchange()
            .expectStatus()
            .isOk
            .expectBody(UnreadCountResponse::class.java)
            .returnResult()
            .responseBody!!
            .count

    private fun rows(): Long =
        databaseClient
            .sql("SELECT count(*) AS n FROM accounts.notification")
            .map { row -> row.get("n", Long::class.javaObjectType)!! }
            .one()
            .block()!!

    private fun firstId(): String =
        databaseClient
            .sql("SELECT id::text AS id FROM accounts.notification ORDER BY created_at LIMIT 1")
            .map { row -> row.get("id", String::class.java)!! }
            .one()
            .block()!!

    @Test
    fun `a new date at a followed venue is a notification without the bell and without push, once`() =
        runBlocking<Unit> {
            follow("venue", "club")
            newEvent("Moderat", "moderat")

            job.run()
            job.run()

            rows() shouldBe 1
            unread() shouldBe 1
            list()
                .jsonPath("$[0].event")
                .isEqualTo("moderat")
                .jsonPath("$[0].kind")
                .isEqualTo("new_date")
                .jsonPath("$[0].aboutKind")
                .isEqualTo("venue")
                .jsonPath("$[0].aboutName")
                .isEqualTo("Club")
                .jsonPath("$[0].readAt")
                .doesNotExist()
        }

    @Test
    fun `two matching follows make one row, named by the artist`() =
        runBlocking<Unit> {
            insertArtist("Moderat", "moderat-artist")
            follow("venue", "club")
            follow("artist", "moderat-artist")
            val event = newEvent("Moderat live", "moderat-live")
            linkArtist(event, artistId("moderat-artist"))

            job.run()

            rows() shouldBe 1
            list()
                .jsonPath("$[0].aboutKind")
                .isEqualTo("artist")
                .jsonPath("$[0].aboutName")
                .isEqualTo("Moderat")
        }

    @Test
    fun `nothing for a past date, a cancelled event, or an account that follows nothing there`() =
        runBlocking<Unit> {
            val other = sessionFor("other@example.org")
            follow("venue", "club")
            newEvent("Yesterday", "yesterday", today.minusDays(1))
            val cancelled = newEvent("Cancelled", "cancelled")
            databaseClient.sql("UPDATE events.event SET status = 'CANCELLED' WHERE id = $cancelled").await()

            job.run()

            rows() shouldBe 0
            unread(other) shouldBe 0
        }

    @Test
    fun `marks one read, then all, and another account's id is not found`() =
        runBlocking<Unit> {
            val other = sessionFor("other@example.org")
            follow("venue", "club")
            newEvent("One", "one")
            newEvent("Two", "two")
            job.run()
            unread() shouldBe 2
            val id = firstId()

            webTestClient
                .post()
                .uri("/me/notifications/$id/read")
                .signedIn(other)
                .exchange()
                .expectStatus()
                .isNotFound
            webTestClient
                .post()
                .uri("/me/notifications/$id/read")
                .signedIn()
                .exchange()
                .expectStatus()
                .isNoContent
            unread() shouldBe 1

            webTestClient
                .post()
                .uri("/me/notifications/read")
                .signedIn()
                .exchange()
                .expectStatus()
                .isNoContent
            unread() shouldBe 0
            list(other).jsonPath("$.length()").isEqualTo(0)
        }

    @Test
    fun `a signed-out visitor gets 401`() {
        webTestClient
            .get()
            .uri("/me/notifications/unread-count")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `the cleanup deletes rows older than 30 days and rows of removed events, and keeps the rest`() =
        runBlocking<Unit> {
            follow("venue", "club")
            newEvent("Old", "old")
            newEvent("Removed", "removed")
            newEvent("Fresh", "fresh")
            job.run()
            databaseClient
                .sql(
                    "UPDATE accounts.notification SET created_at = now() - interval '31 days' " +
                        "WHERE event_id = (SELECT id FROM events.event WHERE slug = 'old')"
                ).await()
            databaseClient.sql("DELETE FROM events.event WHERE slug = 'removed'").await()

            cleanup.run()

            rows() shouldBe 1
            list().jsonPath("$[0].event").isEqualTo("fresh")
        }

    @Test
    fun `the export lists them, and deleting the account deletes them`() =
        runBlocking<Unit> {
            follow("venue", "club")
            newEvent("Moderat", "moderat")
            job.run()

            webTestClient
                .get()
                .uri("/me/export")
                .signedIn()
                .exchange()
                .expectStatus()
                .isOk
                .expectBody()
                .jsonPath("$.notifications[0].event")
                .isEqualTo("moderat")

            databaseClient.sql("DELETE FROM accounts.account").await()
            rows() shouldBe 0
        }

    private fun artistId(slug: String): Long =
        databaseClient
            .sql("SELECT id FROM events.artist WHERE slug = '$slug'")
            .map { row -> row.get("id", Long::class.javaObjectType)!! }
            .one()
            .block()!!
}
