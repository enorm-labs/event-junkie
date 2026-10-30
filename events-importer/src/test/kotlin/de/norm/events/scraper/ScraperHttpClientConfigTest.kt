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
 * keeps its index, a new one takes the next. Each test builds its pool with the idle time it is about (#4277).
 */
class ScraperHttpClientConfigTest {
    private val config = ScraperHttpClientConfig()
    private lateinit var provider: ConnectionProvider
    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun stop() {
        server.close()
        if (::provider.isInitialized) provider.dispose()
    }

    @Test
    fun `a connection is reused while it is fresh`() {
        val client = client(idleMillis = REUSE_IDLE_MILLIS)

        get(client)
        // block() returns on the body, and the pool takes the connection back a moment later on the event loop.
        Thread.sleep(RELEASE_PAUSE_MILLIS)
        get(client)

        server.takeRequest().connectionIndex shouldBe 0
        server.takeRequest().connectionIndex shouldBe 0
    }

    @Test
    fun `a connection idle past maxIdleTime is closed and the next request opens a new one`() {
        val client = client(idleMillis = IDLE_MILLIS)

        get(client)
        Thread.sleep(IDLE_MILLIS * 3)
        get(client)

        server.takeRequest().connectionIndex shouldBe 0
        server.takeRequest().connectionIndex shouldBe 1
    }

    private fun client(idleMillis: Long): WebClient {
        val properties =
            ScraperProperties(
                politeDelayMillis = 0,
                connectionMaxIdleTime = Duration.ofMillis(idleMillis),
                connectionEvictionInterval = Duration.ofMillis(EVICTION_MILLIS)
            )
        provider = config.scraperConnectionProvider(properties)
        return config.scraperBaseWebClient(
            webClientBuilder = WebClient.builder(),
            scraperProperties = properties,
            connectionProvider = provider,
            throttle = config.perHostThrottlingFilter(properties)
        )
    }

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
        const val REUSE_IDLE_MILLIS = 5_000L
        const val RELEASE_PAUSE_MILLIS = 100L
        const val IDLE_MILLIS = 200L
        const val EVICTION_MILLIS = 50L
    }
}
