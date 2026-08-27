package de.norm.events.scraper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

/**
 * Tests for [ApiClient] that exercise the real Spring [WebClient] request pipeline against a
 * local [MockWebServer]. Verifies raw-body return, verbatim URL handling, and error
 * propagation end to end — no HTTP mocking of the client.
 */
class ApiClientTest {
    private lateinit var server: MockWebServer

    private val apiClient: ApiClient by lazy {
        ApiClient(
            // The real shared bean, built from the production config — no politeness delay so
            // tests aren't slowed by the per-host throttle.
            webClient = testScraperWebClient()
        )
    }

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() {
        server.close()
    }

    /** Base URL of the mock server without a trailing slash, e.g. `http://localhost:12345`. */
    private fun baseUrl(): String = server.url("/").toString().trimEnd('/')

    @Test
    fun `fetchJson returns the response body verbatim for a JSON payload`() =
        runTest {
            val body = """{"items":[{"title":"ok"}]}"""
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(body)
                    .build()
            )

            apiClient.fetchJson(baseUrl() + "/api") shouldBe body
        }

    @Test
    fun `fetchJson sends the query string verbatim without re-encoding`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("{}")
                    .build()
            )

            // The Elfsight boot URL carries the widget id as a query parameter; it must arrive intact.
            val path = "/p/boot/?w=e767cbbe-0026-4173-a511-5aaa105ed563"
            apiClient.fetchJson(baseUrl() + path)

            val recorded = server.takeRequest()
            recorded.target shouldBe path
            recorded.target shouldNotContain "%25"
        }

    @Test
    fun `fetchJson throws HttpFetchException on a 404`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(404).build())

            val url = baseUrl() + "/missing"
            val exception =
                shouldThrow<HttpFetchException> {
                    apiClient.fetchJson(url)
                }

            exception.message!! shouldContain "HTTP 404"
            exception.message!! shouldContain url
        }
}

/**
 * The production scraper client minus [RobotsTxtFilter].
 *
 * The filter is covered by its own test. Including it here would put a `robots.txt` round trip in
 * front of every request, and these tests assert the request the fetcher itself made — against a
 * [MockWebServer] that answers whatever was enqueued next.
 *
 * No politeness delay, so the per-host throttle does not slow the suite down.
 */
private fun testScraperWebClient(): WebClient {
    val properties = ScraperProperties(politeDelayMillis = 0)
    val config = ScraperHttpClientConfig()
    return config.scraperBaseWebClient(
        webClientBuilder = WebClient.builder(),
        scraperProperties = properties,
        throttle = config.perHostThrottlingFilter(properties)
    )
}
