package de.norm.events

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import reactor.core.publisher.Mono

/**
 * That the filter stays quiet about actuator traffic. Worth testing because the failure mode is
 * silence in the other direction: get the match wrong and a real route stops being logged, which
 * nobody notices until they look for a request served months ago.
 */
class RequestLoggingFilterTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var root: Logger

    @BeforeEach
    fun setUp() {
        root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        root.detachAppender(appender)
        appender.stop()
    }

    private fun get(
        path: String,
        basePath: String = "/actuator"
    ) = RequestLoggingFilter(basePath)
        .filter(MockServerWebExchange.from(MockServerHttpRequest.get(path))) { Mono.empty() }
        .block()

    /**
     * The `path` field of every line captured, read from `keyValuePairs` rather than the rendered
     * message (#945), so a wording change cannot break a test about routing.
     */
    private fun loggedPaths() =
        appender.list.mapNotNull { event ->
            event.keyValuePairs?.firstOrNull { it.key == LogContextConfiguration.PATH }?.value as String?
        }

    @Test
    fun `logs an ordinary request`() {
        get("/venues?q=astra")

        loggedPaths() shouldBe listOf("/venues")
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "/actuator",
            "/actuator/health/readiness",
            "/actuator/health/liveness",
            "/actuator/prometheus",
            "/actuator/info"
        ]
    )
    fun `stays quiet about actuator traffic`(path: String) {
        get(path)

        loggedPaths() shouldBe emptyList<String>()
    }

    /**
     * The prefix trap: `/actuatorial` is an ordinary route this application could add tomorrow, and a
     * `startsWith` on the bare base path would swallow it.
     */
    @Test
    fun `logs a path that merely begins like the base path`() {
        get("/actuatorial")

        loggedPaths() shouldBe listOf("/actuatorial")
    }

    @Test
    fun `follows the configured base path rather than the default`() {
        get("/manage/health", basePath = "/manage")
        get("/actuator/health", basePath = "/manage")

        // The second call is not an actuator request under this configuration, so it is logged and the
        // first is not: the assertion that the property is read at all.
        loggedPaths() shouldBe listOf("/actuator/health")
    }

    @Test
    fun `tolerates a base path written with a trailing slash`() {
        get("/actuator/health", basePath = "/actuator/")

        loggedPaths() shouldBe emptyList<String>()
    }
}
