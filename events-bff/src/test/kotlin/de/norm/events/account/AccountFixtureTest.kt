package de.norm.events.account

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.core.ParameterizedTypeReference
import org.springframework.r2dbc.core.await
import org.springframework.test.web.reactive.server.WebTestClient
import tools.jackson.databind.json.JsonMapper
import java.io.File

/**
 * Loads `fixtures/events.sql` and `fixtures/accounts.sql` (#4102) and signs in with the tokens `fixtures/accounts.json`
 * hands to k6, ZAP and Playwright. A migration that breaks the file, or a token that drifts from its hash, fails here
 * instead of as a scan that quietly runs signed out.
 */
class AccountFixtureTest : BaseControllerTest() {
    private data class FixtureAccount(
        val email: String,
        val session: String,
        val savedSearchId: String
    )

    private val fixture =
        JsonMapper
            .builder()
            .build()
            .readTree(File(System.getProperty("fixture.accounts.json")))
            .get("accounts")

    private val accounts: Map<String, FixtureAccount> =
        fixture
            .associate {
                it.get("email").asString() to
                    FixtureAccount(it.get("email").asString(), it.get("session").asString(), it.get("savedSearchId").asString())
            }
    private val ada = accounts.getValue("ada@example.de")
    private val bob = accounts.getValue("bob@example.de")

    @BeforeEach
    fun loadFixtures() =
        runBlocking {
            databaseClient.sql("TRUNCATE TABLE accounts.follow, accounts.rsvp, accounts.session, accounts.account CASCADE").await()
            listOf("fixture.sql", "fixture.accounts.sql").forEach { databaseClient.sql(File(System.getProperty(it)).readText()).await() }
        }

    private fun WebTestClient.RequestHeadersSpec<*>.signedInAs(account: FixtureAccount) = cookie(SessionSecurityContextRepository.COOKIE, account.session)

    private fun get(
        path: String,
        account: FixtureAccount
    ) = webTestClient
        .get()
        .uri(path)
        .signedInAs(account)
        .exchange()
        .expectStatus()
        .isOk
        .expectBody()

    @Test
    fun `each fixture token signs in as its account`() {
        accounts.values.forEach { get("/auth/session", it).jsonPath("$.account.email").isEqualTo(it.email) }
    }

    @Test
    fun `the seeded follows, saved searches and RSVPs come back through the API`() {
        get("/me/follows", ada)
            .jsonPath("$[*].slug")
            .value<List<String>> { it shouldContainExactlyInAnyOrder listOf("kesselhaus-nord", "mobius-trio", "nachtschicht-kollektiv") }
        get("/me/follows", bob).jsonPath("$[*].slug").value<List<String>> { it shouldBe listOf("hinterhof-bar") }
        get("/me/searches", ada).jsonPath("$[0].id").isEqualTo(ada.savedSearchId)
        get("/me/searches", bob).jsonPath("$[0].id").isEqualTo(bob.savedSearchId)

        val statuses =
            webTestClient
                .get()
                .uri("/me/rsvps")
                .signedInAs(ada)
                .exchange()
                .expectBody(object : ParameterizedTypeReference<Map<String, String>>() {})
                .returnResult()
                .responseBody!!
        statuses.values shouldContainExactlyInAnyOrder listOf("going", "interested")
    }

    @Test
    fun `bob's passkey and push subscription come back, for the isolation check to aim at`() {
        val bobFixture = fixture.single { it.get("email").asString() == bob.email }
        get("/me/passkeys", bob).jsonPath("$[0].id").isEqualTo(bobFixture.get("passkeyId").asString())
        get("/me/export", bob).jsonPath("$.pushSubscriptions[*].pushService").value<List<String>> { it.size shouldBe 1 }
    }

    @Test
    fun `bob's bell holds one unread cancellation of his RSVP`() {
        val bobFixture = fixture.single { it.get("email").asString() == bob.email }
        get("/me/notifications", bob)
            .jsonPath("$.length()")
            .isEqualTo(1)
            .jsonPath("$[0].id")
            .isEqualTo(bobFixture.get("notificationId").asString())
            .jsonPath("$[0].kind")
            .isEqualTo("change")
            .jsonPath("$[0].change")
            .isEqualTo("cancelled")
            .jsonPath("$[0].readAt")
            .isEmpty
    }

    @Test
    fun `ada's notifications need a second page, all and unread, and none of them is bob's`() {
        fun walk(filter: String): List<String> {
            val ids = mutableListOf<String>()
            var next: String? = null
            do {
                val result =
                    webTestClient
                        .get()
                        .uri("/me/notifications?limit=20$filter" + next?.let { "&before=$it" }.orEmpty())
                        .signedInAs(ada)
                        .exchange()
                        .expectBody(object : ParameterizedTypeReference<List<Map<String, Any?>>>() {})
                        .returnResult()
                ids += result.responseBody!!.map { it["id"] as String }
                next = result.responseHeaders.getFirst("Next-Cursor")
            } while (next != null)
            return ids
        }

        val all = walk("")
        val unread = walk("&unread=true")
        all.size shouldBeGreaterThan 20
        all.toSet().size shouldBe all.size
        // scripts/account-isolation.sh walks both lists 10 at a time and needs a second page of each.
        unread.size shouldBeGreaterThan 10
        unread.size shouldBeLessThan all.size
        all.containsAll(unread) shouldBe true
        all shouldNotContain fixture.single { it.get("email").asString() == bob.email }.get("notificationId").asString()
    }

    @Test
    fun `each account's reminder switch comes back as the fixture states it`() {
        fixture.forEach { row ->
            val account = accounts.getValue(row.get("email").asString())
            get("/me", account).jsonPath("$.reminderPush").isEqualTo(row.get("reminderPush").asBoolean())
        }
    }

    @Test
    fun `the saved queries are stored as the BFF normalises them`() {
        listOf(ada, bob).forEach { account ->
            get("/me/searches", account).jsonPath("$[0].query").value<String> { SavedSearchQuery.normalise(it).getOrThrow() shouldBe it }
        }
    }

    @Test
    fun `a write needs only a made-up XSRF pair beside the session cookie`() {
        fun follow(csrfHeader: String?) =
            webTestClient
                .put()
                .uri("/me/follows/artist/dj-uberdruck")
                .signedInAs(ada)
                .cookie(SessionSecurityContextRepository.CSRF_COOKIE, "fixture-csrf")
                .headers { headers -> csrfHeader?.let { headers.set("X-XSRF-TOKEN", it) } }
                .exchange()

        follow(csrfHeader = null).expectStatus().isForbidden
        follow(csrfHeader = "fixture-csrf").expectStatus().is2xxSuccessful
    }
}
