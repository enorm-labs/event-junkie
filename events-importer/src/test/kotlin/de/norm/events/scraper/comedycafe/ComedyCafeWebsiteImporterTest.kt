package de.norm.events.scraper.comedycafe

import de.norm.events.scraper.ApiClient
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.HttpFetchException
import de.norm.events.scraper.ImportResult
import io.kotest.assertions.throwables.shouldThrow
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

class ComedyCafeWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = ComedyCafeWebsiteImporter(apiClient)
    private val sourceUrl = "https://www.comedycafeberlin.com/wp-json/tribe/events/v1/events"
    private val firstPageUrl = "$sourceUrl?per_page=50"
    private val secondPageUrl by lazy { ComedyCafeApiScraper().scrapePage(readFixture("comedycafe-events-page1.json")).nextPageUrl!! }

    private fun readFixture(name: String) =
        javaClass.classLoader
            .getResourceAsStream("scraper/comedycafe/$name")!!
            .bufferedReader()
            .readText()

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(firstPageUrl) } returns readFixture("comedycafe-events-page1.json")
        coEvery { apiClient.fetchJson(secondPageUrl) } returns readFixture("comedycafe-events-page2.json")
    }

    @Test
    fun `follows the cursor to the last page`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 50
            result.complete shouldBe true
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
            coVerify(exactly = 2) { apiClient.fetchJson(any()) }
        }

    @Test
    fun `keeps the first page when the second fails`() =
        runTest {
            coEvery { apiClient.fetchJson(secondPageUrl) } throws HttpFetchException(503, secondPageUrl)

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 49
            result.complete shouldBe false
        }

    @Test
    fun `fails the import when the first page fails`() =
        runTest {
            coEvery { apiClient.fetchJson(firstPageUrl) } throws HttpFetchException(503, firstPageUrl)

            shouldThrow<HttpFetchException> { importer.importEvents(sourceUrl) }
        }

    @Test
    fun `returns no events for an empty programme`() =
        runTest {
            coEvery { apiClient.fetchJson(firstPageUrl) } returns """{"events":[],"total":0,"total_pages":0}"""

            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 0
        }

    @Test
    fun `eventSource matches the enum value`() {
        importer.eventSource shouldBe EventSource.COMEDY_CAFE
    }
}
