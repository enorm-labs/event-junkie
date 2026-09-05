package de.norm.events.scraper.festsaal

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Unit tests for [FestsaalWebsiteImporter].
 *
 * Uses a saved JSON fixture and a mocked [ApiClient] for deterministic,
 * offline-safe testing without real HTTP requests.
 */
class FestsaalWebsiteImporterTest {
    private lateinit var importer: FestsaalWebsiteImporter
    private val apiClient: ApiClient = mockk()
    private val apiBaseUrl = "https://admin.festsaal-kreuzberg.de/api/v2/pages/"

    private val fixtureJson: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/festsaal/festsaal-api.json")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = FestsaalWebsiteImporter(apiClient)
        coEvery { apiClient.fetchJson(any()) } returns fixtureJson
    }

    @Test
    fun `importEvents parses all events from the API response`() =
        runTest {
            val result = importer.importEvents(apiBaseUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 78
        }

    @Test
    fun `importEvents builds the Wagtail EventPage query from the configured base URL`() =
        runTest {
            val requestUrl = slot<String>()
            coEvery { apiClient.fetchJson(capture(requestUrl)) } returns fixtureJson

            importer.importEvents(apiBaseUrl)

            coVerify { apiClient.fetchJson(any()) }
            requestUrl.captured shouldStartWith "$apiBaseUrl?"
            requestUrl.captured shouldContain "type=home.EventPage"
            requestUrl.captured shouldContain "fields=title,sub_title,date,doors,start"
            requestUrl.captured shouldContain "genre(title)"
            requestUrl.captured shouldContain "order=date"
            requestUrl.captured shouldContain "limit=100"
        }

    @Test
    fun `importEvents reports no conditional-cache headers as the Wagtail API sends none`() =
        runTest {
            val result = importer.importEvents(apiBaseUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success for a payload without events`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns """{"meta":{"total_count":0},"items":[]}"""

            val result = importer.importEvents(apiBaseUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.FESTSAAL
    }
}
