package de.norm.events.instagram

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.web.reactive.function.client.WebClient
import java.net.URLDecoder
import java.time.OffsetDateTime

/** The real [WebClient] pipeline against a local server: the request Business Discovery gets, and what each answer becomes. */
class BusinessDiscoveryClientTest {
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

    private fun client(): BusinessDiscoveryClient {
        val properties =
            InstagramProperties(
                accessToken = "secret-token",
                igUserId = "1784",
                graphBaseUrl = server.url("/").toString(),
                graphVersion = "v26.0"
            )
        val webClient =
            InstagramHttpClientConfig().instagramWebClient(
                webClientBuilder = WebClient.builder(),
                properties = properties,
                buildProperties = DefaultListableBeanFactory().getBeanProvider(BuildProperties::class.java)
            )
        return BusinessDiscoveryClient(webClient, properties)
    }

    private fun answer(
        code: Int,
        body: String
    ) = MockResponse
        .Builder()
        .code(code)
        .addHeader("Content-Type", "application/json")
        .body(body)
        .build()

    @Test
    fun `reads the posts with the token in a header and without media_url`() =
        runTest {
            server.enqueue(
                answer(
                    200,
                    """
                    {"business_discovery": {"media": {"data": [
                      {"id": "18356757751169892", "caption": "We host selfcontrolbln on Friday", "media_type": "CAROUSEL_ALBUM",
                       "permalink": "https://www.instagram.com/p/DecMBi9gqGk/", "timestamp": "2026-10-13T16:00:12+0000"},
                      {"id": "2", "permalink": "https://www.instagram.com/p/x/", "timestamp": "2026-10-12T17:00:05+0000"},
                      {"id": "3", "permalink": "https://example.com/elsewhere", "timestamp": "2026-10-12T17:00:05+0000"}
                    ]}, "id": "17841448480552313"}, "id": "1784"}
                    """.trimIndent()
                )
            )

            val result = client().recentPosts("rso.berlin")

            result shouldBe
                DiscoveryResult.Posts(
                    listOf(
                        InstagramPost(
                            mediaId = "18356757751169892",
                            caption = "We host selfcontrolbln on Friday",
                            permalink = "https://www.instagram.com/p/DecMBi9gqGk/",
                            postedAt = OffsetDateTime.parse("2026-10-13T16:00:12Z"),
                            mediaType = "CAROUSEL_ALBUM"
                        ),
                        InstagramPost("2", null, "https://www.instagram.com/p/x/", OffsetDateTime.parse("2026-10-12T17:00:05Z"), null)
                    )
                )
            val request = server.takeRequest()
            request.headers["Authorization"] shouldBe "Bearer secret-token"
            val target = URLDecoder.decode(request.target, Charsets.UTF_8)
            target shouldContain "/v26.0/1784?fields=business_discovery.username(rso.berlin){media.limit(25){id,caption,permalink,timestamp,media_type}}"
            target shouldNotContain "media_url"
            target shouldNotContain "secret-token"
        }

    @Test
    fun `error 110 is an unreadable account`() =
        runTest {
            server.enqueue(answer(400, """{"error": {"message": "Invalid user id", "code": 110, "error_subcode": 2207013}}"""))

            client().recentPosts("nobody.here") shouldBe DiscoveryResult.AccountUnreadable(110)
        }

    @Test
    fun `a missing permission and an expired token refuse access`() =
        runTest {
            server.enqueue(answer(403, """{"error": {"message": "(#10) Application does not have permission for this action", "code": 10}}"""))
            server.enqueue(answer(401, """{"error": {"message": "Session has expired", "code": 190}}"""))

            client().recentPosts("rso.berlin").shouldBeInstanceOf<DiscoveryResult.AccessRefused>().code shouldBe 10
            client().recentPosts("rso.berlin").shouldBeInstanceOf<DiscoveryResult.AccessRefused>().code shouldBe 190
        }

    @Test
    fun `a rate limit and a server error without JSON are unavailable`() =
        runTest {
            server.enqueue(answer(400, """{"error": {"message": "Application request limit reached", "code": 4}}"""))
            server.enqueue(answer(502, "Bad Gateway"))

            client().recentPosts("rso.berlin") shouldBe DiscoveryResult.Unavailable("Graph error 4")
            client().recentPosts("rso.berlin") shouldBe DiscoveryResult.Unavailable("HTTP 502")
        }

    @Test
    fun `a handle that could break the field expression is refused before any request`() =
        runTest {
            shouldThrow<IllegalArgumentException> { client().recentPosts("x){media_url}") }

            server.requestCount shouldBe 0
        }
}
