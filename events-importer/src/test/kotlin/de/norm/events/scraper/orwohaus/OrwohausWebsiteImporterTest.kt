package de.norm.events.scraper.orwohaus

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

/** Unit tests for [OrwohausWebsiteImporter], with the saved feed behind a mocked [ApiClient]. */
class OrwohausWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneId.of("Europe/Berlin"))
    private val importer = OrwohausWebsiteImporter(apiClient, clock)
    private val listingUrl = "https://www.orwohaus.de/veranstaltungen/"
    private val requestUrl = slot<String>()

    private val fixture: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/orwohaus/orwohaus-events.ics")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(capture(requestUrl)) } returns fixture
    }

    @Test
    fun `importEvents reads the events feed on the listing's host`() =
        runTest {
            val result = importer.importEvents(listingUrl)

            requestUrl.captured shouldBe "https://www.orwohaus.de/events.ics"
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 34
            result.events.all { it.sourceId.startsWith("orwohaus:") } shouldBe true
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
        importer.eventSource shouldBe EventSource.ORWOHAUS
    }
}
