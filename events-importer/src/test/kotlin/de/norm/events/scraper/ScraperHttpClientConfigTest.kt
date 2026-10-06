package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.resources.ConnectionProvider
import java.time.Duration

/**
 * The scraper pool closes a connection that sat idle, rather than reusing one the venue's server may
 * already have closed (#2798). Read through [MockWebServer]'s connection index: a reused connection
 * keeps its index, a new one takes the next.
 */
class ScraperHttpClientConfigTest {
    private val properties =
        ScraperProperties(
            politeDelayMillis = 0,
            connectionMaxIdleTime = Duration.ofMillis(IDLE_MILLIS),
            connectionEvictionInterval = Duration.ofMillis(EVICTION_MILLIS)
        )
    private val config = ScraperHttpClientConfig()
    private lateinit var provider: ConnectionProvider
    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        provider = config.scraperConnectionProvider(properties)
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
        provider.dispose()
    }

    @Test
    fun `a connection is reused while it is fresh`() {
        val client = client()

        get(client)
        get(client)

        server.takeRequest().connectionIndex shouldBe 0
        server.takeRequest().connectionIndex shouldBe 0
    }

    @Test
    fun `a connection idle past maxIdleTime is closed and the next request opens a new one`() {
        val client = client()

        get(client)
        Thread.sleep(IDLE_MILLIS * 3)
        get(client)

        server.takeRequest().connectionIndex shouldBe 0
        server.takeRequest().connectionIndex shouldBe 1
    }

    private fun client(): WebClient =
        config.scraperBaseWebClient(
            webClientBuilder = WebClient.builder(),
            scraperProperties = properties,
            connectionProvider = provider,
            throttle = config.perHostThrottlingFilter(properties)
        )

    private fun get(client: WebClient) {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("ok")
                .build()
        )
        client
            .get()
            .uri(server.url("/").toUri())
            .retrieve()
            .bodyToMono<String>()
            .block(Duration.ofSeconds(5))
    }

    private companion object {
        const val IDLE_MILLIS = 200L
        const val EVICTION_MILLIS = 50L
    }
}
