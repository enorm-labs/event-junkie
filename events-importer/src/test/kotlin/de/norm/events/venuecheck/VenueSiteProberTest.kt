package de.norm.events.venuecheck

import de.norm.events.scraper.RobotsDisallowedException
import de.norm.events.scraper.RobotsRulesCache
import de.norm.events.scraper.ScraperHttpClientConfig
import de.norm.events.scraper.ScraperProperties
import io.kotest.matchers.shouldBe
import io.netty.channel.ConnectTimeoutException
import kotlinx.coroutines.test.runTest
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.net.ConnectException
import java.net.ServerSocket
import java.net.UnknownHostException
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import javax.net.ssl.SSLHandshakeException

/**
 * [VenueSiteProber] against a local server, through the same `robots.txt`-checking client the importer uses.
 */
class VenueSiteProberTest {
    private lateinit var server: MockWebServer
    private val paths = CopyOnWriteArrayList<String>()

    /** The answer per path; a path not listed is a 200, and `robots.txt` a 404. */
    private val routes = mutableMapOf<String, MockResponse>()

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.target
                    paths += path
                    return routes[path]
                        ?: if (path == "/robots.txt") {
                            MockResponse.Builder().code(404).build()
                        } else {
                            MockResponse
                                .Builder()
                                .code(200)
                                .body("<html></html>")
                                .build()
                        }
                }
            }
        server.start()
    }

    @AfterEach
    fun stopServer() = server.close()

    @Test
    fun `a page that answers is OK, after a redirect too`() =
        runTest {
            routes["/old"] =
                MockResponse
                    .Builder()
                    .code(301)
                    .addHeader("Location", url("/new"))
                    .build()

            prober().probe(url("/")) shouldBe ProbeResult(url("/"), SiteOutcome.OK, 200)
            prober().probe(url("/old")) shouldBe ProbeResult(url("/old"), SiteOutcome.OK, 200)
        }

    @Test
    fun `a 4xx or 5xx is an HTTP failure with its status`() =
        runTest {
            routes["/gone"] = MockResponse.Builder().code(404).build()
            routes["/removed"] = MockResponse.Builder().code(410).build()
            routes["/error"] = MockResponse.Builder().code(500).build()
            routes["/broken"] = MockResponse.Builder().code(503).build()

            prober().probe(url("/gone")) shouldBe ProbeResult(url("/gone"), SiteOutcome.HTTP, 404)
            prober().probe(url("/removed")) shouldBe ProbeResult(url("/removed"), SiteOutcome.HTTP, 410)
            prober().probe(url("/error")) shouldBe ProbeResult(url("/error"), SiteOutcome.HTTP, 500)
            prober().probe(url("/broken")) shouldBe ProbeResult(url("/broken"), SiteOutcome.HTTP, 503)
        }

    @Test
    fun `a 403 or 429 is skipped with its status, not a failure`() =
        runTest {
            routes["/forbidden"] = MockResponse.Builder().code(403).build()
            routes["/limited"] = MockResponse.Builder().code(429).build()

            val forbidden = prober().probe(url("/forbidden"))
            val limited = prober().probe(url("/limited"))

            forbidden shouldBe ProbeResult(url("/forbidden"), SiteOutcome.SKIPPED, 403)
            limited shouldBe ProbeResult(url("/limited"), SiteOutcome.SKIPPED, 429)
            forbidden.outcome.isFailure shouldBe false
            forbidden.isRefused shouldBe true
            limited.isRefused shouldBe true
        }

    @Test
    fun `a forbidden host is skipped without a request`() =
        runTest {
            prober().probe("https://ra.co/clubs/12345") shouldBe ProbeResult("https://ra.co/clubs/12345", SiteOutcome.SKIPPED)
            prober().probe("https://www.instagram.com/rso.berlin/").outcome shouldBe SiteOutcome.SKIPPED

            server.requestCount shouldBe 0
        }

    @Test
    fun `a robots txt disallow is skipped, and the page is never fetched`() =
        runTest {
            routes["/robots.txt"] =
                MockResponse
                    .Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/plain")
                    .body("User-agent: *\nDisallow: /\n")
                    .build()

            prober().probe(url("/")).outcome shouldBe SiteOutcome.SKIPPED

            paths shouldBe listOf("/robots.txt")
        }

    @Test
    fun `a robots txt that answers 5xx is a server failure`() =
        runTest {
            routes["/robots.txt"] = MockResponse.Builder().code(502).build()

            prober().probe(url("/")) shouldBe ProbeResult(url("/"), SiteOutcome.HTTP, 502)
        }

    @Test
    fun `a refused connection is a CONNECTION failure`() =
        runTest {
            val closedPort = ServerSocket(0).use { it.localPort }

            prober().probe("http://localhost:$closedPort/").outcome shouldBe SiteOutcome.CONNECTION
        }

    @Test
    fun `a transport fault is classified by its cause`() {
        val prober = prober()
        val url = "https://example.test/"

        prober.classify(url, RuntimeException(UnknownHostException("example.test"))).outcome shouldBe SiteOutcome.DNS
        prober.classify(url, RuntimeException(SSLHandshakeException("expired"))).outcome shouldBe SiteOutcome.TLS
        prober.classify(url, ConnectTimeoutException("slow")).outcome shouldBe SiteOutcome.TIMEOUT
        prober.classify(url, ConnectException("refused")).outcome shouldBe SiteOutcome.CONNECTION
        prober.classify(url, IllegalStateException("odd")).outcome shouldBe SiteOutcome.OTHER
        prober.classify(url, RobotsDisallowedException(url, "https://example.test/robots.txt")).outcome shouldBe SiteOutcome.SKIPPED
        // A 4xx on robots.txt never reaches here as a disallow; if one did, it says nothing about the site.
        prober.classify(url, RobotsDisallowedException(url, null, unreadableStatus = 403)).outcome shouldBe SiteOutcome.SKIPPED
    }

    @Test
    fun `a venue fails when any of its URLs fails, and is skipped when none could be fetched`() =
        runTest {
            routes["/programm"] = MockResponse.Builder().code(404).build()

            prober().probeVenue(listOf(url("/"), url("/programm"))) shouldBe ProbeResult(url("/programm"), SiteOutcome.HTTP, 404)
            prober().probeVenue(listOf(url("/"), "https://ra.co/clubs/1")) shouldBe ProbeResult(url("/"), SiteOutcome.OK, 200)
            prober().probeVenue(listOf(null, "https://ra.co/clubs/1")).outcome shouldBe SiteOutcome.SKIPPED
            prober().probeVenue(listOf(null, " ")).outcome shouldBe SiteOutcome.SKIPPED
        }

    @Test
    fun `a venue with a 403 fails on its other URL, answers on it, or is skipped with the 403`() =
        runTest {
            routes["/forbidden"] = MockResponse.Builder().code(403).build()
            routes["/programm"] = MockResponse.Builder().code(404).build()

            prober().probeVenue(listOf(url("/forbidden"), url("/programm"))) shouldBe ProbeResult(url("/programm"), SiteOutcome.HTTP, 404)
            prober().probeVenue(listOf(url("/forbidden"), url("/"))) shouldBe ProbeResult(url("/"), SiteOutcome.OK, 200)
            prober().probeVenue(listOf(url("/forbidden"), "https://ra.co/clubs/1")) shouldBe
                ProbeResult(url("/forbidden"), SiteOutcome.SKIPPED, 403)
        }

    private fun url(path: String) = server.url(path).toString()

    private fun prober(): VenueSiteProber {
        val properties = ScraperProperties(politeDelayMillis = 0, responseTimeout = Duration.ofSeconds(5))
        val config = ScraperHttpClientConfig()
        val connections = config.scraperConnectionProvider(properties)
        val throttle = config.perHostThrottlingFilter(properties)
        val base = config.scraperBaseWebClient(WebClient.builder(), properties, connections, throttle)
        val client =
            config.scraperWebClient(WebClient.builder(), properties, connections, throttle, RobotsRulesCache(base, properties))
        return VenueSiteProber(client)
    }
}
