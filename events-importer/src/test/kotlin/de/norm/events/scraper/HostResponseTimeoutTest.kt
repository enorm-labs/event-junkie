package de.norm.events.scraper

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.netty.handler.timeout.ReadTimeoutException
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.resources.ConnectionProvider
import java.net.InetAddress
import java.net.URI
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * A host in `app.scraper.host-response-timeouts` waits for its own timeout, and every other host keeps the global one (#2840).
 * Scaled down to milliseconds: the server listens on `127.0.0.1`, so that is the host the map names.
 */
class HostResponseTimeoutTest {
    private val config = ScraperHttpClientConfig()
    private lateinit var provider: ConnectionProvider
    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        provider = config.scraperConnectionProvider(ScraperProperties())
        server = MockWebServer()
        server.start(InetAddress.getByName(HOST), 0)
    }

    @AfterEach
    fun stop() {
        server.close()
        provider.dispose()
    }

    @Test
    fun `a host with its own timeout waits past the global one`() {
        get(client(mapOf(HOST to HOST_TIMEOUT)), delayMillis = BETWEEN_MILLIS) shouldBe "ok"
    }

    @Test
    fun `a host with its own timeout gives up at that timeout`() {
        val elapsed = timedOutAfterMillis(client(mapOf(HOST to HOST_TIMEOUT)), delayMillis = LONG_DELAY_MILLIS)

        elapsed shouldBeGreaterThanOrEqual HOST_TIMEOUT.toMillis()
        elapsed shouldBeLessThan LONG_DELAY_MILLIS
    }

    @Test
    fun `a host not in the map gives up at the global timeout`() {
        val elapsed = timedOutAfterMillis(client(mapOf("radar.squat.net" to HOST_TIMEOUT)), delayMillis = BETWEEN_MILLIS)

        elapsed shouldBeGreaterThanOrEqual GLOBAL_TIMEOUT.toMillis()
        elapsed shouldBeLessThan BETWEEN_MILLIS
    }

    @Test
    fun `the host name matches regardless of case`() {
        get(client(mapOf(HOST.uppercase() to HOST_TIMEOUT)), delayMillis = BETWEEN_MILLIS) shouldBe "ok"
    }

    @Test
    fun `application yaml binds radar squat net as one key`() {
        val environment = StandardEnvironment()
        // By path, because the test classpath has an application.yaml of its own. The first document only: the second is the `local` profile.
        val yaml = FileSystemResource("src/main/resources/application.yaml")
        environment.propertySources.addLast(YamlPropertySourceLoader().load("application", yaml).first())

        val properties = Binder.get(environment).bind("app.scraper", ScraperProperties::class.java).get()

        properties.hostResponseTimeouts shouldBe mapOf("radar.squat.net" to Duration.ofSeconds(60))
        properties.responseTimeout shouldBe Duration.ofSeconds(30)
    }

    private fun client(hostTimeouts: Map<String, Duration>): WebClient {
        val properties = ScraperProperties(politeDelayMillis = 0, responseTimeout = GLOBAL_TIMEOUT, hostResponseTimeouts = hostTimeouts)
        return config.scraperBaseWebClient(
            webClientBuilder = WebClient.builder(),
            scraperProperties = properties,
            connectionProvider = provider,
            throttle = config.perHostThrottlingFilter(properties)
        )
    }

    private fun timedOutAfterMillis(
        client: WebClient,
        delayMillis: Long
    ): Long {
        val startedAt = System.nanoTime()
        val error = shouldThrow<Exception> { get(client, delayMillis) }
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        generateSequence<Throwable>(error) { it.cause }.any { it is ReadTimeoutException }.shouldBeTrue()
        return elapsed
    }

    private fun get(
        client: WebClient,
        delayMillis: Long
    ): String? {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("ok")
                .headersDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
        )
        return client
            .get()
            .uri(URI("http://$HOST:${server.port}/"))
            .retrieve()
            .bodyToMono<String>()
            .block(Duration.ofSeconds(10))
    }

    private companion object {
        const val HOST = "127.0.0.1"
        val GLOBAL_TIMEOUT: Duration = Duration.ofMillis(300)
        val HOST_TIMEOUT: Duration = Duration.ofMillis(1500)
        const val BETWEEN_MILLIS = 800L
        const val LONG_DELAY_MILLIS = 4000L
    }
}
