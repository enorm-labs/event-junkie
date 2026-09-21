package de.norm.events.musicbrainz

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

/**
 * The real [WebClient] pipeline against a local server: the request shape MusicBrainz insists on,
 * and what a 503 or a dropped connection turns into.
 */
class MusicBrainzClientTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    private fun client(): MusicBrainzClient {
        // No pause between requests and a short backoff, so the tests assert decisions rather than wait.
        val properties = MusicBrainzProperties(baseUrl = server.url("/ws/2/").toString(), politeDelayMillis = 0, backoff = Duration.ZERO)
        val webClient =
            MusicBrainzHttpClientConfig().musicBrainzWebClient(
                webClientBuilder = WebClient.builder(),
                properties = properties,
                buildProperties = DefaultListableBeanFactory().getBeanProvider(BuildProperties::class.java)
            )
        return MusicBrainzClient(webClient, properties)
    }

    private fun json(body: String) =
        MockResponse
            .Builder()
            .code(200)
            .addHeader("Content-Type", "application/json")
            .body(body)
            .build()

    @Test
    fun `asks the artist search with the trailing slash, a quoted phrase, JSON and ten candidates`() =
        runTest {
            // Score, type and tags come along and are ignored; tags are the CC BY-NC-SA half.
            server.enqueue(
                json(
                    """
                    {"created": "now", "count": 1, "offset": 0, "artists": [
                      {"id": "abc", "name": "Accept", "sort-name": "Accept", "country": "DE", "score": 100, "type": "Group",
                       "tags": [{"name": "metal"}], "aliases": [{"name": "Akzept", "type": "Artist name"}]}
                    ]}
                    """.trimIndent()
                )
            )

            val candidates = client().search("Accept")

            candidates shouldBe
                listOf(MusicBrainzCandidate(id = "abc", name = "Accept", sortName = "Accept", country = "DE", aliases = listOf(MusicBrainzAlias("Akzept"))))
            val recorded = server.takeRequest()
            recorded.target shouldBe "/ws/2/artist/?query=artist%3A%22Accept%22&fmt=json&limit=10"
            recorded.headers["User-Agent"]!! shouldStartWith "event-junkie/dev ( https://github.com/enorm-labs/event-junkie )"
            recorded.headers["Accept"] shouldBe "application/json"
        }

    @Test
    fun `escapes the quote and the backslash that would end the phrase early`() =
        runTest {
            server.enqueue(json("""{"artists":[]}"""))

            client().search("""Say "Hi" \ Bye""") shouldHaveSize 0

            server.takeRequest().target shouldContain "query=artist%3A%22Say%20%5C%22Hi%5C%22%20%5C%5C%20Bye%22"
        }

    @Test
    fun `a 503 is retried three times, and a fourth one is unavailable`() =
        runTest {
            repeat(3) { server.enqueue(MockResponse.Builder().code(503).build()) }
            server.enqueue(json("""{"artists":[{"id":"x","name":"Pici Mazzei"}]}"""))

            client().search("Pici") shouldHaveSize 1
            server.requestCount shouldBe 4

            repeat(4) { server.enqueue(MockResponse.Builder().code(503).build()) }
            shouldThrow<MusicBrainzUnavailableException> { client().search("Pici") }.message shouldContain "4 times"
            server.requestCount shouldBe 8
        }

    @Test
    fun `any other error status is unavailable too, and never a parsed body`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(400)
                    .body("""{"error":"bad query"}""")
                    .build()
            )

            shouldThrow<MusicBrainzUnavailableException> { client().search("x") }.message shouldContain "400"
        }

    @Test
    fun `a closed server is unavailable rather than a stack trace`() =
        runTest {
            val client = client()
            server.close()

            shouldThrow<MusicBrainzUnavailableException> { client.search("x") }
        }

    @Test
    fun `reads one entity with its URL relationships, and a 404 is no entity rather than an error`() =
        runTest {
            server.enqueue(
                json(
                    javaClass.classLoader
                        .getResourceAsStream("musicbrainz/artist-klock.json")!!
                        .bufferedReader()
                        .readText()
                )
            )
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(404)
                    .body("""{"error":"Not Found"}""")
                    .build()
            )

            val klock = client().artist("mbid-klock")
            server.takeRequest().target shouldBe "/ws/2/artist/mbid-klock?inc=url-rels&fmt=json"
            klock?.name shouldBe "Ben Klock"
            klock?.type shouldBe "Person"
            klock?.relations?.count { it.type == "soundcloud" } shouldBe 1

            client().artist("mbid-klock").shouldBeNull()
        }

    @Test
    fun `an entity read is retried behind a 503 like a search`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(503).build())
            server.enqueue(json("""{"id":"x","name":"Pici","relations":[]}"""))

            client().artist("x")?.name shouldBe "Pici"
            server.requestCount shouldBe 2
        }

    @Test
    fun `a merged MBID is followed to its survivor`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(301)
                    .addHeader("Location", server.url("/ws/2/artist/survivor?inc=url-rels&fmt=json").toString())
                    .build()
            )
            server.enqueue(json("""{"id":"survivor","name":"Pici","relations":[]}"""))

            client().artist("merged")?.id shouldBe "survivor"
            server.requestCount shouldBe 2
        }
}
