package de.norm.events.image

import de.norm.events.scraper.ScraperHttpClientConfig
import de.norm.events.scraper.ScraperProperties
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
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * [ImageFetcher] against a real [WebClient] and a local HTTP server.
 *
 * **The limits here are security controls, not tuning** (ADR-019 §4). The bytes come from a venue we
 * do not control, so each assertion is a thing a hostile or merely broken response could do.
 *
 * The URL guard is stubbed permissive on purpose: [MockWebServer] listens on loopback, which
 * [ImageUrlValidator] refuses by design. That refusal has its own suite in [ImageUrlValidatorTest],
 * and asserting it twice here would only mean this file could never reach the code it is testing.
 */
class ImageFetcherTest {
    private lateinit var server: MockWebServer

    private fun fetcher(properties: ImageProperties = ImageProperties(fetchEnabled = true)) =
        ImageFetcher(
            webClient = testWebClient(),
            ioDispatcher = Dispatchers.IO,
            validator =
                object : ImageUrlValidator() {
                    override fun reject(url: String): String? = null
                },
            properties = properties
        )

    /** Permits loopback, which the real guard refuses by design. See this class's KDoc. */
    private fun permissiveValidator() =
        object : ImageUrlValidator() {
            override fun reject(url: String): String? = null
        }

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() = server.close()

    @Test
    fun `describes a PNG it fetched`() =
        runTest {
            val png = pngBytes(width = 40, height = 25)
            server.enqueue(imageResponse(png, etag = "\"abc\""))

            val result = fetcher().fetch(url())

            result.shouldBeInstanceOf<ImageFetchResult.Success>()
            result.contentType shouldBe "image/png"
            result.width shouldBe 40
            result.height shouldBe 25
            result.byteSize shouldBe png.size.toLong()
            result.etag shouldBe "\"abc\""
            // The hash is the storage key from PR 4 onward, so it has to be of the bytes and
            // nothing else. Two events sharing a poster converge on one object because of this.
            result.contentHash shouldBe sha256Hex(png)
        }

    @Test
    fun `refuses an SVG however it is labelled`() =
        runTest {
            // The stored-XSS case. An SVG served from our origin runs script in our origin, and the
            // venue controls the Content-Type header, so only the bytes can decide.
            val svg = """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script></svg>""".toByteArray()
            server.enqueue(imageResponse(svg, contentType = "image/png"))

            val result = fetcher().fetch(url())

            result.shouldBeInstanceOf<ImageFetchResult.Rejected>()
            result.reason shouldContain "not an allowed image type"
        }

    @Test
    fun `refuses a body larger than the cap`() =
        runTest {
            server.enqueue(imageResponse(pngBytes(width = 200, height = 200)))

            val result = fetcher(ImageProperties(fetchEnabled = true, maxBytes = 64)).fetch(url())

            result.shouldBeInstanceOf<ImageFetchResult.Rejected>()
            result.reason shouldContain "bytes"
        }

    @Test
    fun `refuses more pixels than the cap allows`() =
        runTest {
            // A decompression bomb is small on the wire and enormous decoded, so the byte cap does
            // not catch it. The dimensions are read from the header without decoding.
            server.enqueue(imageResponse(pngBytes(width = 100, height = 100)))

            val result = fetcher(ImageProperties(fetchEnabled = true, maxPixels = 100)).fetch(url())

            result.shouldBeInstanceOf<ImageFetchResult.Rejected>()
            result.reason shouldContain "pixels"
        }

    @Test
    fun `reports 304 as unchanged rather than as a failure`() =
        runTest {
            server.enqueue(MockResponse.Builder().code(304).build())

            fetcher().fetch(url(), etag = "\"abc\"") shouldBe ImageFetchResult.NotModified
        }

    @Test
    fun `turns a 404 into a reason rather than an exception`() =
        runTest {
            // It has to become a negative-cache row. A throw here would stop the whole pass on one
            // dead link, and the link would be requested again the next night regardless.
            server.enqueue(MockResponse.Builder().code(404).build())

            val result = fetcher().fetch(url())

            result.shouldBeInstanceOf<ImageFetchResult.Rejected>()
            result.reason shouldContain "404"
        }

    @Test
    fun `accepts a WebP even though the JVM cannot measure it`() =
        runTest {
            // The bug this test exists for: `sniff` allowed WebP while `readDimensions` could not
            // read one, so every WebP was refused as an unreadable header. A stock JDK ships no
            // WebP or AVIF reader, and adding one would put a decoder for untrusted bytes back
            // inside this process — the thing ADR-020 moved out. imgproxy measures it instead.
            server.enqueue(imageResponse(webpHeaderBytes(), contentType = "image/webp"))

            val result = fetcher().fetch(url())

            result.shouldBeInstanceOf<ImageFetchResult.Success>()
            result.contentType shouldBe "image/webp"
            result.width shouldBe null
            result.height shouldBe null
        }

    @Test
    fun `still refuses a PNG whose header will not parse`() =
        runTest {
            // The other half. A type the JVM *can* read, that it then cannot, is a corrupt file and
            // must still be refused — otherwise making dimensions optional would admit anything
            // wearing a PNG magic number.
            val corrupt = ByteArray(64).also { PNG_MAGIC_BYTES.copyInto(it) }
            server.enqueue(imageResponse(corrupt))

            val result = fetcher().fetch(url())

            result.shouldBeInstanceOf<ImageFetchResult.Rejected>()
            result.reason shouldContain "unreadable image header"
        }

