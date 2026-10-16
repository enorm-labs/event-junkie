package de.norm.events.instagram

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/** What the fetcher downloads, and what it refuses before or after asking. */
class InstagramImageFetcherTest {
    private val requests = mutableListOf<ClientRequest>()

    private fun fetcher(
        status: HttpStatus = HttpStatus.OK,
        type: String = "image/jpeg",
        body: String = "jpeg-bytes",
        limit: Int = InstagramImageFetcher.MAX_BYTES
    ): InstagramImageFetcher {
        val strategies = ExchangeStrategies.builder().codecs { it.defaultCodecs().maxInMemorySize(limit) }.build()
        val webClient =
            WebClient
                .builder()
                .exchangeFunction { request ->
                    requests += request
                    Mono.just(
                        ClientResponse
                            .create(status, strategies)
                            .header(HttpHeaders.CONTENT_TYPE, type)
                            .body(body)
                            .build()
                    )
                }.build()
        return InstagramImageFetcher(webClient)
    }

    @Test
    fun `an image on Meta's CDN is downloaded without a token`() =
        runTest {
            val images = fetcher().fetch(listOf(CDN, "https://video.xx.fbcdn.net/v/flyer.png"))

            images.map { it.bytes.decodeToString() } shouldBe listOf("jpeg-bytes", "jpeg-bytes")
            images.first().mediaType.toString() shouldBe "image/jpeg"
            requests.none { it.headers().containsHeader(HttpHeaders.AUTHORIZATION) } shouldBe true
        }

    @Test
    fun `another host, plain http and a look-alike domain are never asked`() =
        runTest {
            fetcher()
                .fetch(
                    listOf(
                        "https://example.com/flyer.jpg",
                        "http://scontent.cdninstagram.com/flyer.jpg",
                        "https://evilcdninstagram.com/flyer.jpg",
                        "not a url"
                    )
                ).shouldBeEmpty()

            requests.shouldBeEmpty()
        }

    @Test
    fun `an error, a type the model does not take and an image over the limit are dropped`() =
        runTest {
            fetcher(status = HttpStatus.FORBIDDEN).fetch(listOf(CDN)).shouldBeEmpty()
            fetcher(type = "image/heic").fetch(listOf(CDN)).shouldBeEmpty()
            fetcher(limit = 4).fetch(listOf(CDN)).shouldBeEmpty()
        }

    private companion object {
        const val CDN = "https://scontent-ber1-1.cdninstagram.com/v/t51/flyer.jpg?stp=dst-jpg&oh=signature"
    }
}
