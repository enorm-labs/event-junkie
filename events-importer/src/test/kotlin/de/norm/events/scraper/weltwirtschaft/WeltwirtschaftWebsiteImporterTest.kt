package de.norm.events.scraper.weltwirtschaft

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class WeltwirtschaftWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = WeltwirtschaftWebsiteImporter(apiClient)
    private val sourceUrl = "https://weltwirtschaft.berlin/wp-json/tribe/events/v1/events"

    @Test
    fun `imports the one page of the programme`() =
        runTest {
            coEvery { apiClient.fetchJson("$sourceUrl?per_page=50") } returns
                javaClass.classLoader
                    .getResourceAsStream("scraper/weltwirtschaft/weltwirtschaft-events.json")!!
                    .bufferedReader()
                    .readText()

            val result = importer.importEvents(sourceUrl).shouldBeInstanceOf<ImportResult.Success>()

            result.events shouldHaveSize 1
            result.etag.shouldBeNull()
            result.complete shouldBe true
            coVerify(exactly = 1) { apiClient.fetchJson(any()) }
        }

    @Test
    fun `an empty programme imports nothing`() =
        runTest {
            coEvery { apiClient.fetchJson("$sourceUrl?per_page=50") } returns """{"events":[],"total":0}"""

            importer
                .importEvents(sourceUrl)
                .shouldBeInstanceOf<ImportResult.Success>()
                .events
                .shouldBeEmpty()
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.WELTWIRTSCHAFT
    }
}
