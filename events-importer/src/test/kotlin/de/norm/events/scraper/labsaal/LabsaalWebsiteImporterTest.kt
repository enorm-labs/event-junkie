package de.norm.events.scraper.labsaal

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

/** Unit tests for [LabsaalWebsiteImporter], with the saved feed behind a mocked [ApiClient]. */
class LabsaalWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = LabsaalWebsiteImporter(apiClient)
    private val listingUrl = "https://labsaal.de/events/"
    private val requestUrl = slot<String>()

    private val fixture: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/labsaal/labsaal-feed.ics")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(capture(requestUrl)) } returns fixture
    }

    @Test
    fun `importEvents reads the calendar feed on the listing's host`() =
        runTest {
            val result = importer.importEvents(listingUrl)

            requestUrl.captured shouldBe "https://labsaal.de/?mec-ical-feed=1"
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 22
            result.events.all { it.sourceId.startsWith("labsaal:") } shouldBe true
        }

    @Test
    fun `importEvents returns no validators, because the feed is read in full`() =
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
    fun `importer reads the whole programme under its own source`() {
        importer.eventSource shouldBe EventSource.LABSAAL
        importer.listsWholeProgramme shouldBe true
    }
}
