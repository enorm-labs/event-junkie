package de.norm.events.scraper

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
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
import java.net.InetAddress
import java.net.URI
import java.time.Duration

/**
 * A host in `app.scraper.plain-user-agent-hosts` gets [PLAIN_SCRAPER_USER_AGENT], and every other host keeps the shared one (#3825).
 * The server listens on `127.0.0.1`, so that is the host the set names.
 */
class HostUserAgentTest {
    private val config = ScraperHttpClientConfig()
    private lateinit var server: MockWebServer

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start(InetAddress.getByName(HOST), 0)
    }

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `a listed host gets the plain User-Agent`() {
        userAgentSentBy(client(setOf(HOST))) shouldBe PLAIN_SCRAPER_USER_AGENT
    }

    @Test
    fun `a host not in the set keeps the shared User-Agent`() {
        userAgentSentBy(client(setOf("claerchensball.haus"))) shouldContain "(compatible; EventJunkie/1.0;"
    }

    @Test
    fun `the host name matches regardless of case`() {
        userAgentSentBy(client(setOf(HOST.uppercase()))) shouldBe PLAIN_SCRAPER_USER_AGENT
    }

    @Test
    fun `application yaml names Claerchens Ballhaus`() {
        val environment = StandardEnvironment()
        // By path, because the test classpath has an application.yaml of its own. The first document only: the second is the `local` profile.
        val yaml = FileSystemResource("src/main/resources/application.yaml")
        environment.propertySources.addLast(YamlPropertySourceLoader().load("application", yaml).first())

        val properties = Binder.get(environment).bind("app.scraper", ScraperProperties::class.java).get()

        properties.plainUserAgentHosts shouldBe setOf("claerchensball.haus")
    }

    private fun client(hosts: Set<String>): WebClient {
        val properties = ScraperProperties(politeDelayMillis = 0, plainUserAgentHosts = hosts)
        return config.scraperBaseWebClient(
            webClientBuilder = WebClient.builder(),
            scraperProperties = properties,
            connectionProvider = config.scraperConnectionProvider(properties),
            throttle = config.perHostThrottlingFilter(properties)
        )
    }

    private fun userAgentSentBy(client: WebClient): String? {
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body("ok")
                .build()
        )
        client
            .get()
            .uri(URI("http://$HOST:${server.port}/"))
            .retrieve()
            .bodyToMono<String>()
            .block(Duration.ofSeconds(10))
        return server.takeRequest().headers["User-Agent"]
    }

    private companion object {
        const val HOST = "127.0.0.1"
    }
}
