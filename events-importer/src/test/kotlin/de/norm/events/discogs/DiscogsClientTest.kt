package de.norm.events.discogs

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
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
 * The real [WebClient] pipeline against a local server: the request shape, the credentials header,
 * and what a 429, an error status or a dropped connection turns into.
 */
class DiscogsClientTest {
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

    private fun client(
        key: String = KEY,
        secret: String = SECRET
    ): DiscogsClient {
        // No pause between requests and no backoff, so the tests assert decisions rather than wait.
        val properties =
            DiscogsProperties(
                baseUrl = server.url("/").toString(),
                consumerKey = key,
                consumerSecret = secret,
                politeDelayMillis = 0,
                backoff = Duration.ZERO
            )
        val webClient =
            DiscogsHttpClientConfig().discogsWebClient(
                webClientBuilder = WebClient.builder(),
                properties = properties,
                buildProperties = DefaultListableBeanFactory().getBeanProvider(BuildProperties::class.java)
            )
        return DiscogsClient(webClient, properties)
    }

    private fun fixture(name: String) = javaClass.getResource("/discogs/$name")!!.readText()

    private fun json(
        body: String,
        remaining: Int = 59
    ) = MockResponse
        .Builder()
        .code(200)
        .addHeader("Content-Type", "application/json")
        .addHeader("X-Discogs-Ratelimit-Remaining", remaining.toString())
        .body(body)
        .build()

    private fun status(code: Int) =
        MockResponse
            .Builder()
            .code(code)
            .build()

    @Test
    fun `asks the artist search with the consumer credentials, a User-Agent and 25 candidates`() =
        runTest {
            server.enqueue(json(fixture("search-okkyung-lee.json")))

            val candidates = client().search("Okkyung Lee")

            candidates.first() shouldBe DiscogsCandidate(id = 130715, type = "artist", title = "Okkyung Lee", uri = "/artist/130715-Okkyung-Lee")
            val recorded = server.takeRequest()
            recorded.target shouldBe "/database/search?q=Okkyung%20Lee&type=artist&per_page=25"
            recorded.headers["Authorization"] shouldBe "Discogs key=$KEY, secret=$SECRET"
            recorded.headers["User-Agent"]!! shouldStartWith "event-junkie/dev ( https://github.com/enorm-labs/event-junkie )"
            recorded.headers["Accept"] shouldBe "application/json"
        }

    @Test
    fun `encodes a name that carries query syntax`() =
        runTest {
            server.enqueue(json("""{"results": []}"""))

            client().search("Simon & Garfunkel + 1")

            server.takeRequest().target shouldBe "/database/search?q=Simon%20%26%20Garfunkel%20%2B%201&type=artist&per_page=25"
        }

    @Test
    fun `no results is an empty list`() =
        runTest {
            server.enqueue(json(fixture("search-sonic-morgue.json")))

            client().search("Sonic Morgue").shouldBeEmpty()
        }

    @Test
    fun `sends no Authorization header without both credentials`() =
        runTest {
            server.enqueue(json("""{"results": []}"""))

            client(secret = "").search("Barker")

            server.takeRequest().headers["Authorization"].shouldBeNull()
        }

    @Test
    fun `retries a 429 and returns the answer that follows`() =
        runTest {
            server.enqueue(status(429))
            server.enqueue(json(fixture("search-okkyung-lee.json")))

            client().search("Okkyung Lee").first().id shouldBe 130715L
            server.requestCount shouldBe 2
        }

    @Test
    fun `gives up after the retries and names neither credential`() =
        runTest {
            repeat(4) { server.enqueue(status(429)) }

            val error = shouldThrow<DiscogsUnavailableException> { client().search("Barker") }

            server.requestCount shouldBe 4
            error.message!! shouldNotContain KEY
            error.message!! shouldNotContain SECRET
        }

    @Test
    fun `an error status is unavailable, not a verdict, and names neither credential`() =
        runTest {
            server.enqueue(status(401))

            val error = shouldThrow<DiscogsUnavailableException> { client().search("Barker") }

            error.message shouldBe "Discogs answered 401 for 'Barker'"
            error.message!! shouldNotContain KEY
        }

    @Test
    fun `a dropped connection is unavailable`() =
        runTest {
            server.close()

            shouldThrow<DiscogsUnavailableException> { client().search("Barker") }
        }

    @Test
    fun `the properties never print a credential`() {
        val printed = DiscogsProperties(consumerKey = KEY, consumerSecret = SECRET).toString()

        printed shouldNotContain KEY
        printed shouldNotContain SECRET
    }

    private companion object {
        const val KEY = "test-consumer-key"
        const val SECRET = "test-consumer-secret"
    }
}
