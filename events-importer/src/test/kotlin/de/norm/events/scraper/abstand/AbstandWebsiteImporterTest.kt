package de.norm.events.scraper.abstand

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

/** Unit tests for [AbstandWebsiteImporter], with a mocked [ApiClient]. */
class AbstandWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = AbstandWebsiteImporter(apiClient)

    @Test
    fun `imports the concerts, bills no one and keeps the price text`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns
                javaClass.classLoader
                    .getResourceAsStream("scraper/radar/radar-group-abstand.json")!!
                    .bufferedReader()
                    .readText()

            val result = importer.importEvents("https://radar.squat.net/api/1.2/search/events.json?facets%5Bgroup%5D%5B%5D=1608")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.title } shouldBe listOf("Geburtstagsgeballer", "HAMBURG. BERLIN. DRESDEN. KRACH", "Konzert")
            result.events.flatMap { it.artists }.shouldBeEmpty()
            result.events.first().priceNote shouldBe "7-78€"
            importer.eventSource shouldBe EventSource.ABSTAND
        }
}
