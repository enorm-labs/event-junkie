package de.norm.events.dataquality.flyer

import de.norm.events.BaseControllerTest
import de.norm.events.dataquality.classification.AnthropicResponses.textResponse
import de.norm.events.dataquality.classification.ClassificationProperties
import de.norm.events.dataquality.classification.ClassificationTokenLedger
import de.norm.events.dataquality.classification.EventClassificationRepository
import de.norm.events.image.ImgproxyClient
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.r2dbc.core.await
import org.springframework.r2dbc.core.flow
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The flyer-lineup check against a real database, a mocked model and a stubbed imgproxy (#5048).
 *
 * The shared context runs with the check off, so each test builds it by hand, as `EventClassificationCheckTest` does.
 */
class FlyerLineupCheckTest : BaseControllerTest() {
    @Autowired
    private lateinit var template: R2dbcEntityTemplate

    private lateinit var server: MockWebServer
    private lateinit var registry: SimpleMeterRegistry
    private val imgproxy = mockk<ImgproxyClient>()
    private var venueId = 0L

    @BeforeEach
    fun setUp(): Unit =
        runBlocking {
            server = MockWebServer()
            server.start()
            registry = SimpleMeterRegistry()
            venueId = insertVenue()
            insertSource(SOURCE)
            insertSource(OTHER_SOURCE)
            coEvery { imgproxy.render(any(), any(), any()) } returns IMAGE
        }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    @Test
    fun `a music night with no act gets its acts and start time from the flyer, and the image goes to the model`(): Unit =
        runBlocking {
            val event = insertEvent("Savory", hash = "h1")
            server.enqueue(flyerAnswer())

            val result = check().pass()

            result.read shouldBe 1
            result.suggested shouldBe mapOf("artists" to 1, "startTime" to 1)
            suggestions(event) shouldContainExactly
                listOf(
                    Row("artists", null, "Lukr Range; Taube; Wan", "LUKR RANGE\nTAUBE\nWAN"),
                    Row("startTime", null, "23:00", "23:00 — END")
                )
            val body =
                server
                    .takeRequest()
                    .body
                    ?.utf8()
                    .orEmpty()
            withClue("the image travels as a base64 block beside the event as data") {
                body shouldContain "\"type\":\"image\""
                body shouldContain "\"eventDate\\\":\\\"${event.date}\\\""
            }
            coVerify { imgproxy.render("h1", 1024, "jpg") }
            registry.counter("importer.data_quality.suggestions", "check", "flyer-lineup", "field", "artists").count() shouldBe 1.0
            registry.counter("importer.data_quality.flyer_reads", "outcome", "answered").count() shouldBe 1.0
        }

    @Test
    fun `a flyer read once is not read again, and a new image for the night is`(): Unit =
        runBlocking {
            val event = insertEvent("Savory", hash = "h1")
            server.enqueue(flyerAnswer())
            check().pass()

            check().pass().candidates shouldBe 0

            setImage(event.id, "h2")
            server.enqueue(flyerAnswer())
            check().pass().read shouldBe 1
            server.requestCount shouldBe 2
        }

    @Test
    fun `the allowlist, the type, an act, a pinned lineup, a withheld image and a cancelled night each keep an event out`(): Unit =
        runBlocking {
            insertEvent("Elsewhere", hash = "a", source = OTHER_SOURCE)
            insertEvent("Comedy night", hash = "b", type = "COMEDY")
            insertEvent("Billed", hash = "c").also { linkArtist(it.id, "Taube") }
            insertEvent("Pinned", hash = "d", pinned = "{lineup}")
            insertEvent("Withheld", hash = "e", withheld = true)
            insertEvent("Cancelled", hash = "f", status = "CANCELLED")

            check().pass().candidates shouldBe 0

            server.requestCount shouldBe 0
        }

    @Test
    fun `an image imgproxy cannot render is a failure, and the night stays a candidate`(): Unit =
        runBlocking {
            insertEvent("Savory", hash = "h1")
            coEvery { imgproxy.render(any(), any(), any()) } returns null

            check().pass().failed shouldBe 1

            server.requestCount shouldBe 0
            coEvery { imgproxy.render(any(), any(), any()) } returns IMAGE
            server.enqueue(flyerAnswer())
            check().pass().read shouldBe 1
        }

    @Test
    fun `a night with a start time gets only the acts`(): Unit =
        runBlocking {
            val event = insertEvent("Savory", hash = "h1", startTime = "22:00")
            server.enqueue(flyerAnswer())

            check().pass()

            suggestions(event).map { it.field } shouldContainExactly listOf("artists")
        }

    @Test
    fun `a flyer that names another day proposes nothing and is not read again`(): Unit =
        runBlocking {
            val event = insertEvent("Savory", hash = "h1")
            server.enqueue(flyerAnswer(text = SAVORY.replace("{day}", "1 JANUARY 2030")))

            check().pass().rejected shouldBe 1

            suggestions(event).shouldBeEmpty()
            check().pass().candidates shouldBe 0
        }

    @Test
    fun `a spent month reads no flyer`(): Unit =
        runBlocking {
            insertEvent("Savory", hash = "h1")

            check(budget = 0).pass().overBudget shouldBe 1

            server.requestCount shouldBe 0
        }

    private fun check(budget: Long = 3_000_000): FlyerLineupCheck {
        val classification =
            ClassificationProperties(
                enabled = true,
                apiKey = "test-key",
                maxRetries = 0,
                timeout = Duration.ofSeconds(10),
                monthlyTokenBudget = budget
            )
        val reader = AnthropicFlyerReader(classification, registry, Dispatchers.IO, server.url("/").toString().trimEnd('/'))
        return FlyerLineupCheck(
            FlyerRepository(template),
            EventClassificationRepository(template),
            reader,
            imgproxy,
            ClassificationTokenLedger(template, classification, registry),
            FlyerProperties(enabled = true, sources = listOf(SOURCE, " ")),
            classification,
            registry
        )
    }

    private data class Event(
        val id: Long,
        val date: LocalDate
    )

    private data class Row(
        val field: String,
        val stored: String?,
        val proposed: String,
        val evidence: String
    )

    private fun flyerAnswer(text: String = SAVORY): mockwebserver3.MockResponse =
        textResponse(
            mapper.writeValueAsString(
                mapOf(
                    "flyerText" to text.replace("{day}", dayOf(NIGHT)),
                    "acts" to mapOf("value" to listOf("Lukr Range", "Taube", "Wan"), "evidence" to "LUKR RANGE\nTAUBE\nWAN", "confidence" to 0.95),
                    "startTime" to mapOf("value" to "23:00", "evidence" to "23:00 — END", "confidence" to 0.9)
                )
            )
        )

    private suspend fun suggestions(event: Event): List<Row> =
        databaseClient
            .sql(
                "SELECT field, stored_value, proposed_value, evidence FROM events.event_suggestion " +
                    "WHERE event_id = ${event.id} AND check_name = 'flyer-lineup' AND status = 'OPEN' ORDER BY field"
            ).map { row, _ ->
                Row(
                    row.get("field", String::class.java)!!,
                    row.get("stored_value", String::class.java),
                    row.get("proposed_value", String::class.java)!!,
                    row.get("evidence", String::class.java)!!
                )
            }.flow()
            .toList()

    private suspend fun insertVenue(): Long =
        databaseClient
            .sql("INSERT INTO events.venue (name, slug, address, city, postal_code) VALUES ('v', 'v', 'Somewhere 1', 'Berlin', '10999') RETURNING id")
            .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    private suspend fun insertSource(slug: String) {
        databaseClient
            .sql(
                "INSERT INTO events.event_source (venue_id, name, slug, url, source_type) " +
                    "VALUES ($venueId, '$slug', '$slug', 'https://$slug.example/events', 'CASSIOPEIA')"
            ).await()
    }

    @Suppress("LongParameterList") // One flag per way a night leaves the candidates.
    private suspend fun insertEvent(
        title: String,
        hash: String,
        source: String = SOURCE,
        type: String = "PARTY",
        startTime: String? = null,
        pinned: String = "{}",
        withheld: Boolean = false,
        status: String = "SCHEDULED"
    ): Event {
        val slug = "e-" + title.lowercase().replace(Regex("[^a-z0-9]+"), "-")
        val imageUrl = "https://$source.example/$slug.jpg"
        val id =
            databaseClient
                .sql(
                    """
                    INSERT INTO events.event (venue_id, event_source_id, title, event_type, slug, event_date, start_time, source_id,
                                              image_url, image_withheld, pinned_fields, status)
                    SELECT $venueId, s.id, :title, :type, :slug, CAST(:date AS date), CAST(:startTime AS time), :slug,
                           :imageUrl, $withheld, CAST(:pinned AS text[]), :status
                    FROM events.event_source s WHERE s.slug = :source
                    RETURNING id
                    """.trimIndent()
                ).bind("title", title)
                .bind("type", type)
                .bind("slug", slug)
                .bind("date", NIGHT.toString())
                .let { if (startTime == null) it.bindNull("startTime", String::class.java) else it.bind("startTime", startTime) }
                .bind("imageUrl", imageUrl)
                .bind("pinned", pinned)
                .bind("status", status)
                .bind("source", source)
                .map { row, _ -> row.get("id", Number::class.java)!!.toLong() }
                .one()
                .awaitSingle()
        databaseClient
            .sql("INSERT INTO events.cached_image (source_url, content_hash) VALUES (:url, :hash)")
            .bind("url", imageUrl)
            .bind("hash", hash)
            .await()
        return Event(id, NIGHT)
    }

    private suspend fun setImage(
        eventId: Long,
        hash: String
    ) {
        val url = "https://$SOURCE.example/new-$eventId.jpg"
        databaseClient.sql("UPDATE events.event SET image_url = :url WHERE id = $eventId").bind("url", url).await()
        databaseClient
            .sql("INSERT INTO events.cached_image (source_url, content_hash) VALUES (:url, :hash)")
            .bind("url", url)
            .bind("hash", hash)
            .await()
    }

    private suspend fun linkArtist(
        eventId: Long,
        name: String
    ) {
        databaseClient
            .sql(
                "WITH a AS (INSERT INTO events.artist (name, slug) VALUES (:name, :slug) RETURNING id) " +
                    "INSERT INTO events.event_artist (event_id, artist_id) SELECT $eventId, id FROM a"
            ).bind("name", name)
            .bind("slug", name.lowercase())
            .await()
    }

    private companion object {
        const val SOURCE = "flyervenue"
        const val OTHER_SOURCE = "othervenue"

        /** A week ahead, so the night is upcoming whatever day the test runs. */
        val NIGHT: LocalDate = LocalDate.now(ZoneOffset.UTC).plusDays(7)

        /** Bytes of a JPEG's start. The model is mocked, so they need only travel. */
        val IMAGE = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

        val mapper: JsonMapper = JsonMapper.builder().build()

        val SAVORY =
            """
            SAVORY
            {day}
            FREE ENTRY UNTIL 00:30
            23:00 — END
            LINE UP A—Z
            LUKR RANGE
            TAUBE
            WAN
            """.trimIndent()

        fun dayOf(date: LocalDate): String = "${date.dayOfMonth} ${date.month.name} ${date.year}"
    }
}
