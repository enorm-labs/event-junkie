package de.norm.events.scraper

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.boot.logging.logback.StructuredLogEncoder
import org.springframework.core.env.Environment
import org.springframework.mock.env.MockEnvironment
import tools.jackson.databind.json.JsonMapper

private val logger = KotlinLogging.logger {}

/**
 * The log context is asserted through a **captured log event**, not through [MDC] directly (#380).
 *
 * Reading MDC back in the test would prove the map was set and nothing about whether a log line
 * carries it, which is the failure this guards: the line still appears, only without its fields, so
 * an assertion on MDC alone would stay green through exactly the regression that matters. What
 * Logback sees in `ILoggingEvent.mdcPropertyMap` is what Spring's ECS formatter serialises into the
 * JSON object, so that map is the closest thing to the shipped log line a unit test can hold.
 */
class LogContextTest {
    private lateinit var appender: ListAppender<ILoggingEvent>
    private lateinit var root: Logger

    @BeforeEach
    fun attachAppender() {
        root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        root.detachAppender(appender)
        appender.stop()
        MDC.clear()
    }

    @Nested
    inner class ForImportRun {
        @Test
        fun `puts the source slug and a run id on a line logged inside the context`() =
            runTest {
                withContext(LogContext.forImportRun("berghain")) {
                    logger.info { "scraped" }
                }

                appender.list shouldHaveSize 1
                val mdc = appender.list.single().mdcPropertyMap
                mdc[LogContext.SOURCE_SLUG] shouldBe "berghain"
                mdc shouldContainKey LogContext.IMPORT_RUN_ID
                mdc[LogContext.IMPORT_RUN_ID] shouldNotBe ""
            }

        @Test
        fun `keeps the context across a suspension point and a thread change`() =
            runTest {
                withContext(LogContext.forImportRun("renate")) {
                    // The trap this whole mechanism exists for: MDC is thread-local, so a plain
                    // MDC.put would be gone by the line below — silently, as an absent field.
                    delay(1)
                    withContext(Dispatchers.Default) { logger.info { "after hopping threads" } }
                }

                appender.list.single().mdcPropertyMap[LogContext.SOURCE_SLUG] shouldBe "renate"
            }

        @Test
        fun `gives concurrent source imports their own slug rather than the last one set`() =
            runTest {
                coroutineScope {
                    listOf("amt", "lido", "ohm")
                        .map { slug ->
                            async(LogContext.forImportRun(slug)) {
                                delay(1)
                                logger.info { "importing" }
                            }
                        }.awaitAll()
                }

                appender.list.map { it.mdcPropertyMap[LogContext.SOURCE_SLUG] }.toSet() shouldBe setOf("amt", "lido", "ohm")
            }

        @Test
        fun `merges over an outer context rather than replacing it`() =
            runTest {
                withContext(LogContext.forImportRun("outer")) {
                    val outerRunId = MDC.get(LogContext.IMPORT_RUN_ID)
                    withContext(LogContext.forImportRun("inner")) {
                        logger.info { "nested" }
                        MDC.get(LogContext.IMPORT_RUN_ID) shouldNotBe outerRunId
                    }
                }

                appender.list.single().mdcPropertyMap[LogContext.SOURCE_SLUG] shouldBe "inner"
            }

        @Test
        fun `reaches the ECS JSON as a top-level field, which is what makes it filterable`() =
            runTest {
                withContext(LogContext.forImportRun("berghain")) { logger.info { "parse failed" } }

                val json = JsonMapper.builder().build().readTree(encodeAsEcs(appender.list.single()))

                // Top level, beside `message` — not nested, and not inside the message text. This is
                // the assertion the whole change exists for: a field at this position is a column
                // the log store can filter on once the collector lifts it out.
                json.get(LogContext.SOURCE_SLUG).stringValue() shouldBe "berghain"
                json.get(LogContext.IMPORT_RUN_ID).stringValue() shouldNotBe null
                json.get("message").stringValue() shouldBe "parse failed"
                // The paths the collector's OTTL reads. `log.level` is nested, so a rule written
                // against a flat `log.level` key would match nothing and look like it worked.
                json.get("log").get("level").stringValue() shouldBe "INFO"
            }

        @Test
        fun `leaves no context behind once the run is over`() =
            runTest {
                withContext(LogContext.forImportRun("supamolly")) { logger.info { "during" } }
                logger.info { "after" }

                appender.list shouldHaveSize 2
                appender.list.last().mdcPropertyMap[LogContext.SOURCE_SLUG] shouldBe null
            }
    }

    /**
     * Encodes a captured event exactly as the running container does — Spring Boot's own ECS
     * encoder, the one `LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs` installs. Asserting against this
     * rather than a hand-written JSON shape is what makes the test worth having: it fails if Boot
     * ever changes where MDC entries land.
     */
    private fun encodeAsEcs(event: ILoggingEvent): String {
        val context = LoggerContext().apply { putObject(Environment::class.java.name, MockEnvironment()) }
        val encoder =
            StructuredLogEncoder().apply {
                setFormat("ecs")
                setContext(context)
                start()
            }
        return String(encoder.encode(event)).also { encoder.stop() }
    }
}