    @Test
    fun `refuses on the declared Content-Length before reading the body`() =
        runTest {
            // The cheap defence: refuse on the header rather than after the download. MockWebServer
            // sets Content-Length from the body it is given, so the body itself has to exceed the
            // cap — which is exactly the honest case this branch is for.
            server.enqueue(imageResponse(pngBytes(width = 200, height = 200)))

            val result = fetcher(ImageProperties(fetchEnabled = true, maxBytes = 64)).fetch(url())

            result.shouldBeInstanceOf<ImageFetchResult.Rejected>()
            result.reason shouldContain "declared larger than"
        }

    @Test
    fun `reports the codec buffer limit as a size refusal, not a transport fault`() =
        runTest {
            // The bug this pins: `maxBytes` defaults to the same 8MB as
            // `spring.http.codecs.max-in-memory-size`, so the codec throws before `describe` ever
            // measures anything. Without the translation the row reads
            // "fetch failed: DataBufferLimitException", and an operator debugging a missing image
            // goes looking at the network.
            val tinyBuffer =
                WebClient
                    .builder()
                    .codecs { it.defaultCodecs().maxInMemorySize(1024) }
                    .build()
            val fetcher =
                ImageFetcher(
                    webClient = tinyBuffer,
                    ioDispatcher = Dispatchers.IO,
                    validator = permissiveValidator(),
                    properties = ImageProperties(fetchEnabled = true)
                )
            // Four kilobytes against a one-kilobyte codec limit. The content is irrelevant: the
            // codec throws at `awaitBody`, before anything sniffs or measures it.
            server.enqueue(imageResponse(ByteArray(4096)))

            val result = fetcher.fetch(url())

            result.shouldBeInstanceOf<ImageFetchResult.Rejected>()
            result.reason shouldContain "buffer limit"
        }

    @Test
    fun `sends an already-encoded URL verbatim rather than re-encoding it`() =
        runTest {
            // Frannz Club proxies every image through `images.copilot.events/resize?url=…` with the
            // whole target percent-encoded in the query. Passing that to `uri(String)` treats it as
            // a URI template and turns `%3A` into `%253A`, which the proxy answers 400. Twenty-two
            // images failed that way on staging before this was fixed. HtmlFetcher carries the same
            // guard, from the same class of bug on a venue's non-ASCII slugs.
            server.enqueue(imageResponse(pngBytes(width = 8, height = 8)))
            val encoded = "${url().substringBefore("/poster.png")}/resize?url=https%3A%2F%2Fexample.test%2Fa.jpg"

            fetcher().fetch(encoded).shouldBeInstanceOf<ImageFetchResult.Success>()

            val requested = server.takeRequest().target
            requested shouldContain "url=https%3A%2F%2Fexample.test%2Fa.jpg"
            // The failure this pins: a re-encoded '%' arrives as %25.
            requested shouldNotContain "%253A"
        }

    @Test
    fun `fetches a URL whose filename contains a literal space`() =
        runTest {
            // Wild at Heart names files `R1783504681V8 Wankers.jpeg`. A browser encodes the space
            // and fetches them; `URI` rejects the raw string, so 19 of them were recorded as
            // malformed URLs and never fetched. Encoding only the space cannot double-encode
            // anything, because a space is never legal in a URI to begin with.
            server.enqueue(imageResponse(pngBytes(width = 8, height = 8)))
            val spaced = "${url().substringBefore("/poster.png")}/img/R178 Wankers.jpeg"

            fetcher().fetch(spaced).shouldBeInstanceOf<ImageFetchResult.Success>()

            server.takeRequest().target shouldContain "R178%20Wankers.jpeg"
        }

    @Test
    fun `refuses a body that is not an image at all`() =
        runTest {
            server.enqueue(imageResponse("<html><body>Not found</body></html>".toByteArray()))

            fetcher().fetch(url()).shouldBeInstanceOf<ImageFetchResult.Rejected>()
        }

    /** A minimal RIFF/WEBP header: enough for the sniffer, not a decodable image. */
    private fun webpHeaderBytes(): ByteArray =
        "RIFF".toByteArray(Charsets.US_ASCII) +
            byteArrayOf(0, 0, 0, 0) +
            "WEBPVP8 ".toByteArray(Charsets.US_ASCII) +
            ByteArray(32)

    private fun url() = server.url("/poster.png").toString()

    private fun imageResponse(
        body: ByteArray,
        contentType: String = "image/png",
        etag: String? = null
    ) = MockResponse
        .Builder()
        .code(200)
        .setHeader("Content-Type", contentType)
        .apply { etag?.let { setHeader("ETag", it) } }
        .body(Buffer().write(body))
        .build()

    private fun pngBytes(
        width: Int,
        height: Int
    ): ByteArray =
        ByteArrayOutputStream()
            .also { ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it) }
            .toByteArray()

    private fun sha256Hex(bytes: ByteArray): String =
        java.security.MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}

private val PNG_MAGIC_BYTES = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

private fun testWebClient(): WebClient {
    val properties = ScraperProperties(politeDelayMillis = 0)
    val config = ScraperHttpClientConfig()
    return config.scraperBaseWebClient(
        webClientBuilder = WebClient.builder(),
        scraperProperties = properties,
        throttle = config.perHostThrottlingFilter(properties)
    )
}
