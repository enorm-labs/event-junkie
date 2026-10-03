package de.norm.events.scraper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.github.oshai.kotlinlogging.KotlinLogging
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory

/** Unit tests for [mapSkippingFailures] and [flatMapSkippingFailures], the one per-row failure path. */
class RowParsingTest {
    private val logger = KotlinLogging.logger("venue.ExampleOverviewPageScraper")
    private val appender = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeEach
    fun attach() {
        appender.start()
        root.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        root.detachAppender(appender)
        appender.stop()
    }

    private fun warnings() = appender.list.filter { it.level == Level.WARN }

    @Test
    fun `drops a row that throws and logs it once on the caller's logger`() {
        val parsed = listOf("1", "x", "3").mapSkippingFailures(logger, "Example event item") { it.toInt() }

        parsed shouldContainExactly listOf(1, 3)
        val warning = warnings().single()
        warning.loggerName shouldBe "venue.ExampleOverviewPageScraper"
        warning.formattedMessage shouldBe "Failed to parse Example event item, skipping"
        warning.throwableProxy.className shouldBe NumberFormatException::class.java.name
    }

    @Test
    fun `drops a row that parses to null without a warning`() {
        listOf("1", "", "3").mapSkippingFailures(logger, "Example event item") { it.toIntOrNull() } shouldContainExactly listOf(1, 3)
        warnings().shouldBeEmpty()
    }

    @Test
    fun `names the failed row when given a function of it`() {
        listOf("x").mapSkippingFailures(logger, { row -> "Example entry '$row'" }) { it.toInt() }

        warnings().single().formattedMessage shouldBe "Failed to parse Example entry 'x', skipping"
    }

    @Test
    fun `flattens the rows that parse and drops one that throws`() {
        val parsed = listOf("1,2", "x", "3").flatMapSkippingFailures(logger, "Example post") { row -> row.split(',').map { it.toInt() } }

        parsed shouldContainExactly listOf(1, 2, 3)
        warnings().single().formattedMessage shouldBe "Failed to parse Example post, skipping"
    }
}
