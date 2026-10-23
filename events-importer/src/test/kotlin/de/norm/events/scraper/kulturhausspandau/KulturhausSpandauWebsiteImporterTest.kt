package de.norm.events.scraper.kulturhausspandau

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class KulturhausSpandauWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = KulturhausSpandauWebsiteImporter(apiClient)
    private val sourceUrl = "https://kulturhaus-spandau.de/wp-json/eventmanager/v1/events"

    @Test
    fun `imports the house's events from the API response`() =
        runTest {
            coEvery { apiClient.fetchJson(sourceUrl) } returns kulturhausSpandauEvents()

            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.size shouldBe 75
            result.etag shouldBe null
        }

    @Test
    fun `returns no events for an empty list`() =
        runTest {
            coEvery { apiClient.fetchJson(sourceUrl) } returns "[]"

            importer
                .importEvents(sourceUrl)
                .shouldBeInstanceOf<ImportResult.Success>()
                .events
                .shouldBeEmpty()
        }

    @Test
    fun `is the Kulturhaus Spandau source and does not claim the whole programme`() {
        importer.eventSource shouldBe EventSource.KULTURHAUS_SPANDAU
        importer.listsWholeProgramme shouldBe false
    }
}
