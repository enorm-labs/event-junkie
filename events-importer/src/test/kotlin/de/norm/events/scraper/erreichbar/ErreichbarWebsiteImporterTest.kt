package de.norm.events.scraper.erreichbar

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

class ErreichbarWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = ErreichbarWebsiteImporter(apiClient)

    @Test
    fun `imports the Punkrocktresen nights and leaves out the brunches and the Kufa`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns
                javaClass.classLoader
                    .getResourceAsStream("scraper/radar/radar-group-erreichbar.json")!!
                    .bufferedReader()
                    .readText()

            val result = importer.importEvents("https://radar.squat.net/api/1.2/search/events.json?facets%5Bgroup%5D%5B%5D=6653&limit=500")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.title }.distinct() shouldBe listOf("Punkrocktresen")
            result.events.size shouldBe
                result.events
                    .map { it.eventDate }
                    .distinct()
                    .size
            val first = result.events.minBy { it.eventDate }
            first.eventDate shouldBe LocalDate.of(2026, 10, 8)
            first.startTime shouldBe LocalTime.of(20, 0)
            first.eventType shouldBe "PARTY"
            first.sourceId shouldBe "erreichbar:${first.sourceUrl.substringAfterLast('/')}"
            result.events.flatMap { it.artists }.shouldBeEmpty()
            importer.eventSource shouldBe EventSource.ERREICHBAR
        }

    @Test
    fun `reads an empty group as no events`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns """{"result":[],"count":0}"""

            val result = importer.importEvents("https://radar.squat.net/api/1.2/search/events.json?facets%5Bgroup%5D%5B%5D=6653&limit=500")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }
}
