package de.norm.events.account

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/** The account gauges `/traffic` reads, set from the database on a fixed clock. */
class AccountGaugeRefreshTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var follows: FollowStore

    @Autowired
    private lateinit var metrics: AccountMetrics

    @Autowired
    private lateinit var registry: MeterRegistry

    private val now: Instant = Instant.parse("2028-10-01T10:00:00Z")

    @BeforeEach
    fun clearAccounts() =
        runBlocking {
            databaseClient.sql("TRUNCATE TABLE accounts.follow, accounts.session, accounts.one_time_token, accounts.account CASCADE").await()
        }

    private fun account(lastSignIn: Instant): UUID =
        runBlocking {
            databaseClient
                .sql("INSERT INTO accounts.account (email, locale, created_at, last_sign_in_at) VALUES (:e, 'en', :s, :s) RETURNING id")
                .bind("e", "${UUID.randomUUID()}@example.de")
                .bind("s", lastSignIn)
                .map { row -> row.get("id", UUID::class.java)!! }
                .one()
                .block()!!
        }

    private fun session(
        accountId: UUID,
        expiresAt: Instant
    ) = runBlocking {
        databaseClient
            .sql("INSERT INTO accounts.session (token_hash, account_id, expires_at) VALUES (:h, :a, :x)")
            .bind("h", UUID.randomUUID().toString())
            .bind("a", accountId)
            .bind("x", expiresAt)
            .await()
    }

    private fun follow(
        accountId: UUID,
        kind: String,
        entityId: Long
    ) = runBlocking {
        databaseClient
            .sql("INSERT INTO accounts.follow (account_id, kind, entity_id) VALUES (:a, :k, :e)")
            .bind("a", accountId)
            .bind("k", kind)
            .bind("e", entityId)
            .await()
    }

    private fun gauge(
        name: String,
        vararg tags: String
    ): Double =
        registry
            .get(name)
            .tags(*tags)
            .gauge()
            .value()

    @Test
    fun `the gauges count accounts, active accounts, live sessions and follows per kind`() {
        val recent = account(now.minus(2, ChronoUnit.DAYS))
        val idle = account(now.minus(40, ChronoUnit.DAYS))
        session(recent, now.plus(1, ChronoUnit.DAYS))
        session(recent, now.plus(3, ChronoUnit.DAYS))
        session(idle, now.minus(1, ChronoUnit.DAYS))
        follow(recent, "artist", 1)
        follow(recent, "artist", 2)
        follow(idle, "venue", 1)

        runBlocking { AccountGaugeRefresh(accounts, follows, metrics, Clock.fixed(now, BERLIN)).refresh() }

        gauge(AccountMetrics.ACCOUNTS, AccountMetrics.TAG_STATE, "all") shouldBe 2.0
        gauge(AccountMetrics.ACCOUNTS, AccountMetrics.TAG_STATE, "active") shouldBe 1.0
        gauge(AccountMetrics.SESSIONS) shouldBe 2.0
        gauge(AccountMetrics.FOLLOWS, AccountMetrics.TAG_KIND, "artist") shouldBe 2.0
        gauge(AccountMetrics.FOLLOWS, AccountMetrics.TAG_KIND, "venue") shouldBe 1.0
        gauge(AccountMetrics.FOLLOWS, AccountMetrics.TAG_KIND, "promoter") shouldBe 0.0
    }
}
