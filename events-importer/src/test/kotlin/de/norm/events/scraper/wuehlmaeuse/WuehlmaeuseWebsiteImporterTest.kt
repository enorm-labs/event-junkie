package de.norm.events.scraper.wuehlmaeuse

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class WuehlmaeuseWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = WuehlmaeuseWebsiteImporter(apiClient)
    private val sourceUrl = "https://wuehlmaeuse.de/wp-json/wc/store/v1/products"
    private val page1 = "$sourceUrl?per_page=100&page=1"
    private val page2 = "$sourceUrl?per_page=100&page=2"

    private fun readFixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("scraper/wuehlmaeuse/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(page1) } returns readFixture("wuehlmaeuse-shop-page1.json")
        coEvery { apiClient.fetchJson(page2) } returns readFixture("wuehlmaeuse-shop-last.json")
    }

    @Test
    fun `asks for the next page until one comes back short`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.complete shouldBe true
            result.events.isNotEmpty() shouldBe true
            result.events.size shouldBe
                result.events
                    .map { it.sourceId }
                    .distinct()
                    .size
            coVerify(exactly = 2) { apiClient.fetchJson(any()) }
        }

    @Test
    fun `keeps the first page when the second fails`() =
        runTest {
            coEvery { apiClient.fetchJson(page2) } throws HttpFetchException(503, page2)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.complete shouldBe false
            result.events.isNotEmpty() shouldBe true
        }

    @Test
    fun `returns no events for an empty shop`() =
        runTest {
            coEvery { apiClient.fetchJson(page1) } returns "[]"

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.WUEHLMAEUSE
    }
}
