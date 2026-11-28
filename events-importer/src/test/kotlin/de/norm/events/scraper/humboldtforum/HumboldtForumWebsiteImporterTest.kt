package de.norm.events.scraper.humboldtforum

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureText
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class HumboldtForumWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = HumboldtForumWebsiteImporter(apiClient)
    private val sourceUrl = "https://www.humboldtforum.org/wp-json/humboldt/v1/event/items?lang=de"

    @Test
    fun `imports the programme from the API response`() =
        runTest {
            coEvery { apiClient.fetchJson(sourceUrl) } returns fixtureText("humboldtforum/humboldtforum-items.json")

            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events.size shouldBe 69
            result.etag shouldBe null
            result.lastModified shouldBe null
        }

    @Test
    fun `returns no events for an empty list`() =
        runTest {
            coEvery { apiClient.fetchJson(sourceUrl) } returns """{"events":[]}"""

            importer
                .importEvents(sourceUrl)
                .shouldBeInstanceOf<ImportResult.Success>()
                .events
                .shouldBeEmpty()
        }

    @Test
    fun `is the Humboldt Forum source`() {
        importer.eventSource shouldBe EventSource.HUMBOLDT_FORUM
        importer.listsWholeProgramme shouldBe false
    }
}
