package de.norm.events

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * The BFF serves JSON only, so Boot's default catch-all static-resource handler is off (#3075). With it on, every scanner
 * traversal probe logged a `ResourceHandlerUtils` WARN before the 404. Swagger UI keeps answering through springdoc's
 * own handler.
 *
 * The JDK client, because it sends `..` segments as written; the test `application.yaml` shadows the main one, so the
 * last test holds the two settings together.
 */
class StaticResourceHandlerTest : BaseControllerTest() {
    private val client = HttpClient.newHttpClient()
    private val appender = ListAppender<ILoggingEvent>()
    private val resourceLogger = LoggerFactory.getLogger(RESOURCE_HANDLER_UTILS) as Logger

    @BeforeEach
    fun attach() {
        appender.start()
        resourceLogger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        resourceLogger.detachAppender(appender)
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(HttpRequest.newBuilder(URI("http://localhost:$port$path")).build(), HttpResponse.BodyHandlers.ofString())

    @Test
    fun `a traversal probe is a 404 and logs nothing from the resource handler`() {
        listOf("/api/x/../../etc/passwd", "/api/hassio/app/../supervisor/info", "/x/../../etc/passwd").forEach { path ->
            withClue(path) { get(path).statusCode() shouldBe 404 }
        }

        withClue("ResourceHandlerUtils logged — is Boot's default static-resource handler back on?") {
            appender.list.map { it.formattedMessage }.shouldBeEmpty()
        }
    }

    @Test
    fun `the API, the actuator and the OpenAPI endpoints still answer`() {
        get("/api/venues").statusCode() shouldBe 200
        get("/actuator/health/liveness").statusCode() shouldBe 200
        get("/v3/api-docs").statusCode() shouldBe 200

        get("/v3/api-docs/swagger-config").statusCode() shouldBe 200
    }

    /** springdoc's own handler, not Boot's, serves the page and its assets. The `/webjars` path went with Boot's handler. */
    @Test
    fun `Swagger UI answers at springdoc's path with its assets`() {
        val page = get("/swagger-ui/index.html")
        page.statusCode() shouldBe 200
        page.body() shouldContain "swagger-ui"

        listOf("/swagger-ui/swagger-ui-bundle.js", "/swagger-ui/swagger-initializer.js", "/swagger-ui/swagger-ui.css").forEach { asset ->
            withClue(asset) { get(asset).statusCode() shouldBe 200 }
        }
    }

    @Test
    fun `the test configuration turns the handler off as the shipped one does`() {
        val main = addMappings("src/main/resources/application.yaml")
        val test = addMappings("src/test/resources/application.yaml")

        withClue("spring.web.resources.add-mappings must be false in the shipped configuration") { main shouldBe "false" }
        withClue("the test application.yaml shadows the main one, so the two must agree.\n  main: $main\n  test: $test") {
            test shouldBe main
        }
    }

    private fun addMappings(path: String): String =
        File(path)
            .readLines()
            .firstOrNull { it.trimStart().startsWith("add-mappings:") }
            ?.substringAfter("add-mappings:")
            ?.trim()
            ?: error("no `add-mappings:` line in $path — Boot's default static-resource handler is on")

    private companion object {
        const val RESOURCE_HANDLER_UTILS = "org.springframework.web.reactive.resource.ResourceHandlerUtils"
    }
}
