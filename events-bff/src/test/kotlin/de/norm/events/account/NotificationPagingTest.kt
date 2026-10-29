package de.norm.events.account

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.ParameterizedTypeReference
import org.springframework.r2dbc.core.await
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The notifications page's list (#4169): keyset pages over the bell's order, the unread filter and the limit's bounds. */
class NotificationPagingTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessionRows: SessionStore

    private lateinit var session: String
    private lateinit var account: UUID
    private var club = 0L
    private val today: LocalDate = LocalDate.now(BERLIN)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private data class Page(
        val ids: List<String>,
        val next: String?
    )

    @BeforeEach
    fun setUp() =
        runBlocking<Unit> {
            databaseClient.sql("TRUNCATE TABLE accounts.notification, accounts.session, accounts.account CASCADE").await()
            val (token, id) = signIn("fan@example.org")
            session = token
            account = id
            club = insertVenue("Club", "club")
        }

    private suspend fun signIn(email: String): Pair<String, UUID> {
        val signedIn = accounts.signIn(email, "en", now)
        val token = SecretTokens.next()
        sessionRows.createSession(SecretTokens.hash(token), signedIn.id, now, now.plus(Duration.ofDays(1)))
        return token to signedIn.id
    }

    /** One notification [hoursAgo] old, for an event [inDays] ahead. */
    private suspend fun notify(
        who: UUID,
        slug: String,
        hoursAgo: Long,
        inDays: Long = 7,
        read: Boolean = false
    ) {
        val event =
            databaseClient
                .sql("SELECT id FROM events.event WHERE slug = :slug")
                .bind("slug", slug)
                .map { row -> row.get("id", Long::class.javaObjectType)!! }
                .all()
                .collectList()
                .block()!!
                .firstOrNull() ?: insertEvent(club, "Night $slug", slug, today.plusDays(inDays))
        databaseClient
            .sql(
                "INSERT INTO accounts.notification (account_id, event_id, kind, about_kind, about_id, created_at, read_at) " +
                    "VALUES (:account, :event, 'new_date', 'venue', :venue, :createdAt, ${if (read) ":createdAt" else "NULL"})"
            ).bind("account", who)
            .bind("event", event)
            .bind("venue", club)
            .bind("createdAt", now.minus(Duration.ofHours(hoursAgo)))
            .await()
    }

    private fun page(
        query: String,
        who: String = session
    ): Page {
        val result =
            webTestClient
                .get()
                .uri("/me/notifications$query")
                .cookie(SessionSecurityContextRepository.COOKIE, who)
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
                .returnResult()
        return Page(result.responseBody!!.map { it["id"] as String }, result.responseHeaders.getFirst("Next-Cursor"))
    }

    private fun walk(
        limit: Int,
        filter: String = ""
    ): List<String> {
        val seen = mutableListOf<String>()
        var next: String? = null
        do {
            val page = page("?limit=$limit$filter" + next?.let { "&before=$it" }.orEmpty())
            page.ids.size shouldNotBe 0
            seen += page.ids
            next = page.next
        } while (next != null)
        return seen
    }

    private fun status(query: String): Int =
        webTestClient
            .get()
            .uri("/me/notifications$query")
            .cookie(SessionSecurityContextRepository.COOKIE, session)
            .exchange()
            .returnResult(String::class.java)
            .status
            .value()

    /** Seven entries in three batches of equal `created_at`, and in the newest batch two of the same event date. */
    private suspend fun seedSeven() {
        notify(account, "a", hoursAgo = 1, inDays = 3)
        notify(account, "b", hoursAgo = 1, inDays = 1, read = true)
        notify(account, "c", hoursAgo = 1, inDays = 1)
        notify(account, "d", hoursAgo = 2, read = true)
        notify(account, "e", hoursAgo = 2)
        notify(account, "f", hoursAgo = 3)
        notify(account, "g", hoursAgo = 3)
    }

    @Test
    fun `pages of every size list each entry once, in the order of one long page, across equal created_at`() =
        runBlocking<Unit> {
            seedSeven()
            val whole = page("?limit=50")
            whole.ids shouldHaveSize 7
            whole.next shouldBe null

            (1..7).forEach { limit -> walk(limit) shouldBe whole.ids }
        }

    @Test
    fun `the unread filter leaves out the read entries, page by page`() =
        runBlocking<Unit> {
            seedSeven()
            val readIds =
                databaseClient
                    .sql("SELECT id::text AS id FROM accounts.notification WHERE read_at IS NOT NULL")
                    .map { row -> row.get("id", String::class.java)!! }
                    .all()
                    .collectList()
                    .block()!!

            val unread = walk(2, "&unread=true")

            unread shouldBe page("?limit=50").ids.filterNot { it in readIds }
            unread shouldHaveSize 5
        }

    @Test
    fun `without parameters it answers the newest 20, as the bell always got them`() =
        runBlocking<Unit> {
            (1L..21L).forEach { notify(account, "n$it", hoursAgo = it) }

            val bell = page("")

            bell.ids shouldBe page("?limit=50").ids.take(20)
            bell.next shouldNotBe null
            page("?before=${bell.next}").ids shouldHaveSize 1
        }

    @Test
    fun `another account's entries appear on no page, and its cursor positions only the session's own list`() =
        runBlocking<Unit> {
            seedSeven()
            val (otherSession, other) = signIn("other@example.org")
            notify(other, "a", hoursAgo = 1)
            notify(other, "z", hoursAgo = 2)

            val theirs = page("?limit=50", otherSession).ids.toSet()
            val theirCursor = page("?limit=1", otherSession).next!!

            theirs shouldHaveSize 2
            (walk(2) + walk(2, "&unread=true")).toSet().intersect(theirs) shouldBe emptySet()
            page("?limit=50&before=$theirCursor").ids.toSet().intersect(theirs) shouldBe emptySet()
        }

    @Test
    fun `an empty list has no next page`() {
        page("?limit=50") shouldBe Page(emptyList(), null)
        page("?unread=true") shouldBe Page(emptyList(), null)
    }

    @Test
    fun `the limit is 1 to 50, the cursor must be one, and an unknown parameter is refused`() {
        status("?limit=50") shouldBe 200
        status("?limit=51") shouldBe 400
        status("?limit=0") shouldBe 400
        status("?before=not-a-cursor") shouldBe 400
        status("?before=${NotificationCursor(now, today, UUID.randomUUID()).token()}") shouldBe 200
        status("?unred=true") shouldBe 400
    }

    @Test
    fun `a signed-out visitor gets 401`() {
        webTestClient
            .get()
            .uri("/me/notifications?limit=50&unread=true")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `a cursor reads back as the key it was made from`() {
        val cursor = NotificationCursor(now.plusNanos(123_000), today, UUID.randomUUID())
        NotificationCursor.parse(cursor.token()) shouldBe cursor
        NotificationCursor.parse("") shouldBe null
        NotificationCursor.parse("YQ") shouldBe null
    }
}
