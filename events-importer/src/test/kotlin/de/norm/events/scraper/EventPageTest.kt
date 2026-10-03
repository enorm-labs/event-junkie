package de.norm.events.scraper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.jsoup.Jsoup
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import java.time.LocalDate

/** Unit tests for [withEventPageOrFlagged] and [readEventPage], the one failure path for an event page. */
class EventPageTest {
    private val htmlFetcher: HtmlFetcher = mockk()
    private val event =
        ScrapedEvent(
            title = "Late Night Jazz",
            eventDate = LocalDate.of(2026, 10, 9),
            sourceUrl = "https://venue.example/events/late-night-jazz",
            sourceId = "venue:late-night-jazz"
        )
    private val appender = ListAppender<ILoggingEvent>()
    private val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger

    @BeforeEach
    fun attachAppender() {
        appender.start()
        root.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        root.detachAppender(appender)
        appender.stop()
    }

    private fun warnings() = appender.list.filter { it.level == Level.WARN }

    private fun stubPage(html: String) {
        coEvery { htmlFetcher.fetchDocument(event.sourceUrl) } returns Jsoup.parse(html, event.sourceUrl)
    }

    @Test
    fun `returns the enriched event when the page parses`() =
        runTest {
            stubPage("<p>A blurb</p>")

            val result = htmlFetcher.withEventPageOrFlagged(event) { event.copy(description = it.selectFirst("p")?.text()) }

            result.description shouldBe "A blurb"
            result.detailUnavailable shouldBe false
            warnings().shouldBeEmpty()
        }

    @Test
    fun `flags the listing row and logs one WARN with url and source id when the fetch fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(event.sourceUrl) } throws RuntimeException("boom")

            val result = htmlFetcher.withEventPageOrFlagged(event) { event.copy(description = "never") }

            result shouldBe event.copy(detailUnavailable = true)
            warnings() shouldHaveSize 1
            val payload = warnings().single().keyValuePairs.associate { it.key to it.value }
            payload shouldBe mapOf(LogFields.URL to event.sourceUrl, LogFields.EVENT_SOURCE_ID to event.sourceId)
            warnings().single().throwableProxy.message shouldBe "boom"
        }

    @Test
    fun `flags the listing row without a WARN when the page parses to nothing`() =
        runTest {
            stubPage("<p></p>")

            val result = htmlFetcher.withEventPageOrFlagged(event) { null }

            result shouldBe event.copy(detailUnavailable = true)
            warnings().shouldBeEmpty()
        }

    @Test
    fun `flags the listing row when the parser throws`() =
        runTest {
            stubPage("<p></p>")

            val result = htmlFetcher.withEventPageOrFlagged(event) { error("unexpected markup") }

            result shouldBe event.copy(detailUnavailable = true)
            warnings() shouldHaveSize 1
        }

    @Test
    fun `parses inside the page scope, so a parser's own line names the page`() =
        runTest {
            stubPage("<p></p>")

            htmlFetcher.readEventPage(event) { LoggerFactory.getLogger(javaClass).warn("parsing") }

            appender.list.single { it.formattedMessage == "parsing" }.mdcPropertyMap[LogFields.URL] shouldBe event.sourceUrl
        }

    @Test
    fun `returns null from readEventPage when the fetch fails`() =
        runTest {
            coEvery { htmlFetcher.fetchDocument(event.sourceUrl) } throws RuntimeException("boom")

            htmlFetcher.readEventPage(event) { "parsed" }.shouldBeNull()
        }
}
