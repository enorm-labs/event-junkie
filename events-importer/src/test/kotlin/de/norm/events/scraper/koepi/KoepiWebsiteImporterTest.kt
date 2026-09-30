package de.norm.events.scraper.koepi

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.LocalTime

/** Unit tests for [KoepiWebsiteImporter] and the shared radar importer, with a mocked [ApiClient]. */
class KoepiWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = KoepiWebsiteImporter(apiClient)
    private val url = "https://radar.squat.net/api/1.2/search/events.json?facets%5Bgroup%5D%5B%5D=13&limit=500"

    private val fixtureJson: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/radar/radar-group-koepi.json")!!
            .bufferedReader()
            .readText()

    @Test
    fun `imports the concerts with the bill read from each description`() =
        runTest {
            coEvery { apiClient.fetchJson(url) } returns fixtureJson

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 9
            result.complete shouldBe true
            val languid = result.events.single { it.sourceId == "koepi:577799" }
            languid.eventDate shouldBe LocalDate.of(2026, 10, 20)
            languid.startTime shouldBe LocalTime.of(20, 0)
            languid.sourceUrl shouldBe "https://radar.squat.net/en/node/577799"
            languid.eventType shouldBe "CONCERT"
            languid.artists.map { it.name } shouldBe listOf("Languid", "Electric Masochist")
            languid.genre shouldBe "Raw Hardcore Punk, Raw Punk"
        }

    @Test
    fun `marks a page the limit cut short as incomplete`() =
        runTest {
            val cut = fixtureJson.replace("\"count\":26", "\"count\":40")
            coEvery { apiClient.fetchJson(url) } returns cut

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.complete shouldBe false
        }

    @Test
    fun `returns no events for an empty group`() =
        runTest {
            coEvery { apiClient.fetchJson(url) } returns """{"result":[],"count":0}"""

            val result = importer.importEvents(url)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `lists the whole programme and names its source`() {
        importer.eventSource shouldBe EventSource.KOEPI
        importer.listsWholeProgramme shouldBe true
    }
}
