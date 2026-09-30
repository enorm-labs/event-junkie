package de.norm.events.scraper.drugstore

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Unit tests for [DrugstoreWebsiteImporter], with a mocked [ApiClient]. */
class DrugstoreWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = DrugstoreWebsiteImporter(apiClient)

    @Test
    fun `imports the nights and reads free entry from the description`() =
        runTest {
            coEvery { apiClient.fetchJson(any()) } returns
                javaClass.classLoader
                    .getResourceAsStream("scraper/radar/radar-group-drugstore.json")!!
                    .bufferedReader()
                    .readText()

            val result = importer.importEvents("https://radar.squat.net/api/1.2/search/events.json?facets%5Bgroup%5D%5B%5D=1680")

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events.map { it.title } shouldBe listOf("Konzert", "Eddy Playground", "Hardcore for Solidarity")
            // "Eintritt frei!" and "FREE ENTRY".
            result.events.filter { it.free }.map { it.title } shouldBe listOf("Konzert", "Hardcore for Solidarity")
            // A path alias, not /node/<id>: the id comes from the row's key.
            result.events.first().sourceId shouldBe "drugstore:598022"
            result.events.first().sourceUrl shouldBe "https://radar.squat.net/en/event/berlin/drugstore/2026-10-03/konzert"
            importer.eventSource shouldBe EventSource.DRUGSTORE
        }
}
