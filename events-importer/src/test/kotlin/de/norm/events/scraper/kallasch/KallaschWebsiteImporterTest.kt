package de.norm.events.scraper.kallasch

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Unit tests for [KallaschWebsiteImporter], with the saved feed behind a mocked [ApiClient]. */
class KallaschWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val importer = KallaschWebsiteImporter(apiClient, clock)
    private val listingUrl = "https://kallasch.berlin/programm/"
    private val requestUrl = slot<String>()

    private val fixture: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/kallasch/kallasch-events.ics")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(capture(requestUrl)) } returns fixture
    }

    @Test
    fun `importEvents reads the MEC iCalendar feed on the listing's host`() =
        runTest {
            val result = importer.importEvents(listingUrl)

            requestUrl.captured shouldBe "https://kallasch.berlin/?mec-ical-feed=1"
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 28
            result.events.all { it.sourceId.startsWith("kallasch:") } shouldBe true
        }

    @Test
    fun `importEvents ignores the validators, because the horizon moves with the clock`() =
        runTest {
            val result = importer.importEvents(listingUrl, etag = "\"abc\"", lastModified = "Wed, 01 Jul 2026 00:00:00 GMT")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success for a calendar without events`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nEND:VCALENDAR\r\n"

            val result = importer.importEvents(listingUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.shouldBeEmpty()
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.KALLASCH
    }
}
