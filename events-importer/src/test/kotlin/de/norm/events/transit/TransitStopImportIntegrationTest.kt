package de.norm.events.transit

import de.norm.events.BaseControllerTest
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.flow
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * [TransitStopImportService] against a real database and a local server standing in for VBB (#2724): what it stores,
 * that a second import replaces the first, and that a failed or short download keeps the stored stations.
 */
class TransitStopImportIntegrationTest : BaseControllerTest() {
    @Autowired
    private lateinit var store: TransitStopStore

    @Autowired
    private lateinit var buildProperties: ObjectProvider<BuildProperties>

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.close()
    }

    @Test
    fun `stores the stations of the feed with their lines, and names the project in the User-Agent`(): Unit =
        runBlocking {
            server.enqueue(zip(GtfsFixture.bytes()))

            service().runOnce().shouldNotBeNull().stations shouldBe 3

            stored() shouldContainExactly
                listOf(
                    "orphan-platform" to listOf("U1"),
                    "potsdam-hbf" to listOf("M10"),
                    "warschauer" to listOf("S3", "S5", "S25", "U1")
                )
            server.takeRequest().headers["User-Agent"].shouldNotBeNull() shouldStartWith "event-junkie/"
        }

    @Test
    fun `a second import replaces the first`(): Unit =
        runBlocking {
            server.enqueue(zip(GtfsFixture.bytes()))
            service().runOnce().shouldNotBeNull()
            val withoutTram = GtfsFixture.FILES + ("routes.txt" to GtfsFixture.FILES.getValue("routes.txt").replace(",900", ",700"))
            server.enqueue(zip(GtfsFixture.bytes(withoutTram)))

            service().runOnce().shouldNotBeNull().stations shouldBe 2

            stored().map { it.first } shouldContainExactly listOf("orphan-platform", "warschauer")
        }

    @Test
    fun `a failed download keeps the stored stations`(): Unit =
        runBlocking {
            server.enqueue(zip(GtfsFixture.bytes()))
            service().runOnce().shouldNotBeNull()
            server.enqueue(MockResponse.Builder().code(503).build())

            service().runOnce().shouldBeNull()

            stored().size shouldBe 3
        }

    @Test
    fun `a feed with fewer stations than the minimum keeps the stored ones`(): Unit =
        runBlocking {
            server.enqueue(zip(GtfsFixture.bytes()))
            service().runOnce().shouldNotBeNull()
            server.enqueue(zip(GtfsFixture.bytes()))

            service(minStations = 4).runOnce().shouldBeNull()

            stored().size shouldBe 3
        }

    @Test
    fun `a file that is not a zip keeps the stored stations`(): Unit =
        runBlocking {
            server.enqueue(zip(GtfsFixture.bytes()))
            service().runOnce().shouldNotBeNull()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("<html>maintenance</html>")
                    .build()
            )

            service().runOnce().shouldBeNull()

            stored().size shouldBe 3
        }

    @Test
    fun `an import that replaces the rows stamps the gauge, and one that fails leaves it`(): Unit =
        runBlocking {
            val registry = SimpleMeterRegistry()
            val metrics = TransitStopMetrics(registry)
            lastSuccess(registry) shouldBe 0.0
            val first = Instant.parse("2026-10-02T04:45:00Z")
            server.enqueue(zip(GtfsFixture.bytes()))

            service(metrics = metrics, at = first).runOnce().shouldNotBeNull()
            lastSuccess(registry) shouldBe first.epochSecond.toDouble()

            val later = Instant.parse("2027-01-02T04:45:00Z")
            server.enqueue(MockResponse.Builder().code(503).build())
            service(metrics = metrics, at = later).runOnce().shouldBeNull()
            server.enqueue(zip(GtfsFixture.bytes()))
            service(minStations = 4, metrics = metrics, at = later).runOnce().shouldBeNull()
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("<html>maintenance</html>")
                    .build()
            )
            service(metrics = metrics, at = later).runOnce().shouldBeNull()

            lastSuccess(registry) shouldBe first.epochSecond.toDouble()
        }

    @Test
    fun `the gauge starts from the newest stored import, and stays at 0 before the first`(): Unit =
        runBlocking {
            databaseClient.sql("DELETE FROM events.transit_stop").await()
            val registry = SimpleMeterRegistry()
            val starting = service(metrics = TransitStopMetrics(registry))
            starting.seedLastSuccess()
            lastSuccess(registry) shouldBe 0.0

            server.enqueue(zip(GtfsFixture.bytes()))
            service(at = Instant.parse("2026-07-02T04:45:00Z")).runOnce().shouldNotBeNull()
            val newest = Instant.parse("2026-10-02T04:45:00Z")
            databaseClient
                .sql("UPDATE events.transit_stop SET imported_at = :newest WHERE gtfs_stop_id = 'warschauer'")
                .bind("newest", newest)
                .await()

            starting.seedLastSuccess()
            lastSuccess(registry) shouldBe newest.epochSecond.toDouble()
        }

    private fun service(
        minStations: Int = 1,
        metrics: TransitStopMetrics = TransitStopMetrics(SimpleMeterRegistry()),
        at: Instant = Instant.parse("2026-10-02T04:45:00Z")
    ) = TransitStopImportService(
        store,
        TransitProperties(gtfsUrl = server.url("/gtfs").toString(), minStations = minStations),
        metrics,
        buildProperties,
        Dispatchers.IO,
        Clock.fixed(at, ZoneOffset.UTC)
    )

    private fun lastSuccess(registry: SimpleMeterRegistry) = registry.find("importer.transit_stops.import.last_success").gauge()?.value()

    private fun zip(bytes: ByteArray) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/zip")
            .body(Buffer().write(bytes))
            .build()

    private suspend fun stored(): List<Pair<String, List<String>>> =
        databaseClient
            .sql("SELECT gtfs_stop_id, lines FROM events.transit_stop ORDER BY gtfs_stop_id")
            .map { row, _ ->
                requireNotNull(row.get("gtfs_stop_id", String::class.java)) to
                    requireNotNull(row.get("lines", Array<String>::class.java)).toList()
            }.flow()
            .toList()
}
