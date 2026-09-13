package de.norm.events.scraper.sisyphos

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SisyphosWebsiteImporterTest {
    private lateinit var importer: SisyphosWebsiteImporter
    private val apiClient: ApiClient = mockk()
    private val feedUrl = "https://www.sisyphos-berlin.net/collections/tickets/products.json"

    private val fixtureJson: String =
        javaClass.classLoader
            .getResourceAsStream("scraper/sisyphos/sisyphos-tickets.json")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        importer = SisyphosWebsiteImporter(apiClient)
        coEvery { apiClient.fetchJson(feedUrl) } returns fixtureJson
    }

    @Test
    fun `importEvents fetches the configured feed and returns its dated tickets`() =
        runTest {
            val result = importer.importEvents(feedUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 1
            result.events.single().sourceId shouldBe "sisyphos:generations-10-okt-2026"
            coVerify(exactly = 1) { apiClient.fetchJson(feedUrl) }
        }

    @Test
    fun `importEvents reports no conditional-cache headers`() =
        runTest {
            val result = importer.importEvents(feedUrl, etag = "W/\"stale\"", lastModified = "yesterday")
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
        }

    @Test
    fun `importEvents returns an empty success for an empty collection`() =
        runTest {
            coEvery { apiClient.fetchJson(feedUrl) } returns """{"products": []}"""

            val result = importer.importEvents(feedUrl)
            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches expected enum value`() {
        importer.eventSource shouldBe EventSource.SISYPHOS
    }
}
