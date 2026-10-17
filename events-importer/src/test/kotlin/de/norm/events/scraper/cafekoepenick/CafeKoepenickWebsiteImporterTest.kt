package de.norm.events.scraper.cafekoepenick

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

class CafeKoepenickWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = CafeKoepenickWebsiteImporter(apiClient)
    private val url =
        "https://export.kalender.digital/ics/2710942/25872d7326c4cadce48e/ffentlicheveranstaltungenangebote.ics?past_months=0&future_months=12"

    @Test
    fun `fetches the configured feed and returns its programme without validators`() =
        runTest {
            coEvery { apiClient.fetchJson(url) } returns
                javaClass.classLoader
                    .getResourceAsStream("scraper/cafekoepenick/cafe-koepenick-calendar.ics")!!
                    .bufferedReader()
                    .readText()

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 79
            result.etag.shouldBeNull()
            coVerify(exactly = 1) { apiClient.fetchJson(url) }
            importer.eventSource shouldBe EventSource.CAFE_KOEPENICK
        }
}
