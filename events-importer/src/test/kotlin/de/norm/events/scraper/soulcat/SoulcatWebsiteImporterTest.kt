package de.norm.events.scraper.soulcat

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import de.norm.events.scraper.fixtureText
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class SoulcatWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = SoulcatWebsiteImporter(apiClient)
    private val sourceUrl = "https://soulcat-berlin.com/wp-json/tribe/events/v1/events"

    @Test
    fun `imports the one page of the programme`() =
        runTest {
            coEvery { apiClient.fetchJson("$sourceUrl?per_page=50") } returns fixtureText("soulcat/soulcat-events.json")

            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 21
            result.etag.shouldBeNull()
            result.complete shouldBe true
            coVerify(exactly = 1) { apiClient.fetchJson(any()) }
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.SOULCAT
    }
}
