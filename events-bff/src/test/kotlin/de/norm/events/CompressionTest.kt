package de.norm.events

import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.GZIPInputStream

/**
 * `server.compression` is applied by Netty, below every filter, so only a request over a real
 * socket shows it (#1206). The JDK client, because `WebTestClient` decompresses silently and
 * drops the header. The test `application.yaml` shadows the main one, so the last test holds the
 * two `server.compression` blocks together.
 */
class CompressionTest : BaseControllerTest() {
    private val client = HttpClient.newHttpClient()

    private fun get(
        path: String,
        acceptEncoding: String? = null
    ): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI("http://localhost:$port/api$path"))
        acceptEncoding?.let { request.header("Accept-Encoding", it) }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun HttpResponse<ByteArray>.contentEncoding(): String? = headers().firstValue("Content-Encoding").orElse(null)

    private suspend fun insertVenuesAboveTheThreshold() {
        repeat(VENUES) { insertVenue("Venue $it", "venue-$it", description = "A room with a sound system. ".repeat(4)) }
    }

    @Test
    fun `a JSON response above 1 KB is gzipped for a client that asks`(): Unit =
        runBlocking {
            insertVenuesAboveTheThreshold()

            val response = get("/venues?size=$VENUES", acceptEncoding = "gzip")

            response.statusCode() shouldBe 200
            response.contentEncoding() shouldBe "gzip"
            val json = GZIPInputStream(ByteArrayInputStream(response.body())).readBytes().decodeToString()
            json shouldStartWith "{"
            json shouldContain "\"venue-0\""
        }

    @Test
    fun `a client that does not ask gets the identity encoding`(): Unit =
        runBlocking {
            insertVenuesAboveTheThreshold()

            val response = get("/venues?size=$VENUES")

            response.statusCode() shouldBe 200
            response.contentEncoding() shouldBe null
            response.body().decodeToString() shouldContain "\"venue-0\""
        }

    @Test
    fun `a response below 1 KB is not compressed`(): Unit =
        runBlocking {
            insertGenreTag("Techno", "techno")

            val response = get("/genres", acceptEncoding = "gzip")

            response.statusCode() shouldBe 200
            response.contentEncoding() shouldBe null
            (response.body().size < 1024) shouldBe true
        }

    @Test
    fun `every read says it varies by Accept-Encoding, compressed or not`(): Unit =
        runBlocking {
            insertGenreTag("Techno", "techno")

            val vary =
                get("/genres")
                    .headers()
                    .allValues("Vary")
                    .flatMap { it.split(",") }
                    .map { it.trim().lowercase() }

            withClue("a shared cache without `Vary` hands a gzip body to a client that never asked for one") {
                vary shouldContain "accept-encoding"
            }
        }

    @Test
    fun `the test configuration compresses exactly as the shipped one`() {
        val main = compressionBlock("src/main/resources/application.yaml")

        withClue("the shipped application.yaml must turn compression on; found: $main") {
            main shouldContain "enabled: true"
        }
        withClue("the test application.yaml shadows the main one, so the two blocks must match") {
            compressionBlock("src/test/resources/application.yaml") shouldBe main
        }
    }

    private fun compressionBlock(path: String): List<String> {
        val lines = File(path).readLines()
        val start = lines.indexOfFirst { it.trim() == "compression:" }
        withClue("no `compression:` block in $path") { (start >= 0) shouldBe true }
        return lines.drop(start + 1).takeWhile { it.startsWith("    ") }.map { it.trim() }
    }

    private companion object {
        const val VENUES = 30
    }
}
