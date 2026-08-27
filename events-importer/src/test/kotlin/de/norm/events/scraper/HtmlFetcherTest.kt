package de.norm.events.scraper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

/**
 * Tests for [HtmlFetcher] that exercise the real Spring [WebClient] request
 * pipeline against a local [MockWebServer]. Verifies URL handling, conditional
 * requests and error propagation end to end — no HTTP mocking of the client.
 */
class HtmlFetcherTest {
    private lateinit var server: MockWebServer

    private val fetcher: HtmlFetcher by lazy {
        HtmlFetcher(
            // The real shared bean, built from the production config — no politeness delay so
            // tests aren't slowed by the per-host throttle.
            webClient = testScraperWebClient(),
            ioDispatcher = Dispatchers.IO
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

    @Nested
    inner class PreEncodedUrls {
        // The exact slug from the Badehaus regression: an already percent-encoded Arabic
        // title. Passing it as a WebClient URI *template* would re-encode the '%' signs
        // (%d8 -> %25d8), double-encoding the path into a 404 on the origin server.
        private val encodedPath =
            "/events/sahra-party-%d8%ad%d9%81%d9%84%d8%a9-%d8%b3%d9%87%d8%b1%d8%a9-pride-of-arab-women/"

        @Test
        fun `fetchDocument sends an already-encoded path verbatim without double-encoding`() =
            runTest {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body("<html><body>ok</body></html>")
                        .build()
                )

                fetcher.fetchDocument(baseUrl() + encodedPath)

                val recorded = server.takeRequest()
                recorded.target shouldBe encodedPath
                // Guards against the regression specifically: no '%' was re-escaped to '%25'.
                recorded.target shouldNotContain "%25"
            }

        @Test
        fun `fetch sends an already-encoded path verbatim without double-encoding`() =
            runTest {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body("<html><body>ok</body></html>")
                        .build()
                )

                fetcher.fetch(baseUrl() + encodedPath)

                val recorded = server.takeRequest()
                recorded.target shouldBe encodedPath
                recorded.target shouldNotContain "%25"
            }
    }

    @Nested
    inner class RawBodyFetching {
        @Test
        fun `fetchHtml returns the response body verbatim`() =
            runTest {
                val body = "<html><body>ok</body></html>"
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .body(body)
                        .build()
                )

                fetcher.fetchHtml(baseUrl() + "/page") shouldBe body
            }
    }

    @Nested
    inner class CharacterEncoding {
        /** "Eddie & die Bäänd - freie Bühne" — umlauts a naive UTF-8 decode of Latin-1 bytes would destroy. */
        private val germanTitle = "Eddie & die Bäänd - freie Bühne"

        private fun latin1Page(metaCharset: String) =
            Buffer().write(
                """<html><head><meta http-equiv="Content-Type" content="text/html; charset=$metaCharset">""".toByteArray() +
                    "</head><body><h1>$germanTitle</h1></body></html>".toByteArray(Charsets.ISO_8859_1)
            )

        @Test
        fun `fetch decodes a Latin-1 page declared only by its meta tag`() =
            runTest {
                // The retro Arcanoa host sends "Content-Type: text/html" with no charset parameter,
                // so the encoding is knowable only from the document's own meta tag.
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .setHeader("Content-Type", "text/html")
                        .body(latin1Page("iso-8859-1"))
                        .build()
                )

                val result = fetcher.fetch(baseUrl() + "/veranst.htm")

                result.shouldBeInstanceOf<FetchResult.Success>()
                result.document.selectFirst("h1")!!.text() shouldBe germanTitle
            }

        @Test
        fun `fetch prefers the Content-Type charset over the meta tag`() =
            runTest {
                // A stale meta tag must not override what the server actually declares.
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .setHeader("Content-Type", "text/html; charset=ISO-8859-1")
                        .body(latin1Page("utf-8"))
                        .build()
                )

                val result = fetcher.fetch(baseUrl() + "/veranst.htm")

                result.shouldBeInstanceOf<FetchResult.Success>()
                result.document.selectFirst("h1")!!.text() shouldBe germanTitle
            }

        @Test
        fun `fetchDocument decodes a Latin-1 page declared only by its meta tag`() =
            runTest {
                server.enqueue(
                    MockResponse
                        .Builder()
                        .code(200)
                        .setHeader("Content-Type", "text/html")
                        .body(latin1Page("iso-8859-1"))
                        .build()
                )

                val document = fetcher.fetchDocument(baseUrl() + "/veranst.htm")

                document.selectFirst("h1")!!.text() shouldBe germanTitle
            }
    }

    @Nested
    inner class ErrorHandling {
        @Test
        fun `fetchHtml throws HttpFetchException on a 404`() =
            runTest {
                server.enqueue(MockResponse.Builder().code(404).build())

                val url = baseUrl() + "/missing"
                val exception =
                    shouldThrow<HttpFetchException> {
                        fetcher.fetchHtml(url)
                    }

                exception.message!! shouldContain "HTTP 404"
                exception.message!! shouldContain url
            }
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
