package de.norm.events.image

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient

/**
 * The URL [ImgproxyClient] builds, and the signature it puts in front of it.
 *
 * **The vector below was verified against a real imgproxy**, `v4.0.14` at the digest the chart pins.
 * Running it with this key and salt and requesting this exact path answered `404 Source image is
 * unreachable` — past signature verification, failing only on the S3 source a local container
 * cannot reach. One byte changed in the signature answered `403 Forbidden`. So this constant is
 * imgproxy's own arithmetic rather than a second copy of the implementation under test.
 *
 * That distinction is the point. A signing test that recomputes the HMAC the same way the code does
 * passes whatever the code is wrong about.
 */
class ImgproxyClientTest {
    private val storage = ImageStorageProperties(bucket = "event-junkie-images", prefix = "staging")

    private fun client(properties: ImgproxyProperties) = ImgproxyClient(properties, storage, WebClient.builder())

    @Test
    fun `signs the path exactly as imgproxy verifies it`() {
        val signed =
            client(
                ImgproxyProperties(key = KEY, salt = SALT)
            ).sign(PATH)

        signed shouldBe "/$SIGNATURE$PATH"
    }

    @Test
    fun `builds a resize path against the bucket and the environment prefix`() {
        // `rs:fit:<width>:0` fits inside the width and lets the height follow, so nothing is
        // cropped — one aspect ratio, resized only.
        client(ImgproxyProperties()).renderPath("abc123", width = 192, format = "avif") shouldBe PATH
    }

    @Test
    fun `falls back to the insecure prefix when no key is configured`() {
        // imgproxy allows unsigned URLs and we do not run that way. The prefix exists so a local
        // run without a key still works, and so its absence is visible in the URL rather than
        // silently producing an unsigned request that looks signed.
        client(ImgproxyProperties()).sign(PATH) shouldStartWith "/insecure/"
    }

    @Test
    fun `treats a key with no salt as unsigned rather than half-signed`() {
        client(ImgproxyProperties(key = KEY)).sign(PATH) shouldStartWith "/insecure/"
    }

    // --- render, against a stand-in for the sidecar ----------------------------------------------

    private lateinit var server: MockWebServer

    @BeforeEach
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stopServer() = server.close()

    private fun renderingClient() =
        ImgproxyClient(
            ImgproxyProperties(baseUrl = server.url("/").toString().trimEnd('/')),
            storage,
            WebClient.builder()
        )

    @Test
    fun `returns the rendered bytes and asks for the path it signed`() =
        runTest {
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(Buffer().write(byteArrayOf(1, 2, 3)))
                    .build()
            )

            renderingClient().render("abc123", width = 192, format = "avif")!!.size shouldBe 3

            // The request has to carry the signature prefix and the resize options, or imgproxy
            // would answer 403 and this would look like a network problem.
            val target = server.takeRequest().target
            target shouldStartWith "/insecure/rs:fit:192:0/"
            target shouldContain ".avif"
        }

    @Test
    fun `returns null when imgproxy refuses the source`() =
        runTest {
            // A source past its resolution or file-size bound. The caller records the variants that
            // did work rather than failing the whole image, so this must not throw.
            server.enqueue(MockResponse.Builder().code(422).build())

            renderingClient().render("abc123", width = 192, format = "avif").shouldBeNull()
        }

    @Test
    fun `returns null rather than throwing when the sidecar is unreachable`() =
        runTest {
            // A sidecar that is starting, out of memory or gone. One dead render must not stop a
            // pass, so the failure is a null and never an exception.
            val unreachable =
                ImgproxyClient(
                    ImgproxyProperties(baseUrl = "http://127.0.0.1:1"),
                    storage,
                    WebClient.builder()
                )

            unreachable.render("abc123", width = 192, format = "avif").shouldBeNull()
        }

    private companion object {
        // **These are a published test vector and must never configure a deployment.** They exist
        // so the constant below is imgproxy's arithmetic rather than a copy of ours, and they signed
        // nothing but a local container that no longer exists. A real pair comes from
        // `openssl rand -hex 32` per environment (SECRETS.md §event-junkie-imgproxy).
        //
        // A 64-character hex string named KEY is exactly what the entropy rule is for, and it is
        // right to flag it. Allowed on the line rather than by widening the path list in
        // .gitleaks.toml, which that file warns against doing.
        const val KEY = "943b421c9eb07c830af81030552c86009268de4e532ba2ee2eab8247c6da0881" // gitleaks:allow
        const val SALT = "520f986b998545b4785e0defbc4f3c1203f22de2374a3d53cb7a7fe9fea309c5" // gitleaks:allow
        const val PATH = "/rs:fit:192:0/czM6Ly9ldmVudC1qdW5raWUtaW1hZ2VzL3N0YWdpbmcvb3JpZ2luYWxzL2FiYzEyMw.avif"
        const val SIGNATURE = "TvwZutT1COv9KoKWpp5JKOcHhLqBPz4VZao2atIJDiU"
    }
}
