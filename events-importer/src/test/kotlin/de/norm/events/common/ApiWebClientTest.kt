package de.norm.events.common

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.boot.info.BuildProperties
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBodyOrNull
import org.springframework.web.reactive.function.client.awaitExchange
import java.time.Duration

class ApiWebClientTest {
    private lateinit var server: MockWebServer
    private val appender = ListAppender<ILoggingEvent>()
    private val logger = LoggerFactory.getLogger(ApiWebClient::class.java) as Logger

    @BeforeEach
    fun start() {
        server = MockWebServer()
        server.start()
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun stop() {
        logger.detachAppender(appender)
        server.close()
    }

    private val client =
        ApiWebClient
            .builder(
                webClientBuilder = WebClient.builder(),
                timeout = Duration.ofSeconds(5),
                buildProperties = DefaultListableBeanFactory().getBeanProvider(BuildProperties::class.java)
            ).build()

    private fun redirect(location: String) =
        MockResponse
            .Builder()
            .code(301)
            .addHeader("Location", location)
            .build()

    private suspend fun get(path: String): Pair<Int, String> =
        client.get().uri(server.url(path).toString()).awaitExchange { it.statusCode().value() to it.awaitBodyOrNull<String>().orEmpty() }

    @Test
    fun `follows a 301 that stays on the request's host, with the API headers`() =
        runTest {
            server.enqueue(redirect("/ws/2/artist/survivor"))
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body("survivor")
                    .build()
            )

            get("/ws/2/artist/merged") shouldBe (200 to "survivor")

            server.takeRequest().target shouldBe "/ws/2/artist/merged"
            val followed = server.takeRequest()
            followed.target shouldBe "/ws/2/artist/survivor"
            followed.headers["User-Agent"]!! shouldStartWith "event-junkie/dev "
            followed.headers["Accept"] shouldBe "application/json"
            appender.list.shouldBeEmpty()
        }

    @Test
    fun `returns a redirect to another host unfollowed, and says so`() =
        runTest {
            server.enqueue(redirect("https://elsewhere.invalid/steal"))

            get("/database/search") shouldBe (301 to "")

            server.requestCount shouldBe 1
            appender.list shouldHaveSize 1
            appender.list.single().level shouldBe Level.WARN
            appender.list.single().formattedMessage shouldContain "to elsewhere.invalid"
        }
}
