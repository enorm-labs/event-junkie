package de.norm.events.scraper.pandaplatforma

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

class PandaPlatformaWebsiteImporterTest {
    private val apiClient: ApiClient = mockk()
    private val importer = PandaPlatformaWebsiteImporter(apiClient)
    private val sourceUrl = "https://panda-platforma.berlin/wp-json/tribe/events/v1/events"
    private val firstPageUrl = "$sourceUrl?per_page=50"

    @BeforeEach
    fun setUp() {
        coEvery { apiClient.fetchJson(firstPageUrl) } returns
            javaClass.classLoader
                .getResourceAsStream("scraper/pandaplatforma/pandaplatforma-events.json")!!
                .bufferedReader()
                .readText()
    }

    @Test
    fun `imports the one page the programme fills`() =
        runTest {
            val result = importer.importEvents(sourceUrl)

            result.shouldBeInstanceOf<ImportResult.Success>()
            result.events shouldHaveSize 21
            result.complete shouldBe true
            result.etag.shouldBeNull()
            result.lastModified.shouldBeNull()
            coVerify(exactly = 1) { apiClient.fetchJson(any()) }
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
        importer.eventSource shouldBe EventSource.PANDA_PLATFORMA
    }
}
