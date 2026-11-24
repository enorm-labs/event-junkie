package de.norm.events.account

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import de.norm.events.push.PushSender
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.support.StaticListableBeanFactory
import org.springframework.r2dbc.core.await
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * A watch turns into an artist follow once an artist row carries its MusicBrainz id with the verdict EXACT (#5214), and the
 * bell announces the artist's upcoming events however long ago they were stored. The test context has no push sender.
 */
class WatchConversionTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var job: WatchConversionJob

    @Autowired
    private lateinit var watches: WatchStore

    @Autowired
    private lateinit var follows: FollowStore

    private lateinit var fan: UUID
    private var club = 0L
    private val today: LocalDate = LocalDate.now(BERLIN)

    @BeforeEach
    fun seed() =
        runBlocking<Unit> {
            databaseClient
                .sql("TRUNCATE TABLE accounts.notification, accounts.push_subscription, accounts.follow, accounts.session, accounts.account CASCADE")
                .await()
            // The new-dates job's window has long passed every event below.
            databaseClient.sql("UPDATE accounts.push_cursor SET sent_until = now()").await()
            fan = accounts.signIn("fan@example.org", "en", Instant.now()).id
            club = insertVenue("Club", "club")
        }

    private suspend fun watch(
        account: UUID = fan,
        mbid: String = MODERAT
    ) = watches.watch(account, mbid, "Moderat", Instant.now())

    /** An artist row with [match]; [mbid] only on EXACT, as the CHECK demands. */
    private suspend fun artist(
        slug: String,
        match: String = "EXACT",
        mbid: String = MODERAT
    ): Long =
        insertArtist(slug, slug).also { id ->
            val stored = if (match == "EXACT") "'$mbid'" else "NULL"
            databaseClient.sql("UPDATE events.artist SET musicbrainz_match = '$match', musicbrainz_id = $stored WHERE id = $id").await()
        }

    /** An event stored a week ago, long before its artist's verdict. */
    private suspend fun billed(
        artistId: Long,
        slug: String,
        date: LocalDate = today.plusDays(7),
        status: String = "SCHEDULED"
    ): Long =
        insertEvent(club, slug, slug, date).also { id ->
            linkArtist(id, artistId)
            databaseClient.sql("UPDATE events.event SET created_at = now() - interval '7 days', status = '$status' WHERE id = $id").await()
        }

    private fun notified(account: UUID = fan): List<String> =
        databaseClient
            .sql("SELECT e.slug FROM accounts.notification n JOIN events.event e ON e.id = n.event_id WHERE n.account_id = :account")
            .bind("account", account)
            .map { row -> row.get("slug", String::class.java)!! }
            .all()
            .collectList()
            .block()!!

    @Test
    fun `an EXACT row turns the watch into a follow with its bell, and announces its upcoming events however old`() =
        runBlocking<Unit> {
            watch()
            val moderat = artist("moderat")
            billed(moderat, "moderat-in-berlin")
            billed(moderat, "moderat-last-year", date = today.minusDays(1))
            billed(moderat, "moderat-cancelled", status = "CANCELLED")

            job.run()

            watches.list(fan).shouldBeEmpty()
            follows.list(fan).map { it.slug to it.notify } shouldBe listOf("moderat" to "instant")
            notified() shouldBe listOf("moderat-in-berlin")
        }

    @Test
    fun `a second run changes nothing`() =
        runBlocking<Unit> {
            watch()
            billed(artist("moderat"), "moderat-in-berlin")

            job.run()
            job.run()

            notified() shouldBe listOf("moderat-in-berlin")
            follows.list(fan).size shouldBe 1
        }

    @Test
    fun `an AMBIGUOUS or unchecked row converts nothing`() =
        runBlocking<Unit> {
            watch()
            billed(artist("moderat", match = "AMBIGUOUS"), "moderat-in-berlin")
            billed(artist("moderat-2", match = "UNCHECKED"), "moderat-again")

            job.run()

            watches.list(fan).map { it.mbid } shouldBe listOf(MODERAT)
            notified().shouldBeEmpty()
        }

    @Test
    fun `two rows with one id give two follows and one notification per event`() =
        runBlocking<Unit> {
            watch()
            val first = artist("moderat")
            val second = artist("moderat-live")
            val both = billed(first, "moderat-in-berlin")
            linkArtist(both, second, billingOrder = 1)
            billed(second, "moderat-live-in-berlin")

            job.run()

            follows.list(fan).map { it.slug } shouldContainExactlyInAnyOrder listOf("moderat", "moderat-live")
            notified() shouldContainExactlyInAnyOrder listOf("moderat-in-berlin", "moderat-live-in-berlin")
        }

    @Test
    fun `a watch of an artist the account already follows keeps that follow and its bell`() =
        runBlocking<Unit> {
            val moderat = artist("moderat")
            follows.follow(fan, FollowKind.ARTIST, moderat, Instant.now())
            watch()

            job.run()

            watches.list(fan).shouldBeEmpty()
            follows.list(fan).map { it.slug to it.notify } shouldBe listOf("moderat" to "off")
        }

    @Test
    fun `a conversion is due for push only with the bell on and a browser subscribed`() =
        runBlocking<Unit> {
            val other = accounts.signIn("other@example.org", "de", Instant.now()).id
            watch()
            watch(other)
            databaseClient
                .sql("UPDATE accounts.follow SET notify = 'off' WHERE account_id = :other")
                .bind("other", other)
                .await()
            listOf(fan, other).forEachIndexed { i, account ->
                databaseClient
                    .sql("INSERT INTO accounts.push_subscription (account_id, endpoint, p256dh, auth) VALUES (:account, :endpoint, 'p', 'a')")
                    .bind("account", account)
                    .bind("endpoint", "https://fcm.googleapis.com/fcm/send/$i")
                    .await()
            }
            billed(artist("moderat"), "moderat-in-berlin")

            val rows = watches.convert(today, Instant.now())

            rows.map { it.accountId to it.push } shouldContainExactlyInAnyOrder listOf(fan to true, other to false)
            rows.single { it.accountId == fan }.announced!!.slug shouldBe "moderat-in-berlin"
        }

    @Test
    fun `the table refuses a watch without its id or name, and an id of the wrong shape`() =
        runBlocking<Unit> {
            listOf(
                "INSERT INTO accounts.follow (account_id, kind, name) VALUES ('$fan', 'watch', 'Moderat')",
                "INSERT INTO accounts.follow (account_id, kind, mbid) VALUES ('$fan', 'watch', '$MODERAT')",
                "INSERT INTO accounts.follow (account_id, kind, mbid, name) VALUES ('$fan', 'watch', 'MODERAT', 'Moderat')",
                "INSERT INTO accounts.follow (account_id, kind, entity_id, mbid) VALUES ('$fan', 'artist', 1, '$MODERAT')"
            ).forEach { sql -> shouldThrowAny { databaseClient.sql(sql).await() } }
        }

    @Test
    fun `setting every bell includes the watches, and the orphan cleanup leaves them`() =
        runBlocking<Unit> {
            watch()

            follows.setAllNotify(fan, "off")
            follows.deleteOrphans()

            watches.list(fan).map { it.notify } shouldBe listOf("off")
        }

    @Test
    fun `a failed run is counted and leaves the last success, and a run that held moves it`() {
        val registry = SimpleMeterRegistry()
        val start = Instant.parse("2026-11-24T10:00:00Z")
        val clock = SteppingClock(start)
        var broken = true
        val store =
            object : WatchStore(databaseClient) {
                override suspend fun convert(
                    today: LocalDate,
                    now: Instant
                ): List<ConvertedWatch> = if (broken) error("database gone") else super.convert(today, now)
            }
        val job = conversion(store, clock, registry)
        lastSuccess(registry) shouldBe start.epochSecond.toDouble()

        clock.instant = start.plus(1, ChronoUnit.HOURS)
        runBlocking { job.run() }
        lastSuccess(registry) shouldBe start.epochSecond.toDouble()
        failedRuns(registry) shouldBe 1.0

        broken = false
        clock.instant = start.plus(2, ChronoUnit.HOURS)
        runBlocking { job.run() }
        lastSuccess(registry) shouldBe clock.instant.epochSecond.toDouble()
        failedRuns(registry) shouldBe 1.0
    }

    private fun conversion(
        store: WatchStore,
        clock: Clock,
        registry: MeterRegistry
    ) = WatchConversionJob(
        store,
        PushSubscriptionStore(databaseClient),
        AccountMetrics(registry),
        NotificationMetrics(registry),
        StaticListableBeanFactory().getBeanProvider(PushSender::class.java),
        clock
    )

    // The literals, not the constants: the alert rule reads these names (architecture.instructions.md § Metrics).
    private fun lastSuccess(registry: MeterRegistry) = registry.find("bff.account.watch.conversion.last_success").gauge()!!.value()

    private fun failedRuns(registry: MeterRegistry) =
        registry
            .find("bff.account.watch")
            .tag("action", "conversion_failed")
            .counter()!!
            .count()

    /** A clock the test moves, so the seed and the stamp can differ. */
    private class SteppingClock(
        var instant: Instant
    ) : Clock() {
        override fun getZone(): ZoneId = BERLIN

        override fun withZone(zone: ZoneId): Clock = this

        override fun instant(): Instant = instant
    }

    private companion object {
        const val MODERAT = "2d2a7d9e-6cf0-4f2f-9d5c-4b9b1e3a7f10"
    }
}
