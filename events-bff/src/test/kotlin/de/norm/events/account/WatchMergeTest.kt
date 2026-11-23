package de.norm.events.account

import de.norm.events.BaseControllerTest
import de.norm.events.common.BERLIN
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.r2dbc.core.await
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** A watch on an id MusicBrainz merged later moves to the survivor (#5225), against a local MusicBrainz. */
class WatchMergeTest : BaseControllerTest() {
    @Autowired
    private lateinit var accounts: AccountStore

    @Autowired
    private lateinit var watches: WatchStore

    @Autowired
    private lateinit var job: WatchMergeJob

    private lateinit var fan: UUID
    private var requestsBefore = 0

    private fun requests() = server.requestCount - requestsBefore

    @BeforeEach
    fun seed() =
        runBlocking<Unit> {
            databaseClient.sql("TRUNCATE TABLE accounts.follow, accounts.session, accounts.account CASCADE").await()
            // The month before this one, so the next run claims this month.
            val previous = LocalDate.now(BERLIN).withDayOfMonth(1).minusMonths(1)
            databaseClient.sql("UPDATE accounts.push_cursor SET watches_checked_until = '$previous'").await()
            generateSequence { server.takeRequest(0, TimeUnit.SECONDS) }.count() // Drains what an earlier test left.
            requestsBefore = server.requestCount
            fan = accounts.signIn("fan@example.org", "en", Instant.now()).id
        }

    private fun entity(
        id: String,
        name: String
    ) = MockResponse
        .Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .body("""{"id":"$id","name":"$name","type":"Group","country":"DE"}""")
        .build()

    private fun status(code: Int) =
        MockResponse
            .Builder()
            .code(code)
            .build()

    private suspend fun watch(
        mbid: String,
        name: String,
        account: UUID = fan
    ) = watches.watch(account, mbid, name, Instant.now())

    private suspend fun watched(account: UUID = fan) = watches.list(account).map { it.mbid to it.name }

    @Test
    fun `a watch on a merged id moves to the survivor with its name`() =
        runBlocking<Unit> {
            watch(OLD, "Moderat (duplicate)")
            server.enqueue(entity(MODERAT, "Moderat"))

            job.run()

            watched() shouldBe listOf(MODERAT to "Moderat")
            server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath shouldBe "/ws/2/artist/$OLD"
        }

    @Test
    fun `an id that was not merged leaves its watch as it is`() =
        runBlocking<Unit> {
            watch(MODERAT, "Moderat")
            server.enqueue(entity(MODERAT, "Moderat"))

            job.run()

            watched() shouldBe listOf(MODERAT to "Moderat")
        }

    @Test
    fun `an account that watches both ids ends with one watch`() =
        runBlocking<Unit> {
            watch(MODERAT, "Moderat")
            watch(OLD, "Moderat (duplicate)")
            val other = accounts.signIn("other@example.org", "en", Instant.now()).id
            watch(OLD, "Moderat (duplicate)", other)
            // Both reads answer the survivor: OLD is merged, MODERAT is the survivor itself.
            server.enqueue(entity(MODERAT, "Moderat"))
            server.enqueue(entity(MODERAT, "Moderat"))

            job.run()

            watched() shouldBe listOf(MODERAT to "Moderat")
            watched(other) shouldBe listOf(MODERAT to "Moderat")
        }

    @Test
    fun `an id MusicBrainz does not know keeps its watch`() =
        runBlocking<Unit> {
            watch(OLD, "Gone")
            server.enqueue(status(404))

            job.run()

            watched() shouldBe listOf(OLD to "Gone")
        }

    @Test
    fun `MusicBrainz unavailable stops the run and changes nothing`() =
        runBlocking<Unit> {
            watch(OLD, "Moderat (duplicate)")
            watch(KNOWN, "Another act")
            // The client retries a 503 once; the second ends the run before KNOWN is read.
            server.enqueue(status(503))
            server.enqueue(status(503))

            job.run()

            watched().toSet() shouldBe setOf(OLD to "Moderat (duplicate)", KNOWN to "Another act")
            requests() shouldBe 2
        }

    @Test
    fun `two replicas in one month read MusicBrainz once`() =
        runBlocking<Unit> {
            watch(OLD, "Moderat (duplicate)")
            // One answer only: a second read would wait for it, time out, and still count as a request.
            server.enqueue(entity(MODERAT, "Moderat"))

            coroutineScope { List(2) { async { job.run() } }.awaitAll() }
            job.run()

            requests() shouldBe 1
            watched() shouldBe listOf(MODERAT to "Moderat")
        }

    companion object {
        private val server = MockWebServer().apply { start() }
        private const val MODERAT = "2d2a7d9e-6cf0-4f2f-9d5c-4b9b1e3a7f10"
        private const val OLD = "11111111-2222-4333-8444-555555555555"
        private const val KNOWN = "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee"

        @JvmStatic
        @DynamicPropertySource
        fun musicBrainz(registry: DynamicPropertyRegistry) {
            registry.add("app.musicbrainz.url") { server.url("/ws/2/").toString() }
            registry.add("app.musicbrainz.pace") { "PT0S" }
        }

        @JvmStatic
        @AfterAll
        fun stop() = server.close()
    }
}
