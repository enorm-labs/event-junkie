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
import tools.jackson.databind.node.JsonNodeFactory

/** Unit tests for [mapSkippingFailures], [flatMapSkippingFailures] and [parseListing], the one per-row failure path and its count line. */
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
    fun `counts a listing in one INFO line with its URL as a field, then parses it`() {
        val parsed = listOf("1", "x", "3").parseListing(logger, "Example event item", url = "https://example.de/programm") { it.toInt() }

        parsed shouldContainExactly listOf(1, 3)
        val info = appender.list.single { it.level == Level.INFO }
        info.loggerName shouldBe "venue.ExampleOverviewPageScraper"
        info.formattedMessage shouldBe "Found 3 Example event item(s)"
        info.keyValuePairs.associate { it.key to it.value } shouldBe mapOf(LogFields.URL to "https://example.de/programm")
        warnings().single().formattedMessage shouldBe "Failed to parse Example event item, skipping"
    }

    @Test
    fun `counts a listing that is only iterable, and leaves the URL out when none is given`() {
        val rows =
            JsonNodeFactory.instance
                .arrayNode()
                .add(1)
                .add(2)

        rows.parseListing(logger, "Example record") { it.asInt() } shouldContainExactly listOf(1, 2)
        val info = appender.list.single { it.level == Level.INFO }
        info.formattedMessage shouldBe "Found 2 Example record(s)"
        info.keyValuePairs.orEmpty().shouldBeEmpty()
    }

    @Test
    fun `counts without the article the failure line keeps`() {
        listOf("x").parseListing(logger, "a Paloma night") { it.toInt() }
        listOf("x").parseListing(logger, "an L.U.X night") { it.toInt() }

        appender.list.filter { it.level == Level.INFO }.map { it.formattedMessage } shouldContainExactly
            listOf("Found 1 Paloma night(s)", "Found 1 L.U.X night(s)")
        warnings().map { it.formattedMessage } shouldContainExactly
            listOf("Failed to parse a Paloma night, skipping", "Failed to parse an L.U.X night, skipping")
    }

    @Test
    fun `flattens the rows that parse and drops one that throws`() {
        val parsed = listOf("1,2", "x", "3").flatMapSkippingFailures(logger, "Example post") { row -> row.split(',').map { it.toInt() } }

        parsed shouldContainExactly listOf(1, 2, 3)
        warnings().single().formattedMessage shouldBe "Failed to parse Example post, skipping"
    }
}
