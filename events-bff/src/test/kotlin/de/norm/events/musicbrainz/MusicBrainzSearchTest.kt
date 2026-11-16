package de.norm.events.musicbrainz

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.concurrent.TimeUnit

/** The MusicBrainz search for watches (#5214) against a local server: what goes out, the pace, the cache and the failures. */
class MusicBrainzSearchTest {
    private val server = MockWebServer().apply { start() }
    private val registry = SimpleMeterRegistry()
    private val search =
        MusicBrainzSearch(WebClient.builder(), registry, server.url("/ws/2/").toString(), PACE, Duration.ofSeconds(5), Duration.ofHours(24))

    @AfterEach
    fun stop() = server.close()

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    private fun found(vararg names: Pair<String, String>) =
        json(
            names.joinToString(",", """{"artists":[""", "]}") { (id, name) ->
                """{"id":"$id","name":"$name","type":"Group","country":"DE","life-span":{"begin":"1990"},"tags":[]}"""
            }
        )

    @Test
    fun `sends the term as a phrase on the name and the aliases, and nothing of the visitor`() =
        runBlocking<Unit> {
            server.enqueue(found(MODERAT to "Moderat"))

            search.search("  Moderat ").map { it.name } shouldContainExactly listOf("Moderat")

            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            request.url.encodedPath shouldBe "/ws/2/artist/"
            request.url.queryParameter("query") shouldBe "artist:\"moderat\" OR alias:\"moderat\""
            request.url.queryParameter("fmt") shouldBe "json"
            request.url.queryParameter("limit") shouldBe "10"
            request.headers["User-Agent"] shouldBe MusicBrainzSearch.USER_AGENT
            request.headers
                .names()
                .map { it.lowercase() }
                .filter { it in FORWARDED }
                .shouldContainExactly(emptyList())
        }

    @Test
    fun `answers a second search for the same term from the cache, whatever its case and spaces`() =
        runBlocking<Unit> {
            server.enqueue(found(MODERAT to "Moderat"))

            search.search("Moderat")
            search.search("moderat")
            search.search("  MODERAT  ")

            server.requestCount shouldBe 1
            registry.counter(MusicBrainzSearch.METRIC, "request", "search", "outcome", "cached").count() shouldBe 2.0
        }

    @Test
    fun `keeps two requests a pace apart`() =
        runBlocking<Unit> {
            server.enqueue(found())
            server.enqueue(found())

            val started = System.nanoTime()
            search.search("first act")
            search.search("second act")

            (System.nanoTime() - started) / 1_000_000 shouldBeGreaterThanOrEqual PACE.toMillis()
        }

    @Test
    fun `escapes the two characters that end a phrase`() {
        MusicBrainzSearch.query("A \"B\" \\ C") shouldBe "artist:\"a \\\"b\\\" \\\\ c\" OR alias:\"a \\\"b\\\" \\\\ c\""
    }

    @Test
    fun `reads one artist, and null for an id MusicBrainz does not know`() =
        runBlocking<Unit> {
            server.enqueue(json("""{"id":"$MODERAT","name":"Moderat","disambiguation":"Berlin electronic trio"}"""))
            server.enqueue(MockResponse.Builder().code(404).build())

            search.artist(MODERAT)!!.disambiguation shouldBe "Berlin electronic trio"
            search.artist(UNKNOWN).shouldBeNull()
            server.takeRequest(1, TimeUnit.SECONDS)!!.url.encodedPath shouldBe "/ws/2/artist/$MODERAT"
        }

    @Test
    fun `a 503 is unavailable at once, without a retry, and is not cached`() =
        runBlocking<Unit> {
            server.enqueue(MockResponse.Builder().code(503).build())
            server.enqueue(found(MODERAT to "Moderat"))

            shouldThrow<MusicBrainzUnavailableException> { search.search("Moderat") }
            server.requestCount shouldBe 1
            search.search("Moderat").map { it.name } shouldContainExactly listOf("Moderat")
        }

    private companion object {
        val PACE: Duration = Duration.ofMillis(300)
        const val MODERAT = "2d2a7d9e-6cf0-4f2f-9d5c-4b9b1e3a7f10"
        const val UNKNOWN = "00000000-0000-4000-8000-000000000000"
        val FORWARDED = setOf("x-forwarded-for", "forwarded", "x-real-ip", "cookie", "authorization", "referer")
    }
}
