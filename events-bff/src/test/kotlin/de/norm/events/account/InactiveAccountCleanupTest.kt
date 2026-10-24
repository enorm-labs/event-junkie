package de.norm.events.account

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetupTest
import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import de.norm.events.mail.Mailer
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.mail.javamail.JavaMailSenderImpl
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.flow
import java.net.ServerSocket
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** The daily cleanup of inactive accounts, expired links and sessions (#3905), on a fixed clock, with the mail caught by GreenMail. */
class InactiveAccountCleanupTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var sessions: SessionStore

    @Autowired
    private lateinit var metrics: AccountMetrics

    @Autowired
    private lateinit var registry: MeterRegistry

    private val now: Instant = LocalDateTime.of(2028, 10, 1, 4, 30).atZone(BERLIN).toInstant()

    @BeforeEach
    fun clearAccounts() =
        runBlocking {
            greenMail.purgeEmailFromAllMailboxes()
            databaseClient.sql("TRUNCATE TABLE accounts.session, accounts.one_time_token, accounts.account CASCADE").await()
        }

    private fun mailer(port: Int = greenMail.smtp.port) =
        Mailer(
            JavaMailSenderImpl().apply {
                host = "localhost"
                this.port = port
                javaMailProperties["mail.smtp.connectiontimeout"] = 2000
                javaMailProperties["mail.smtp.timeout"] = 2000
            },
            "Event Junkie <noreply@notify.event-junkie.de>",
            registry
        )

    private fun cleanup(
        at: Instant = now,
        mailer: Mailer? = mailer()
    ) = InactiveAccountCleanup(
        accounts,
        sessions,
        StaticListableBeanFactory(if (mailer == null) emptyMap() else mapOf("mailer" to mailer)).getBeanProvider(Mailer::class.java),
        metrics,
        Clock.fixed(at, BERLIN),
        "https://test.event-junkie.example"
    )

    private fun monthsAgo(
        months: Long,
        from: Instant = now
    ): Instant = from.atZone(BERLIN).minusMonths(months).toInstant()

    private fun account(
        email: String,
        lastSignIn: Instant,
        warned: Instant? = null,
        locale: String = "en"
    ) = runBlocking {
        databaseClient
            .sql("INSERT INTO accounts.account (email, locale, created_at, last_sign_in_at, deletion_warned_at) VALUES (:e, :l, :s, :s, :w)")
            .bind("e", email)
            .bind("l", locale)
            .bind("s", lastSignIn)
            .let { if (warned == null) it.bindNull("w", Instant::class.java) else it.bind("w", warned) }
            .await()
    }

    private fun emails(): List<String> =
        runBlocking {
            databaseClient
                .sql("SELECT email FROM accounts.account")
                .map { row -> row.get("email", String::class.java)!! }
                .flow()
                .toList()
        }

    private fun warnedAt(email: String): Instant? =
        runBlocking {
            databaseClient
                .sql("SELECT deletion_warned_at FROM accounts.account WHERE email = :e")
                .bind("e", email)
                .map { row -> java.util.Optional.ofNullable(row.get("deletion_warned_at", Instant::class.java)) }
                .one()
                .block()!!
                .orElse(null)
        }

    private fun recipients() = greenMail.receivedMessages.map { it.allRecipients.single().toString() }

    @Test
    fun `an account 23 months inactive is warned once, in its language`() {
        account("old@example.org", monthsAgo(23).minus(1, ChronoUnit.DAYS), locale = "de")
        account("recent@example.org", monthsAgo(22))

        runBlocking { cleanup().run() }
        runBlocking { cleanup(now.plus(1, ChronoUnit.DAYS)).run() }

        recipients() shouldBe listOf("old@example.org")
        val mail = greenMail.receivedMessages.single()
        mail.subject shouldBe "Dein Konto bei Event Junkie wird gelöscht"
        GreenMailUtil.getBody(mail) shouldContain "https://test.event-junkie.example/de/sign-in"
        warnedAt("old@example.org") shouldBe now
        warnedAt("recent@example.org") shouldBe null
    }

    @Test
    fun `an account 24 months inactive and warned a month ago is deleted with its links and sessions`() {
        runBlocking {
            val id = accounts.signIn("gone@example.org", "en", monthsAgo(25)).id
            databaseClient
                .sql("UPDATE accounts.account SET last_sign_in_at = :s, deletion_warned_at = :w")
                .bind("s", monthsAgo(24).minus(1, ChronoUnit.DAYS))
                .bind("w", now.minus(31, ChronoUnit.DAYS))
                .await()
            sessions.createSession("s1", id, now, now.plus(1, ChronoUnit.DAYS))
            accounts.issueToken("t1", "GONE@example.org", "en", now, now.plus(15, ChronoUnit.MINUTES))
        }

        runBlocking { cleanup().run() }

        emails() shouldHaveSize 0
        runBlocking { sessions.findSession("s1", now) } shouldBe null
        runBlocking { accounts.linksSince("gone@example.org", now.minus(1, ChronoUnit.DAYS)) } shouldBe 0L
        greenMail.receivedMessages shouldHaveSize 0
    }

    @Test
    fun `an account 24 months inactive but never warned, or warned too recently, is warned or kept, not deleted`() {
        account("unwarned@example.org", monthsAgo(25))
        account("fresh-warning@example.org", monthsAgo(25), warned = now.minus(29, ChronoUnit.DAYS))

        runBlocking { cleanup().run() }

        emails() shouldContainExactlyInAnyOrder listOf("unwarned@example.org", "fresh-warning@example.org")
        recipients() shouldBe listOf("unwarned@example.org")
        GreenMailUtil.getBody(greenMail.receivedMessages.single()) shouldContain "October 31, 2028"
    }

    @Test
    fun `a sign-in after the warning cancels the deletion`() {
        account("back@example.org", monthsAgo(23).minus(1, ChronoUnit.DAYS))
        runBlocking { cleanup().run() }
        warnedAt("back@example.org") shouldBe now

        runBlocking { accounts.signIn("back@example.org", "en", now.plus(1, ChronoUnit.DAYS)) }
        runBlocking { cleanup(now.plus(40, ChronoUnit.DAYS)).run() }

        warnedAt("back@example.org") shouldBe null
        emails() shouldBe listOf("back@example.org")
    }

    @Test
    fun `a warning the relay refuses is released and sent by the next run`() {
        account("retry@example.org", monthsAgo(23).minus(1, ChronoUnit.DAYS))
        val closedPort = ServerSocket(0).use { it.localPort }

        runBlocking { cleanup(mailer = mailer(closedPort)).run() }
        warnedAt("retry@example.org") shouldBe null

        runBlocking { cleanup(now.plus(1, ChronoUnit.DAYS)).run() }
        recipients() shouldBe listOf("retry@example.org")
    }

    @Test
    fun `without a mail sender nobody is warned and nobody unwarned is deleted`() {
        account("no-mail@example.org", monthsAgo(25))

        runBlocking { cleanup(mailer = null).run() }

        emails() shouldBe listOf("no-mail@example.org")
        warnedAt("no-mail@example.org") shouldBe null
    }

    @Test
    fun `two replicas running at once send each warning once`() {
        (1..20).forEach { account("many-$it@example.org", monthsAgo(23).minus(1, ChronoUnit.DAYS)) }

        runBlocking(Dispatchers.IO) { listOf(cleanup(), cleanup()).map { async { it.run() } }.awaitAll() }

        recipients().sorted() shouldBe (1..20).map { "many-$it@example.org" }.sorted()
    }

    @Test
    fun `expired links an hour past expiry and expired sessions are purged, live ones kept`() {
        runBlocking {
            val id = accounts.signIn("keep@example.org", "en", now).id
            sessions.createSession("live", id, now, now.plus(1, ChronoUnit.DAYS))
            sessions.createSession("dead", id, now.minus(31, ChronoUnit.DAYS), now.minus(1, ChronoUnit.MINUTES))
            accounts.issueToken("old-link", "keep@example.org", "en", now.minus(2, ChronoUnit.HOURS), now.minus(105, ChronoUnit.MINUTES))
            accounts.issueToken("recent-link", "keep@example.org", "en", now.minus(30, ChronoUnit.MINUTES), now.minus(15, ChronoUnit.MINUTES))
        }

        runBlocking { cleanup().run() }

        val left =
            runBlocking {
                databaseClient
                    .sql("SELECT token_hash AS h FROM accounts.session UNION ALL SELECT token_hash FROM accounts.one_time_token")
                    .map { row -> row.get("h", String::class.java)!! }
                    .flow()
                    .toList()
            }
        left shouldContainExactlyInAnyOrder listOf("live", "recent-link")
    }

    companion object {
        private val greenMail = GreenMail(ServerSetupTest.SMTP.dynamicPort()).apply { start() }

        @JvmStatic
        @AfterAll
        fun stopMail() = greenMail.stop()
    }
}
